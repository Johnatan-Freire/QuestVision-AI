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
) {
    override fun toString() = "EncodedImage(${width}x$height, ${dataUrl.length} chars)"
}

/** Prepares a capture for the image mode; a seam so the analysis can be tested on the JVM. */
interface QuestionImageEncoder {
    /** The upright photo, its longest edge at most [maxDimension], as a JPEG data URL; null if unreadable. */
    suspend fun encode(
        file: File,
        maxDimension: Int,
    ): EncodedImage?
}

/**
 * Re-encodes the stored photo for upload: upright (EXIF), downscaled, JPEG. The original file is only
 * read, never changed.
 */
@Singleton
class JpegQuestionImageEncoder
    @Inject
    constructor(
        private val captures: CaptureRepository,
    ) : QuestionImageEncoder {
        override suspend fun encode(
            file: File,
            maxDimension: Int,
        ): EncodedImage? {
            val decoded = captures.loadForReview(file) ?: return null
            return withContext(Dispatchers.Default) {
                val scale = maxDimension.toFloat() / max(decoded.width, decoded.height)
                val bitmap =
                    if (scale < 1f) {
                        Bitmap.createScaledBitmap(
                            decoded,
                            (decoded.width * scale).roundToInt().coerceAtLeast(1),
                            (decoded.height * scale).roundToInt().coerceAtLeast(1),
                            true,
                        )
                    } else {
                        decoded
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
                    )
                if (bitmap !== decoded) bitmap.recycle()
                decoded.recycle()
                image
            }
        }
    }
