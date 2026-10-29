/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.questvision.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val CAPTURE_DIRECTORY = "captures"
private const val KEPT_DIRECTORY = "kept"
private const val CAPTURE_EXTENSION = "jpg"

/** Unpreserved captures beyond this many (newest first) are deleted. Preserved ones are never pruned. */
internal const val MAX_RECENT_CAPTURES = 20

/** Free space below which automatic capturing pauses (about 100 photos of the M52). */
internal const val MIN_FREE_BYTES = 300L * 1024 * 1024

/** Longest edge of the bitmap decoded for on-screen review; the JPEG on disk keeps full resolution. */
private const val REVIEW_MAX_DIMENSION = 2048

/** Longest edge of history thumbnails, and how many bytes of them are kept decoded in memory. */
private const val THUMBNAIL_MAX_DIMENSION = 320
private const val THUMBNAIL_CACHE_BYTES = 12 * 1024 * 1024

private const val TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss_SSS"
private const val SESSION_PATTERN = "yyyyMMdd-HHmmss"

/** How a capture was taken; [UNKNOWN] for files saved before captures were labeled. */
enum class CaptureOrigin {
    MANUAL,
    AUTOMATIC,
    UNKNOWN,
}

/** One stored photo as listed in the history. */
data class CaptureEntry(
    val file: File,
    val capturedAtMs: Long,
    val origin: CaptureOrigin,
    /** Monitoring session (its start time, `yyyyMMdd-HHmmss`) of an automatic capture. */
    val session: String?,
    /** Automatic-capture number within [session]; gaps mean failed captures. */
    val sequence: Int?,
    /** In `kept/`: exempt from retention. */
    val isPreserved: Boolean,
)

/**
 * Owns the captured photos. Captures live in the app's private, non-backed-up storage
 * (`noBackupFilesDir/captures`): they never reach the shared gallery or a cloud backup, and they are
 * removed when the app is uninstalled.
 *
 * Retention: new captures — manual and automatic alike — land in `captures/` and only the
 * [MAX_RECENT_CAPTURES] newest are kept there; older ones are deleted when the app opens and after
 * every new capture. A capture the user chooses to keep is moved to `captures/kept/`, which is never
 * pruned.
 *
 * Each file name records how it was taken (see [captureFileName]), so a photo can always be traced
 * back even without its database row (see `QuestionRepository.reconcile`). Files from before labeling
 * (`capture_<timestamp>.jpg`) remain valid and are listed as [CaptureOrigin.UNKNOWN].
 */
