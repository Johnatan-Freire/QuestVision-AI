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

import com.questvision.camera.recognition.LineBox
import com.questvision.camera.recognition.RecognizedText
import com.questvision.camera.recognition.RecognizedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 4: the short framing tip for a photo that didn't get the whole question. */
class FramingHintTest {
    private class L(
        val text: String,
        val top: Float,
        val left: Float = 0.2f,
        val right: Float = 0.8f,
        val height: Float = 0.03f,
        val confidence: Float = 0.95f,
    )

    private fun screen(vararg lines: L) =
        RecognizedText(
            lines = lines.map { line -> line.text.split(" ").map { RecognizedWord(it, line.confidence) } },
            boxes = lines.map { LineBox(it.left, it.top, it.right, it.top + it.height) },
        )

    private fun hint(text: RecognizedText) = framingHint(text, assessQuestion(text))

    private fun question(
        top: Float = 0.2f,
        left: Float = 0.2f,
        right: Float = 0.8f,
        height: Float = 0.03f,
        confidence: Float = 0.95f,
        footer: Boolean = true,
        title: Boolean = true,
    ): RecognizedText {
        val step = height * 1.6f
        val lines = mutableListOf<L>()
        var y = top
        if (title) lines += L("Questão 2", y, left, right, height, confidence).also { y += step }
        lines += L("Qual protocolo traduz nomes de domínio em endereços IP na internet?", y, left, right, height, confidence)
        y += step
        for (alternative in listOf("A) DHCP", "B) FTP", "C) DNS", "D) SSH")) {
            lines += L(alternative, y, left, right, height, confidence)
            y += step
        }
        if (footer) lines += L("Responder", minOf(y + step, 0.96f), left, right, height, confidence)
        return screen(*lines.toTypedArray())
    }

    @Test
    fun aWholeQuestion_needsNoTip() {
        assertNull(hint(question()))
    }

    @Test
    fun cutAtTheBottom_turnDown() {
        // The last alternative ends the photo, at its bottom edge.
        assertEquals(FramingHint.DOWN, hint(question(top = 0.8f, footer = false)))
    }

    @Test
    fun anUntitledQuestionCutAtTheTop_turnUp() {
        assertEquals(FramingHint.UP, hint(question(top = 0.005f, title = false)))
    }

    @Test
    fun cutOnBothEnds_moveAway() {
        assertEquals(FramingHint.FARTHER, hint(question(top = 0.005f, title = false, footer = false, height = 0.15f)))
    }

    @Test
    fun cutAtASide_turnThatWay() {
        // Lines running off an edge get misread: here, as doubtful words.
        assertEquals(FramingHint.LEFT, hint(question(left = 0.005f, confidence = 0.3f)))
        assertEquals(FramingHint.RIGHT, hint(question(right = 0.995f, confidence = 0.3f)))
    }

    @Test
    fun tinyText_moveCloser() {
        assertEquals(FramingHint.CLOSER, hint(question(height = 0.008f, confidence = 0.3f)))
    }

    @Test
    fun doubtfulWords_tapToFocus() {
        assertEquals(FramingHint.BLURRY, hint(question(confidence = 0.3f)))
    }

    @Test
    fun noAlternatives_noQuestion() {
        val text = screen(L("Visual Studio Code", 0.1f), L("Explorer", 0.2f), L("README.md", 0.3f))
        assertEquals(FramingHint.NO_QUESTION, hint(text))
    }
}
