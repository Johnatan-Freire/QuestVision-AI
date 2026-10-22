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
import com.questvision.camera.detection.SyntheticScreens.noisy
import com.questvision.camera.detection.SyntheticScreens.question
import com.questvision.camera.detection.SyntheticScreens.relit
import com.questvision.camera.detection.SyntheticScreens.shifted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenChangeDetectorTest {
    private val detector = ScreenChangeDetector(config)
    private var clockMs = 0L

    /** Feeds [pixels] as one 2 fps sample and returns its outcome; candidates are accepted. */
    private fun feed(pixels: FloatArray): DetectionOutcome {
        val frame = frame(pixels, clockMs)
        clockMs += config.sampleIntervalMs
        val outcome = detector.process(frame).outcome
        if (outcome == DetectionOutcome.CANDIDATE) detector.accept(frame)
        return outcome
    }

    private fun feedRepeatedly(
        pixels: FloatArray,
        samples: Int,
    ): List<DetectionOutcome> = List(samples) { feed(pixels) }

    /** Starts from an accepted [pixels] so tests begin from a known baseline. */
    private fun settleOn(pixels: FloatArray) {
        val outcomes = feedRepeatedly(pixels, samples = 4)
        assertEquals(listOf(DetectionOutcome.CANDIDATE), outcomes.filter { it != DetectionOutcome.NONE })
    }

    @Test
    fun differentQuestions_withSameLayout_differBeyondChangeThreshold() {
        val a = frame(question(1), 0)
        val b = frame(question(2), 0)
        val difference = structureDifference(a, b, config.structureCellThreshold, config.maxShiftCells)
        assertTrue("difference=$difference", difference > config.changeThreshold)
    }

    @Test
    fun significantChange_isCapturedOnceAfterStabilization() {
        settleOn(question(1))

        val outcomes = feedRepeatedly(question(2), samples = 5)

        // Change seen at once, still after 1 sample, settled after stabilizationMs (2 more samples).
        assertEquals(DetectionOutcome.NONE, outcomes[0])
        assertEquals(DetectionOutcome.NONE, outcomes[1])
        assertEquals(DetectionOutcome.CANDIDATE, outcomes[2])
        assertEquals(listOf(DetectionOutcome.NONE, DetectionOutcome.NONE), outcomes.drop(3))
    }

    @Test
    fun smallLightingChanges_areIgnored() {
        val page = question(1)
        settleOn(page)

        val outcomes =
            feedRepeatedly(relit(page, gain = 0.85f, offset = 18f), samples = 3) +
                feedRepeatedly(relit(page, gain = 1.1f, offset = -12f), samples = 3)

        assertTrue(outcomes.all { it == DetectionOutcome.NONE })
        assertEquals(DetectorPhase.WATCHING, detector.phase)
    }

    @Test
    fun stableImage_withSensorNoise_isNotRecaptured() {
        val page = question(1)
        settleOn(page)

        val outcomes = List(10) { feed(noisy(page, amplitude = 3f, seed = it)) }

        assertTrue(outcomes.all { it == DetectionOutcome.NONE })
    }

    @Test
    fun briefCameraMovement_revertsWithoutCapture() {
        val page = question(1)
        settleOn(page)

        val moving = listOf(feed(shifted(page, 6, 3)), feed(shifted(page, -4, 2)))
        val settled = feedRepeatedly(page, samples = 4)

        assertTrue((moving + settled).none { it == DetectionOutcome.CANDIDATE })
        assertTrue(DetectionOutcome.REVERTED in settled)
    }

    @Test
    fun slightDrift_withinShiftTolerance_isNotAChange() {
        val page = question(1)
        settleOn(page)

        val outcomes = feedRepeatedly(shifted(page, 1, 0), samples = 4)

        assertTrue(outcomes.all { it == DetectionOutcome.NONE })
    }

    @Test
    fun repeatedContent_isFlaggedAsDuplicate() {
        settleOn(question(1))
        assertTrue(DetectionOutcome.CANDIDATE in feedRepeatedly(question(2), samples = 4))

        val backToFirst = feedRepeatedly(question(1), samples = 4)

        assertTrue(DetectionOutcome.DUPLICATE in backToFirst)
        assertTrue(DetectionOutcome.CANDIDATE !in backToFirst)
    }

    @Test
    fun rapidConsecutiveChanges_captureOnlyTheFinalContent() {
        settleOn(question(1))

        val flicking = listOf(question(2), question(3), question(4)).map(::feed)
        val final = question(5)
        val settled = feedRepeatedly(final, samples = 4)

        assertTrue(flicking.none { it == DetectionOutcome.CANDIDATE })
        assertEquals(1, settled.count { it == DetectionOutcome.CANDIDATE })
        // The captured content is the final screen: it is now the accepted reference.
        assertTrue(feedRepeatedly(final, samples = 3).all { it == DetectionOutcome.NONE })
    }

    @Test
    fun firstStableFrame_ofASession_isACandidate() {
        val outcomes = feedRepeatedly(question(1), samples = 3)

        assertEquals(DetectionOutcome.CANDIDATE, outcomes.last())
    }

    @Test
    fun stabilityWindow_isNotReusedAfterACapture() {
        // A candidate is decided, then the photo takes a while: frames are skipped, as in a session.
        val first = question(1)
        var candidate: LumaFrame? = null
        while (candidate == null) {
            val frame = frame(first, clockMs)
            clockMs += config.sampleIntervalMs
            if (detector.process(frame).outcome == DetectionOutcome.CANDIDATE) candidate = frame
        }
        clockMs += 3 * config.sampleIntervalMs
        detector.accept(candidate)

        // Right after the capture a different screen is already still: it needs a fresh full window.
        val afterCapture = List(4) { process(question(2)) }

        assertEquals(DetectionOutcome.NONE, afterCapture[0].outcome)
        assertEquals(0L, afterCapture[0].stableForMs)
        assertEquals(DetectionOutcome.NONE, afterCapture[1].outcome)
        assertEquals(DetectionOutcome.CANDIDATE, afterCapture[2].outcome)
        assertTrue(afterCapture[2].stableForMs >= config.stabilizationMs)
    }

    private fun process(pixels: FloatArray): FrameMetrics {
        val metrics = detector.process(frame(pixels, clockMs))
        clockMs += config.sampleIntervalMs
        return metrics
    }
}
