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
            return recognizer.process(image).await().toRecognizedText()
        }
    }

private fun Text.toRecognizedText(): RecognizedText =
    RecognizedText(
        textBlocks.flatMap { block ->
            block.lines.map { line ->
                line.elements.map { element -> RecognizedWord(element.text, element.confidence) }
            }
        },
    )

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
