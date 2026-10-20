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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val CAPTURE_DIRECTORY = "captures"
private const val CAPTURE_EXTENSION = "jpg"

/** Longest edge of the bitmap decoded for on-screen review; the JPEG on disk keeps full resolution. */
private const val REVIEW_MAX_DIMENSION = 2048

/**
 * Owns the captured photos. Captures live in the app's private, non-backed-up storage
 * (`noBackupFilesDir/captures`): they never reach the shared gallery or a cloud backup, and they are
 * removed when the app is uninstalled.
 */
@Singleton
class CaptureRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val captureDirectory = File(context.noBackupFilesDir, CAPTURE_DIRECTORY)

        /**
         * Returns a new, not-yet-written file for the next capture. Synchronous so a capture request
         * can be claimed atomically; the only I/O is a cheap `mkdirs` (a no-op once the folder exists).
         */
        fun newCaptureFile(): File {
            captureDirectory.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            return File(captureDirectory, "capture_$timestamp.$CAPTURE_EXTENSION")
        }

        /** The most recently written capture, or `null` when there is none. */
        suspend fun latestCapture(): File? =
            withContext(Dispatchers.IO) {
                captureDirectory
                    .listFiles { file -> file.isFile && file.extension == CAPTURE_EXTENSION }
                    ?.maxByOrNull { it.lastModified() }
            }

        suspend fun delete(file: File) {
            withContext(Dispatchers.IO) { file.delete() }
        }

        /**
         * Decodes [file] downsampled for display and rotated upright according to its EXIF
         * orientation. Returns `null` if the file is missing or unreadable.
         */
        suspend fun loadForReview(file: File): Bitmap? =
            withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

                val options =
                    BitmapFactory.Options().apply {
                        inSampleSize =
                            calculateInSampleSize(bounds.outWidth, bounds.outHeight, REVIEW_MAX_DIMENSION)
                    }
                val decoded = BitmapFactory.decodeFile(file.path, options) ?: return@withContext null
                val rotation = readExifRotation(file)
                if (rotation == 0) return@withContext decoded

                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap
                    .createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    .also { rotated -> if (rotated !== decoded) decoded.recycle() }
            }

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
