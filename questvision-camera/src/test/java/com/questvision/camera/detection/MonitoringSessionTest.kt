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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringSessionTest {
    private val session = MonitoringSession(config)
    private var clockMs = 0L

    private fun feed(
        pixels: FloatArray,
        canCaptureNow: Boolean = true,
    ): CaptureTicket? {
        val ticket = session.onFrame(frame(pixels, clockMs), canCaptureNow)
        clockMs += config.sampleIntervalMs
        return ticket
    }

    /** Feeds [pixels] until the session asks for a capture (bounded), returning that ticket. */
    private fun feedUntilCapture(pixels: FloatArray): CaptureTicket {
        repeat(6) { feed(pixels)?.let { return it } }
        throw AssertionError("No capture requested")
    }

    @Test
    fun idleSession_ignoresFrames() {
        assertNull(feed(question(1)))
        assertEquals(MonitoringState.IDLE, session.state)
    }

    @Test
    fun capture_returnsToMonitoringAndCounts() {
        session.start()
        val ticket = feedUntilCapture(question(1))
        assertEquals(MonitoringState.CAPTURING, session.state)

        assertTrue(session.onCaptureFinished(ticket, success = true))

        assertEquals(MonitoringState.MONITORING, session.state)
        assertEquals(1, session.autoCaptureCount)
    }

    @Test
    fun noSecondCapture_whileOneIsInFlight() {
        session.start()
        feedUntilCapture(question(1))

        // A new screen appears while the first capture is still being written.
        val tickets = List(6) { feed(question(2)) }

        assertTrue(tickets.all { it == null })
        assertEquals(MonitoringState.CAPTURING, session.state)
    }

    @Test
    fun candidateWaits_whileAManualCaptureIsInFlight() {
        session.start()
        repeat(4) { assertNull(feed(question(1), canCaptureNow = false)) }
        assertEquals(MonitoringState.STABILIZING, session.state)

        assertNotNull(feed(question(1), canCaptureNow = true))
    }

    @Test
    fun pauseDuringCapture_keepsTheResultButStaysPaused() {
        session.start()
        val ticket = feedUntilCapture(question(1))

        session.pause()
        assertEquals(MonitoringState.PAUSED, session.state)
        assertTrue(session.onCaptureFinished(ticket, success = true))

        assertEquals(MonitoringState.PAUSED, session.state)
        assertEquals(1, session.autoCaptureCount)
        // Paused sessions don't analyze, so nothing new is captured.
        assertTrue(List(6) { feed(question(2)) }.all { it == null })

        session.resume()
        assertEquals(MonitoringState.MONITORING, session.state)
    }

    @Test
    fun resumeWhileCaptureStillInFlight_returnsToCapturing() {
        session.start()
        val ticket = feedUntilCapture(question(1))
        session.pause()
        session.resume()

        assertEquals(MonitoringState.CAPTURING, session.state)
        session.onCaptureFinished(ticket, success = true)
        assertEquals(MonitoringState.MONITORING, session.state)
    }

    @Test
    fun stoppingWithPendingCapture_ignoresItsLateResult() {
        session.start()
        val staleTicket = feedUntilCapture(question(1))

        session.stop()
        assertFalse(session.onCaptureFinished(staleTicket, success = true))
        assertEquals(MonitoringState.IDLE, session.state)
        assertEquals(0, session.autoCaptureCount)
        assertNull(feed(question(2)))
    }

    @Test
    fun staleResult_fromPreviousSession_doesNotAffectANewSession() {
        session.start()
        val staleTicket = feedUntilCapture(question(1))
        session.stop()
        session.start()

        assertFalse(session.onCaptureFinished(staleTicket, success = true))
        assertEquals(MonitoringState.MONITORING, session.state)
        assertEquals(0, session.autoCaptureCount)

        // The new session works normally and its own capture is counted.
        val ticket = feedUntilCapture(question(2))
        assertTrue(ticket != staleTicket)
        assertTrue(session.onCaptureFinished(ticket, success = true))
        assertEquals(1, session.autoCaptureCount)
    }

    @Test
    fun repeatedCaptureFailures_endInError() {
        session.start()
        repeat(config.maxConsecutiveCaptureFailures) {
            val ticket = feedUntilCapture(question(1))
            assertFalse(session.onCaptureFinished(ticket, success = false))
        }

        assertEquals(MonitoringState.ERROR, session.state)
        assertNull(feed(question(1)))

        session.start()
        assertEquals(MonitoringState.MONITORING, session.state)
    }

    @Test
    fun stats_auditEveryStepOfTheAutomation() {
        session.start()
        session.onCaptureFinished(feedUntilCapture(question(1)), success = true)
        session.onCaptureFinished(feedUntilCapture(question(2)), success = false)
        session.onCaptureFinished(feedUntilCapture(question(2)), success = true)
        // Back to the first question: settled, but already captured.
        repeat(5) { feed(question(1)) }

        with(session.stats) {
            assertEquals(3, capturesRequested)
            assertEquals(2, capturesSaved)
            assertEquals(1, capturesFailed)
            assertEquals(1, duplicatesIgnored)
            assertTrue("changes=$changesDetected", changesDetected >= 3)
        }
        assertEquals(session.stats.capturesSaved, session.autoCaptureCount)
    }
}
