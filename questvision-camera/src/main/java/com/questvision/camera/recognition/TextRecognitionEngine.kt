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
package com.questvision.camera.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads the text of a saved photo. Throws when recognition fails. */
interface TextRecognitionEngine {
    suspend fun recognize(file: File): RecognizedText

    /**
     * Reads the text of an upright [bitmap]; line boxes are relative to that same bitmap, so a region
     * found in them can be cut from it exactly.
     */
    suspend fun recognize(bitmap: Bitmap): RecognizedText = throw UnsupportedOperationException("bitmap recognition")
}

/**
 * On-device OCR with ML Kit's bundled Latin model: no network, no Play Services download, nothing
 * leaves the phone. Runs on the full-resolution capture (the 640×480 analysis frames are far too
 * small for text), decoding off the main thread; ML Kit runs recognition on its own threads.
 */
@Singleton
class MlKitTextRecognitionEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : TextRecognitionEngine {
        private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

        override suspend fun recognize(file: File): RecognizedText {
            // fromFilePath decodes the JPEG and applies its EXIF orientation.
            val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, Uri.fromFile(file)) }
            // Boxes come in the upright frame: a quarter-turned image swaps its sides.
            val upright = image.rotationDegrees % 180 == 0
            val width = if (upright) image.width else image.height
            val height = if (upright) image.height else image.width
            val text = recognizer.process(image).await()
            if (textTurn(text) == 0) return text.toRecognizedText(width, height)
            // The phone was held sideways or upside down relative to the screen: read it again turned
            // so the lines run left to right (reading order and boxes depend on it).
            val bitmap = withContext(Dispatchers.IO) { decodeUpright(file) } ?: return text.toRecognizedText(width, height)
            return try {
                recognize(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

        override suspend fun recognize(bitmap: Bitmap): RecognizedText {
            val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val turn = textTurn(text)
            if (turn == 0) return text.toRecognizedText(bitmap.width, bitmap.height)
            // ML Kit's angle sign is checked by the result: the other quarter turn when still sideways.
            for (candidate in listOf(turn, (360 - turn) % 360).distinct()) {
                val turned = rotatedForText(bitmap, candidate)
                try {
                    val again = recognizer.process(InputImage.fromBitmap(turned, 0)).await()
                    if (textTurn(again) == 0) return again.toRecognizedText(turned.width, turned.height, candidate)
                } finally {
                    turned.recycle()
                }
            }
            return text.toRecognizedText(bitmap.width, bitmap.height)
        }
    }

/**
 * Clockwise quarter turns (0, 90, 180, 270) that make the text horizontal, from the median angle ML
 * Kit reports for the lines (`Text.Line.getAngle()`: degrees, clockwise positive). 0 for no text.
 */
private fun textTurn(text: Text): Int {
    val angles =
        text.textBlocks
            .flatMap { it.lines }
            .filter { it.text.length >= 4 }
            .map { it.angle }
            .sorted()
    if (angles.isEmpty()) return 0
    val median = angles[angles.size / 2]
    val quarter = Math.round(median / 90f) * 90
    return ((-quarter % 360) + 360) % 360
}

/**
 * [bitmap] turned [degrees] clockwise (a new bitmap; the original is kept). The image encoder turns
 * the photo with this same function, so boxes read from the turned image match its pixels.
 */
fun rotatedForText(
    bitmap: Bitmap,
    degrees: Int,
): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** The photo upright (EXIF), its longest edge at most about 2048 px, for a second reading. */
private fun decodeUpright(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3_000) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val orientation =
        runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    if (degrees == 0) return decoded
    return rotatedForText(decoded, degrees).also { if (it !== decoded) decoded.recycle() }
}

/**
 * Lines in ML Kit's reading order, each with its words and ML Kit's per-word confidence (documented
 * only as a value in [0, 1], not as a calibrated probability), plus the line's box as fractions of the
 * image.
 */
private fun Text.toRecognizedText(
    width: Int,
    height: Int,
    turn: Int = 0,
): RecognizedText {
    val lines = textBlocks.flatMap { it.lines }
    return RecognizedText(
        lines = lines.map { line -> line.elements.map { element -> RecognizedWord(element.text, element.confidence) } },
        boxes =
            lines.map { line ->
                line.boundingBox?.takeIf { width > 0 && height > 0 }?.let { box ->
                    LineBox(
                        left = box.left.toFloat() / width,
                        top = box.top.toFloat() / height,
                        right = box.right.toFloat() / width,
                        bottom = box.bottom.toFloat() / height,
                    )
                }
            },
        contentRotation = turn,
    )
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TextRecognitionModule {
    @Binds
    abstract fun bindTextRecognitionEngine(engine: MlKitTextRecognitionEngine): TextRecognitionEngine
}