@Singleton
class CaptureRepository internal constructor(
    private val captureDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(File(context.noBackupFilesDir, CAPTURE_DIRECTORY))

    private val keptDirectory = File(captureDirectory, KEPT_DIRECTORY)

    private val thumbnails =
        object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ) = value.byteCount
        }

    /**
     * Returns a new, not-yet-written file for a manual capture. Synchronous so a capture request can
     * be claimed atomically; the only I/O is a cheap `mkdirs` (a no-op once the folder exists).
     */
    fun newManualCaptureFile(nowMs: Long = System.currentTimeMillis()): File =
        newFile(captureFileName(nowMs, CaptureOrigin.MANUAL, session = null, sequence = null))

    /** Returns a new, not-yet-written file for automatic capture [sequence] of [session]. */
    fun newAutomaticCaptureFile(
        session: String,
        sequence: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): File = newFile(captureFileName(nowMs, CaptureOrigin.AUTOMATIC, session, sequence))

    private fun newFile(name: String): File {
        captureDirectory.mkdirs()
        return File(captureDirectory, name)
    }

    /** The most recently written capture, preserved or not, or `null` when there is none. */
    suspend fun latestCapture(): File? =
        withContext(ioDispatcher) {
            (listCaptures(captureDirectory) + listCaptures(keptDirectory))
                .maxByOrNull { it.lastModified() }
        }

    /** Every stored capture, preserved or not, newest first. */
    suspend fun history(): List<CaptureEntry> =
        withContext(ioDispatcher) {
            (listCaptures(captureDirectory) + listCaptures(keptDirectory))
                .map { file -> parseCaptureEntry(file, isPreserved = file.parentFile == keptDirectory) }
                .sortedWith(compareByDescending<CaptureEntry> { it.capturedAtMs }.thenByDescending { it.file.name })
        }

    /**
     * Whether CameraX actually left a usable photo at [file]. A capture only counts once this holds,
     * so a reported success with a missing or empty file is treated as a failure.
     */
    suspend fun isCompleteCapture(file: File): Boolean = withContext(ioDispatcher) { file.isFile && file.length() > 0 }

    /**
     * Moves [file] into the preserved folder, exempting it from retention. Returns its new
     * location, or [file] itself if it was already preserved or could not be moved.
     */
    suspend fun preserve(file: File): File =
        withContext(ioDispatcher) {
            if (file.parentFile == keptDirectory) return@withContext file
            keptDirectory.mkdirs()
            val target = File(keptDirectory, file.name)
            // A rename within the same folder tree keeps lastModified, so ordering is unchanged.
            if (file.renameTo(target)) target else file
        }

    /**
     * Applies the retention policy to unpreserved captures, never touching [protectedPaths] (relative
     * paths, e.g. photos the AI has not processed yet; they neither count toward nor fall under the
     * limit). Returns the relative paths it deleted.
     */
    suspend fun pruneRecentCaptures(protectedPaths: Set<String> = emptySet()): List<String> =
        withContext(ioDispatcher) {
            val candidates = listCaptures(captureDirectory).filter { relativePath(it) !in protectedPaths }
            oldestBeyond(candidates, MAX_RECENT_CAPTURES)
                .filter { it.delete() }
                .map { file ->
                    thumbnails.remove(file.path)
                    relativePath(file)
                }
        }

    /**
     * Whether there is room for more automatic captures. Questions waiting for or analyzed by the AI
     * are never pruned, so a long capture-only session grows storage; below this the session pauses
     * rather than fail captures or crowd the phone.
     */
    fun hasRoomForCaptures(minFreeBytes: Long = MIN_FREE_BYTES): Boolean {
        val volume = generateSequence(captureDirectory) { it.parentFile }.firstOrNull { it.exists() } ?: return true
        return volume.usableSpace >= minFreeBytes
    }

    /** Every stored capture file, preserved or not. */
    suspend fun allCaptureFiles(): List<File> = withContext(ioDispatcher) { listCaptures(captureDirectory) + listCaptures(keptDirectory) }

    /** [file]'s path relative to the captures folder, as stored in the database (`kept/x.jpg`). */
    fun relativePath(file: File): String = file.relativeTo(captureDirectory).invariantSeparatorsPath

    /** The file a relative path from the database points to. */
    fun resolve(relativePath: String): File = File(captureDirectory, relativePath)

    /** Whether [file] is in the preserved folder. */
    fun isPreserved(file: File): Boolean = file.parentFile == keptDirectory

    suspend fun delete(file: File) {
        withContext(ioDispatcher) {
            file.delete()
            thumbnails.remove(file.path)
        }
    }

    /** A small, upright thumbnail of [file] for the history grid, cached in memory. */
    suspend fun loadThumbnail(file: File): Bitmap? {
        thumbnails.get(file.path)?.let { return it }
        return decodeUpright(file, THUMBNAIL_MAX_DIMENSION)?.also { thumbnails.put(file.path, it) }
    }

    /**
     * Decodes [file] downsampled for display and rotated upright according to its EXIF
     * orientation. Returns `null` if the file is missing or unreadable.
     */
    suspend fun loadForReview(file: File): Bitmap? = decodeUpright(file, REVIEW_MAX_DIMENSION)

    private suspend fun decodeUpright(
        file: File,
        maxDimension: Int,
    ): Bitmap? =
        withContext(ioDispatcher) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val options =
                BitmapFactory.Options().apply {
                    inSampleSize =
                        calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                }
            val decoded = BitmapFactory.decodeFile(file.path, options) ?: return@withContext null
            val rotation = readExifRotation(file)
            if (rotation == 0) return@withContext decoded

            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap
                .createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { rotated -> if (rotated !== decoded) decoded.recycle() }
        }

    private fun listCaptures(directory: File): List<File> =
        directory
            .listFiles { file -> file.isFile && file.extension == CAPTURE_EXTENSION }
            ?.toList()
            .orEmpty()

    private fun readExifRotation(file: File): Int {
        val orientation =
            runCatching {
                ExifInterface(file.path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }
}

/**
 * Smallest power-of-two `inSampleSize` for which the decoded longest edge of a `width × height`
 * image is at most [maxDimension].
 */
internal fun calculateInSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
): Int {
    var sampleSize = 1
    val longestEdge = maxOf(width, height)
    while (longestEdge / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

/** Deletes all but the [keep] most recently modified [files]; returns how many were deleted. */
internal fun pruneOldest(
    files: List<File>,
    keep: Int,
): Int = oldestBeyond(files, keep).count { it.delete() }

/** All but the [keep] most recently modified [files]. */
internal fun oldestBeyond(
    files: List<File>,
    keep: Int,
): List<File> = files.sortedByDescending { it.lastModified() }.drop(keep)

private val CAPTURE_NAME =
    Regex("""^capture_(\d{8}_\d{6}_\d{3})(?:_(manual|auto)(?:_([^_]+)_(\d+))?)?\.$CAPTURE_EXTENSION$""")

/**
 * `capture_<yyyyMMdd_HHmmss_SSS>_manual.jpg` or `capture_<…>_auto_<session>_<sequence>.jpg`, where
 * `session` is the monitoring session's start time ([formatSessionLabel]).
 */
internal fun captureFileName(
    capturedAtMs: Long,
    origin: CaptureOrigin,
    session: String?,
    sequence: Int?,
): String {
    val timestamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(Date(capturedAtMs))
    val suffix =
        when (origin) {
            CaptureOrigin.MANUAL -> "_manual"
            CaptureOrigin.AUTOMATIC -> "_auto_${session}_$sequence"
            CaptureOrigin.UNKNOWN -> ""
        }
    return "capture_$timestamp$suffix.$CAPTURE_EXTENSION"
}

/** Reads the metadata encoded in a capture's file name; unrecognized names fall back to the file. */
internal fun parseCaptureEntry(
    file: File,
    isPreserved: Boolean,
): CaptureEntry {
    val match = CAPTURE_NAME.matchEntire(file.name)
    val capturedAtMs =
        match
            ?.groupValues
            ?.get(1)
            ?.let { runCatching { SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).parse(it)?.time }.getOrNull() }
            ?: file.lastModified()
    val origin =
        when (match?.groupValues?.get(2)) {
            "manual" -> CaptureOrigin.MANUAL
            "auto" -> CaptureOrigin.AUTOMATIC
            else -> CaptureOrigin.UNKNOWN
        }
    return CaptureEntry(
        file = file,
        capturedAtMs = capturedAtMs,
        origin = origin,
        session = match?.groupValues?.get(3)?.ifEmpty { null },
        sequence = match?.groupValues?.get(4)?.toIntOrNull(),
        isPreserved = isPreserved,
    )
}

/** Identifies a monitoring session by its start time, e.g. `20261022-141503`. */
internal fun formatSessionLabel(startedAtMs: Long): String = SimpleDateFormat(SESSION_PATTERN, Locale.US).format(Date(startedAtMs))
