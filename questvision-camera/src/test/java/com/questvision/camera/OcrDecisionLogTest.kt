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

import com.questvision.camera.detection.MonitoringStats
import com.questvision.camera.detection.TextDecision
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.recognition.QuestionTextMatcher
import com.questvision.camera.recognition.TextFingerprint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class OcrDecisionLogTest {
    private fun line(number: Int): Pair<String, TextFingerprint> {
        val text = TextFingerprint.of(OcrScreens.screen(number))
        val earlier = TextFingerprint.of(OcrScreens.screen(if (number == 2) 3 else 2))
        val comparison = QuestionTextMatcher().compare(text, listOf(earlier))
        return ocrDecisionLog(
            "auto_s1_001.jpg",
            text,
            TextDecision(comparison.verdict, comparison),
            elapsedMs = 420,
            stats = MonitoringStats(capturesSaved = 1),
        ) to text
    }

    @Test
    fun theOcrLog_neverContainsRecognizedText() {
        for (number in listOf(2, 3)) {
            val (log, text) = line(number)
            val lower = log.lowercase(Locale.ROOT)
            // Every recognized word of three or more characters (the short ones match counters/labels).
            text.tokens
                .map { it.value }
                .filter { it.length >= 3 && it.any(Char::isLetter) }
                .forEach { word -> assertFalse("\"$word\" leaked into: $log", lower.contains(word.lowercase(Locale.ROOT))) }
            assertFalse(log.contains("text="))
        }
    }

    @Test
    fun theOcrLog_keepsTheDiagnosticMetrics() {
        val (log, text) = line(2)

        assertTrue(log.startsWith("auto_s1_001.jpg NEW_QUESTION ocr=420ms "))
        assertTrue(log.contains("tokens=${text.tokens.size} lines=${text.lines.size} "))
        listOf("words=", "novel=", "ratio=", "run=", "closest=", "capturesSaved=1").forEach { assertTrue(it, log.contains(it)) }
        assertTrue(log.contains(String.format(Locale.US, "simhash=%016x", text.simHash)))
    }
}
