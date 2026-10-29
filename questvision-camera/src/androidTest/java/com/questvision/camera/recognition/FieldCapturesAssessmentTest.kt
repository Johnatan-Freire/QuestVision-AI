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

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.questvision.camera.CaptureRepository
import com.questvision.camera.ai.ImageEncoding
import com.questvision.camera.ai.JpegQuestionImageEncoder
import com.questvision.camera.ai.assessQuestion
import com.questvision.camera.ai.questionLinesDiagnostics
import com.questvision.camera.ai.questionRegion
import com.questvision.camera.ai.questionRegionDiagnostics
import com.questvision.camera.data.plainText
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

private const val TAG = "QuestVisionField"

/** Things on the field screens that are not part of any question (browser bookmarks, sidebar, chat bar, clock). */
private val OUTSIDE_ITEMS =
    listOf(
        "Whatsapp",
        "Darry",
        "Colinha",
        "Cloud-hosting",
        "Mesa de Estudos",
        "Candidat",
        "Platform",
        "Pergunte ao",
        "29/10/2026",
        "pergunta para teste",
    )

/**
 * Diagnostic, run by hand: the phase 3.4 assessment over copies of real captures placed in the test
 * app's `files/field_captures`. Reports metrics only (verdict, issues, number, format, rotation,
 * region), never recognized text. Skipped when the folder is empty.
 */
@RunWith(AndroidJUnit4::class)
class FieldCapturesAssessmentTest {
    @Test
    fun reportAssessments() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val photos = File(context.filesDir, "field_captures").listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name }.orEmpty()
            assumeTrue(photos.isNotEmpty())
            val engine = MlKitTextRecognitionEngine(context)
            val encoder = JpegQuestionImageEncoder(CaptureRepository(File(context.filesDir, "field_captures")), engine)
            for (photo in photos) {
                val started = System.nanoTime()
                val text = engine.recognize(photo)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val a = assessQuestion(text)
                val region = questionRegion(text)
                // What the automatic image mode would send, read back: count outside items in it.
                val sent = (encoder.encode(photo, 2_048) as? ImageEncoding.Encoded)?.image
                val leaks =
                    sent?.let {
                        val bytes = Base64.decode(it.dataUrl.substringAfter(","), Base64.DEFAULT)
                        // The exact image the automatic mode would send, kept for a visual check.
                        File(context.filesDir, "field_sent").apply { mkdirs() }.resolve("sent_${photo.name}").writeBytes(bytes)
                        val read = plainText(engine.recognize(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)))
                        val again = assessQuestion(engine.recognize(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)))
                        OUTSIDE_ITEMS.filter { item -> read.contains(item, ignoreCase = true) }.joinToString("|").ifEmpty { "0" } +
                            " sentAssessment=${again.usable}/${again.alternatives.size}/${again.issues}"
                    }
                if (photo.name.endsWith("_1.jpg")) Log.i(TAG, "lines ${questionLinesDiagnostics(text)}")
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "%s usable=%s issues=%s n=%s format=%s alternatives=%d titled=%s visual=%s mode=%s turn=%d lines=%d region=%s sent=%s outsideItemsInSent=%s ocr=%dms",
                        photo.name,
                        a.usable,
                        a.issues,
                        a.questionNumber,
                        a.format,
                        a.alternatives.size,
                        a.titled,
                        a.visualContent,
                        a.mode,
                        text.contentRotation,
                        text.lines.size,
                        region?.let { String.format(Locale.US, "%.2f,%.2f-%.2f,%.2f", it.left, it.top, it.right, it.bottom) }
                            ?: "none(${questionRegionDiagnostics(text)})",
                        sent?.let { "${it.width}x${it.height}" } ?: "nothing",
                        leaks ?: "-",
                        elapsedMs,
                    ),
                )
            }
        }
}
