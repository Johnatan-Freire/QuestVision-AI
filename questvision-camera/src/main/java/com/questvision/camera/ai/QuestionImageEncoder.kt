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
package com.questvision.camera.ai

import android.graphics.Bitmap
import android.util.Base64
import com.questvision.camera.CaptureRepository
import com.questvision.camera.recognition.TextRecognitionEngine
import com.questvision.camera.recognition.rotatedForText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** Quality of the JPEG sent to the AI: small artifacts, still crisp screen text. */
private const val UPLOAD_JPEG_QUALITY = 85

/** A photo ready for the request. Never logged. */
class EncodedImage(
    val dataUrl: String,
    val width: Int,
    val height: Int,
    /** Cut to the question (sidebar, titles and browser left out) rather than the whole photo. */
    val cropped: Boolean = false,
) {
    override fun toString() = "EncodedImage(${width}x$height, cropped=$cropped, ${dataUrl.length} chars)"
}

/** How much of the photo the image mode may send. */
enum class RegionPolicy {
    /** Only the question's region; null (nothing sent) when it can't be found safely. Automatic analysis. */
    QUESTION_ONLY,

    /** The question's region when found, else the whole photo: the user chose the image mode. */
    QUESTION_OR_WHOLE,
}

/** Prepares a capture for the image mode; a seam so the analysis can be tested on the JVM. */
interface QuestionImageEncoder {
    /**
     * The upright photo cut per [region], its longest edge at most [maxDimension], as a JPEG data URL;
     * null if unreadable, or if [RegionPolicy.QUESTION_ONLY] and the question's region was not found.
     */
    suspend fun encode(
        file: File,
        maxDimension: Int,
        region: RegionPolicy = RegionPolicy.QUESTION_OR_WHOLE,
    ): EncodedImage?
}

/**
 * Re-encodes the stored photo for upload: upright (EXIF), cut to the question, downscaled, JPEG. The
 * question's region comes from reading the same upright bitmap again with the on-device OCR (see
 * [questionRegion]), so the box and the pixels always match. The original file is only read, never
 * changed.
 */
@Singleton
class JpegQuestionImageEncoder
    @Inject
    constructor(
        private val captures: CaptureRepository,
        private val textRecognition: TextRecognitionEngine,
    ) : QuestionImageEncoder {
        override suspend fun encode(
            file: File,
            maxDimension: Int,
            region: RegionPolicy,
        ): EncodedImage? {
            val photo = captures.loadForReview(file) ?: return null
            val text =
                try {
                    textRecognition.recognize(photo)
                } catch (exc: CancellationException) {
                    photo.recycle()
                    throw exc
                } catch (exc: Exception) {
                    // No reading, no region: the automatic mode sends nothing, the manual one the whole photo.
                    null
                }
            // Turned like the text was read (phone held sideways), so the region's box matches the pixels.
            val decoded =
                text?.contentRotation?.takeIf { it != 0 }?.let { degrees -> rotatedForText(photo, degrees).also { photo.recycle() } }
                    ?: photo
            val found = text?.let { questionRegion(it) }
            if (found == null && region == RegionPolicy.QUESTION_ONLY) {
                decoded.recycle()
                return null
            }
            return withContext(Dispatchers.Default) {
                val cut =
                    found?.let { r ->
                        val left = (r.left * decoded.width).roundToInt().coerceIn(0, decoded.width - 1)
                        val top = (r.top * decoded.height).roundToInt().coerceIn(0, decoded.height - 1)
                        val width = (r.width * decoded.width).roundToInt().coerceIn(1, decoded.width - left)
                        val height = (r.height * decoded.height).roundToInt().coerceIn(1, decoded.height - top)
                        Bitmap.createBitmap(decoded, left, top, width, height)
                    } ?: decoded
                val scale = maxDimension.toFloat() / max(cut.width, cut.height)
                val bitmap =
                    if (scale < 1f) {
                        Bitmap.createScaledBitmap(
                            cut,
                            (cut.width * scale).roundToInt().coerceAtLeast(1),
                            (cut.height * scale).roundToInt().coerceAtLeast(1),
                            true,
                        )
                    } else {
                        cut
                    }
                val bytes =
                    ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, out)
                        out.toByteArray()
                    }
                val image =
                    EncodedImage(
                        dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                        width = bitmap.width,
                        height = bitmap.height,
                        cropped = found != null,
                    )
                if (bitmap !== cut) bitmap.recycle()
                if (cut !== decoded) cut.recycle()
                decoded.recycle()
                image
            }
        }
    }
