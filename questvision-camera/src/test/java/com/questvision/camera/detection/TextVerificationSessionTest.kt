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
package com.questvision.camera.detection

import com.questvision.camera.detection.SyntheticScreens.config
import com.questvision.camera.detection.SyntheticScreens.frame
import com.questvision.camera.detection.SyntheticScreens.question
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.recognition.RecognizedText
import com.questvision.camera.recognition.TextFingerprint
import com.questvision.camera.recognition.TextVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextVerificationSessionTest {
    private val session = MonitoringSession(config, verifyText = true)
    private var clockMs = 0L

    private fun feed(seed: Int): CaptureTicket? {
        val ticket = session.onFrame(frame(question(seed), clockMs), canCaptureNow = true)
        clockMs += config.sampleIntervalMs
        return ticket
    }

    private fun captureOf(seed: Int): CaptureTicket {
        repeat(8) { feed(seed)?.let { return it } }
        throw AssertionError("No capture requested")
    }

    private fun text(question: Int) = TextFingerprint.of(OcrScreens.screen(question))

    @Test
    fun savedPhoto_waitsForTheText_beforeCounting() {
        session.start()
        val ticket = captureOf(1)

        assertTrue(session.onCaptureFinished(ticket, success = true))
        assertEquals(MonitoringState.VERIFYING, session.state)
        assertEquals(0, session.autoCaptureCount)
        // No frame is processed while the text is being read.
        assertTrue(List(6) { feed(2) }.all { it == null })

        val decision = session.onTextRecognized(ticket, text(1))!!
        assertTrue(decision.keep)
        assertEquals(1, session.autoCaptureCount)
        assertEquals(MonitoringState.MONITORING, session.state)
    }

    @Test
    fun duplicateText_isNotCounted_butBecomesTheVisualReference() {
        session.start()
        captureOf(1).let {
            session.onCaptureFinished(it, true)
            session.onTextRecognized(it, text(1))
        }

        // Visually different (e.g. glare after the photo), textually the same question.
        val again = captureOf(2)
        session.onCaptureFinished(again, true)
        val decision = session.onTextRecognized(again, text(1))!!

        assertEquals(TextVerdict.DUPLICATE, decision.verdict)
        assertFalse(decision.keep)
        assertEquals(1, session.autoCaptureCount)
        assertEquals(1, session.stats.textDuplicatesIgnored)
        // The same screen is not proposed again.
        assertTrue(List(8) { feed(2) }.all { it == null })
    }

    // 3 + 5: an incomplete question is kept, but a better capture of it still counts as new.
    @Test
    fun anIncompleteQuestion_isKept_butDoesNotBlockABetterCapture() {
        session.start()
        val partial = captureOf(1)
        session.onCaptureFinished(partial, true)
        val first = session.onTextRecognized(partial, text(1), complete = false)!!

        assertEquals(TextVerdict.INCOMPLETE, first.verdict)
        assertTrue(first.keep)
        assertEquals(1, session.stats.incompleteCaptures)

        // The user scrolls: the screen changes and the complete question is captured.
        val better = captureOf(2)
        session.onCaptureFinished(better, true)
        val second = session.onTextRecognized(better, text(1))!!

        assertEquals(TextVerdict.NEW_QUESTION, second.verdict)
        assertEquals(2, session.autoCaptureCount)
    }

    // Field session 2026-10-29: scrolling over a partly visible question must not store it again and again.
    @Test
    fun aRepeatedIncompleteScreen_isDropped_butTheCompleteQuestionStillCounts() {
        session.start()
        val partial = captureOf(1)
        session.onCaptureFinished(partial, true)
        assertEquals(TextVerdict.INCOMPLETE, session.onTextRecognized(partial, text(1), complete = false)!!.verdict)

        val samePartial = captureOf(2)
        session.onCaptureFinished(samePartial, true)
        val repeat = session.onTextRecognized(samePartial, text(1), complete = false)!!

        assertEquals(TextVerdict.DUPLICATE, repeat.verdict)
        assertFalse(repeat.keep)

        // Scrolled further (another scene), the whole question is visible now.
        val complete = captureOf(3)
        session.onCaptureFinished(complete, true)
        assertEquals(TextVerdict.NEW_QUESTION, session.onTextRecognized(complete, text(1))!!.verdict)
        assertEquals(2, session.autoCaptureCount)
    }

    @Test
    fun unreadableText_isNotCounted() {
        session.start()
        val ticket = captureOf(1)
        session.onCaptureFinished(ticket, true)

        val decision = session.onTextRecognized(ticket, TextFingerprint.of(RecognizedText(emptyList())))!!

        assertEquals(TextVerdict.UNREADABLE, decision.verdict)
        assertEquals(0, session.autoCaptureCount)
        assertEquals(1, session.stats.unreadableIgnored)
    }

    @Test
    fun recognitionFailure_retriesTheSameScreen_afterAFreshWindow() {
        session.start()
        val ticket = captureOf(1)
        session.onCaptureFinished(ticket, true)

        assertTrue(session.onTextRecognitionFailed(ticket))
        assertEquals(0, session.autoCaptureCount)
        assertEquals(MonitoringState.MONITORING, session.state)

        // Not proposed on the very next sample (window discarded), but proposed again after settling.
        assertNull(feed(1))
        assertTrue(List(4) { feed(1) }.any { it != null })
    }

    @Test
    fun repeatedRecognitionFailures_endInError() {
        session.start()
        repeat(config.maxConsecutiveCaptureFailures) {
            val ticket = captureOf(1)
            session.onCaptureFinished(ticket, true)
            session.onTextRecognitionFailed(ticket)
        }

        assertEquals(MonitoringState.ERROR, session.state)
        assertEquals(0, session.autoCaptureCount)
    }

    @Test
    fun resultsAfterStop_areStale() {
        session.start()
        val ticket = captureOf(1)
        session.onCaptureFinished(ticket, true)

        session.stop()

        assertNull(session.onTextRecognized(ticket, text(1)))
        assertFalse(session.onTextRecognitionFailed(ticket))
        assertEquals(0, session.autoCaptureCount)
    }

    @Test
    fun threeQuestions_produceThreeCaptures() {
        session.start()

        val kept =
            (1..3).count { n ->
                val ticket = captureOf(n)
                session.onCaptureFinished(ticket, true)
                session.onTextRecognized(ticket, text(n))!!.keep
            }

        assertEquals(3, kept)
        assertEquals(3, session.autoCaptureCount)
        assertEquals(3, session.stats.capturesRequested)
    }

    @Test
    fun captureNumbering_restartsWithEachSession() {
        session.start()
        val first = captureOf(1)
        session.onCaptureFinished(first, true)
        session.onTextRecognized(first, text(1))
        session.stop()

        session.start()
        val again = captureOf(2)

        assertEquals(1, first.sequence)
        assertEquals(1, again.sequence)
    }
}
