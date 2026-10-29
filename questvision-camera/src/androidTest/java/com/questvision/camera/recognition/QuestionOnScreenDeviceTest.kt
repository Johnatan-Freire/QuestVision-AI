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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.questvision.camera.CaptureRepository
import com.questvision.camera.ai.AssessmentIssue
import com.questvision.camera.ai.ImageEncoding
import com.questvision.camera.ai.JpegQuestionImageEncoder
import com.questvision.camera.ai.assessQuestion
import com.questvision.camera.ai.sameQuestion
import com.questvision.camera.data.AnalysisMode
import com.questvision.camera.data.plainText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 3.4 with the real on-device OCR (ML Kit) on rendered screens shaped like the field setup: a
 * sidebar of conversation titles on the left, a previous message, then the question. Checks what the
 * session relies on (complete / incomplete, untitled questions, near-identical questions) and that the
 * image mode's crop leaves the sidebar out. No camera, no network; works in the test app's cache.
 */
@RunWith(AndroidJUnit4::class)
class QuestionOnScreenDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workDir = File(context.cacheDir, "question-on-screen-test").apply { mkdirs() }
    private val engine = MlKitTextRecognitionEngine(context)

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private val sidebar =
        listOf("Planejar aplicativo Android", "Lembrança De Notificação Extrajudicial", "SISTEMA ESCOLAR", "Meduza Notify")

    /** A 1600×1000 screen: sidebar at x < 340, the conversation from x = 420; [lines] drawn one per row. */
    private fun render(
        name: String,
        lines: List<String>,
        firstRow: Int = 3,
        browserBar: Boolean = false,
    ): File {
        val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint =
            Paint().apply {
                color = Color.BLACK
                textSize = 30f
                isAntiAlias = true
            }
        val side = Paint(paint).apply { textSize = 24f }
        canvas.drawRect(0f, 0f, 340f, 1000f, Paint().apply { color = Color.rgb(235, 235, 235) })
        sidebar.forEachIndexed { i, title -> canvas.drawText(title, 20f, 100f + i * 40f, side) }
        if (browserBar) canvas.drawText("chatgpt.com/c/68f3a1   Gmail   Banco Itaú   Nova guia", 20f, 34f, side)
        lines.forEachIndexed { i, line -> canvas.drawText(line, 420f, 60f + (firstRow + i) * 48f, paint) }
        val file = File(workDir, "$name.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        return file
    }

    private val previous = "Quando o QuestVision AI capturar a questão, diga próxima."
    private val titled =
        listOf(
            previous,
            "",
            "Questão 07 — Redes de Computadores",
            "Qual protocolo atribui endereços IP automaticamente aos dispositivos?",
            "",
            "A) DNS",
            "B) DHCP",
            "C) FTP",
            "D) SSH",
            "",
            "Pergunte ao ChatGPT",
        )
    private val untitled =
        listOf(
            previous,
            "",
            "",
            "Considere uma rede doméstica com vários dispositivos conectados.",
            "Qual protocolo atribui endereços IP automaticamente?",
            "",
            "A) DNS",
            "B) DHCP",
            "C) FTP",
            "D) SSH",
            "",
            "Pergunte ao ChatGPT",
        )

    @Test
    fun aCompleteTitledQuestion_isUsable_andGoesAsText() =
        runBlocking {
            val assessment = assessQuestion(engine.recognize(render("titled", titled)))

            assertTrue(assessment.issues.toString(), assessment.usable)
            assertEquals(7, assessment.questionNumber)
            assertEquals(listOf("A", "B", "C", "D"), assessment.alternatives.keys.toList())
            assertEquals(AnalysisMode.TEXT, assessment.mode)
        }

    @Test
    fun anUntitledQuestion_isFoundByContentAndLayout() =
        runBlocking {
            val text = engine.recognize(render("untitled", untitled))
            val assessment = assessQuestion(text)

            assertTrue("${assessment.issues} ${text.boxes.size}", assessment.usable)
            assertNull(assessment.questionNumber)
            assertEquals(AnalysisMode.IMAGE, assessment.mode)
        }

    @Test
    fun alternativesCutAtTheBottom_areNotComplete() =
        runBlocking {
            // The list reaches the photo's bottom edge: C and D are below it.
            val cut = render("cut", titled.take(7), firstRow = 13)

            assertFalse(assessQuestion(engine.recognize(cut)).usable)
        }

    @Test
    fun theSameQuestion_matches_butANegatedOneDoesNot() =
        runBlocking {
            val first = plainText(engine.recognize(render("a", titled)))
            val again = plainText(engine.recognize(render("b", listOf("Nova conversa") + titled.drop(1))))
            val negated =
                plainText(
                    engine.recognize(
                        render("c", titled.map { it.replace("Qual protocolo atribui", "Qual protocolo NÃO atribui") }),
                    ),
                )

            assertTrue(sameQuestion(again, first))
            assertFalse(sameQuestion(negated, first))
        }

    /** Privacy: the image sent automatically holds the question and none of the sidebar. */
    @Test
    fun theCroppedImage_keepsTheQuestion_andLeavesTheSidebarOut() =
        runBlocking {
            val file = render("crop", untitled)
            val encoder = JpegQuestionImageEncoder(CaptureRepository(workDir), engine)

            val image = (encoder.encode(file, 2_048) as? ImageEncoding.Encoded)?.image

            assertNotNull(image)
            assertTrue(image!!.cropped)
            assertTrue("${image.width}x${image.height}", image.width < 1_600 && image.height < 1_000)
            val bytes = Base64.decode(image.dataUrl.substringAfter(","), Base64.DEFAULT)
            val sent = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val read = plainText(engine.recognize(sent))
            assertTrue(read, read.contains("DHCP"))
            assertTrue(read, read.contains("automaticamente"))
            for (title in listOf("Extrajudicial", "ESCOLAR", "Meduza", "Planejar", "QuestVision")) {
                assertFalse("\"$title\" in the sent image: $read", read.contains(title, ignoreCase = true))
            }
            // The original photo is untouched.
            assertEquals(1_600, BitmapFactory.decodeFile(file.path).width)
        }

    /** No question found: nothing goes, automatically or on request. */
    @Test
    fun withoutAQuestion_theAutomaticModeSendsNothing() =
        runBlocking {
            val file = render("none", listOf(previous, "Um texto qualquer sem alternativas.", "Pergunte ao ChatGPT"))
            val encoder = JpegQuestionImageEncoder(CaptureRepository(workDir), engine)

            assertTrue(AssessmentIssue.NO_ALTERNATIVES in assessQuestion(engine.recognize(file)).issues)
            // Nothing goes, not even on request: the whole photo is never sent.
            assertEquals(ImageEncoding.QuestionNotFound, encoder.encode(file, 2_048))
        }

    /** An untitled question asked with a command ("Assinale…"), a table, and a browser bar on top. */
    @Test
    fun anUntitledCommandWithATable_isFound_andCroppedWithoutTheBrowserBar() =
        runBlocking {
            val lines =
                listOf(
                    previous,
                    "",
                    "",
                    "Observe a tabela de vendas do primeiro trimestre.",
                    "Mês        Vendas",
                    "Janeiro    120",
                    "Fevereiro  95",
                    "Assinale a alternativa com o mês de maior venda.",
                    "",
                    "A) Janeiro",
                    "B) Fevereiro",
                    "C) Março",
                    "",
                    "Pergunte ao ChatGPT",
                )
            val file = render("table", lines, browserBar = true)
            val assessment = assessQuestion(engine.recognize(file))

            assertTrue(assessment.issues.toString(), assessment.usable)
            assertTrue(assessment.visualContent)
            val image = (JpegQuestionImageEncoder(CaptureRepository(workDir), engine).encode(file, 2_048) as? ImageEncoding.Encoded)?.image
            assertNotNull(image)
            val bytes = Base64.decode(image!!.dataUrl.substringAfter(","), Base64.DEFAULT)
            val read = plainText(engine.recognize(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)))
            assertTrue(read, read.contains("Fevereiro"))
            for (outside in listOf("chatgpt.com", "Gmail", "Itaú", "Extrajudicial", "QuestVision")) {
                assertFalse("\"$outside\" in the sent image: $read", read.contains(outside, ignoreCase = true))
            }
        }

    /**
     * [file]'s pixels turned [pixelTurn] degrees clockwise, saved with EXIF [exifOrientation]: how the
     * camera stores a photo of the screen taken with the phone held sideways or upside down.
     */
    private fun turned(
        file: File,
        name: String,
        pixelTurn: Int,
        exifOrientation: Int = android.media.ExifInterface.ORIENTATION_NORMAL,
    ): File {
        val source = BitmapFactory.decodeFile(file.path)
        val turnedBitmap = rotatedForText(source, pixelTurn)
        val out = File(workDir, "$name.jpg")
        out.outputStream().use { turnedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        android.media.ExifInterface(out.path).apply {
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
            saveAttributes()
        }
        return out
    }

    // Field session 2026-10-29: EXIF "rotate 90" and the screen upside down after it (phone held sideways).
    @Test
    fun aScreenPhotographedSidewaysOrUpsideDown_isReadInOrder_andCroppedRight() =
        runBlocking {
            val upright = render("upright", titled)
            val cases =
                listOf(
                    // After EXIF 90° the content is upside down: the pixels are turned 90° counter-clockwise.
                    turned(
                        upright,
                        "exif6-upside-down",
                        pixelTurn = 90,
                        exifOrientation = android.media.ExifInterface.ORIENTATION_ROTATE_90,
                    ),
                    turned(upright, "turn90", pixelTurn = 90),
                    turned(upright, "turn180", pixelTurn = 180),
                    turned(upright, "turn270", pixelTurn = 270),
                )
            val encoder = JpegQuestionImageEncoder(CaptureRepository(workDir), engine)
            for (file in cases) {
                val text = engine.recognize(file)
                val assessment = assessQuestion(text)

                assertTrue("${file.name}: ${assessment.issues} turn=${text.contentRotation}", assessment.usable)
                assertEquals(file.name, 7, assessment.questionNumber)
                assertEquals(file.name, listOf("A", "B", "C", "D"), assessment.alternatives.keys.toList())

                val image = (encoder.encode(file, 2_048) as? ImageEncoding.Encoded)?.image
                assertNotNull(file.name, image)
                val bytes = Base64.decode(image!!.dataUrl.substringAfter(","), Base64.DEFAULT)
                val read = plainText(engine.recognize(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)))
                assertTrue("${file.name}: $read", read.contains("DHCP"))
                for (title in listOf("Extrajudicial", "ESCOLAR", "Meduza", "QuestVision")) {
                    assertFalse("${file.name}: \"$title\" in the sent image", read.contains(title, ignoreCase = true))
                }
            }
        }
}
