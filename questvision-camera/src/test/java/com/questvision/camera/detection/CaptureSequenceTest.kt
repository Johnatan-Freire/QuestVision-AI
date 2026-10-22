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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Whole capture sequences as in the field test of 2026-10-22: a dark-theme page in a dim room
 * (noisy, so the adaptive still threshold reaches its cap), a real capture latency during which the
 * session ignores frames, and the camera settling on a slightly different focus after each photo
 * (a max-quality capture refocuses) plus an exposure transient. Three questions there produced four
 * captures: the second question was photographed twice, 2 s apart.
 */
class CaptureSequenceTest {
    private val config = DetectionConfig()
    private val screens = RenderedScreens(config)
    private val jitter = Random(11)
    private val metrics = ArrayList<FrameMetrics>()
    private val session = MonitoringSession(config) { metrics += it }
    private var clockMs = 0L
    private var noiseSeed = 0

    private var inFlight: CaptureTicket? = null
    private var latencyLeft = 0
    private var transientLeft = 0
    private val capturedSeeds = ArrayList<Int>()

    // Focus state the preview settles on; it flips after every photo.
    private var focusBlur = 0

    private fun view(seed: Int) =
        RenderedScreens.View(
            seed = seed,
            darkMode = true,
            dx = jitter.nextFloat() - 0.5f,
            dy = jitter.nextFloat() - 0.5f,
            blur = focusBlur,
            gain = 0.94f + jitter.nextFloat() * 0.12f,
            noise = 10f,
            noiseSeed = noiseSeed++,
        )

    /**
     * Feeds one 2 fps sample. A requested capture completes [CAPTURE_LATENCY_SAMPLES] samples later
     * (frames meanwhile are ignored by the session, as on the device), and the following
     * [TRANSIENT_SAMPLES] samples are brighter while auto-exposure re-converges.
     */
    private fun feed(
        seed: Int,
        tweak: (RenderedScreens.View) -> RenderedScreens.View = { it },
    ) {
        var v = tweak(view(seed))
        if (transientLeft > 0) {
            v = v.copy(gain = v.gain * 1.3f, offset = 6f)
            transientLeft--
        }
        val ticket = session.onFrame(screens.frame(v, clockMs), canCaptureNow = inFlight == null)
        clockMs += config.sampleIntervalMs
        inFlight?.let { pending ->
            if (--latencyLeft == 0) {
                session.onCaptureFinished(pending, success = true)
                inFlight = null
                transientLeft = TRANSIENT_SAMPLES
                focusBlur = 1 - focusBlur
            }
        }
        if (ticket != null) {
            capturedSeeds += seed
            inFlight = ticket
            latencyLeft = CAPTURE_LATENCY_SAMPLES
        }
    }

    private fun show(
        seed: Int,
        samples: Int,
        tweak: (RenderedScreens.View) -> RenderedScreens.View = { it },
    ) = repeat(samples) { feed(seed, tweak) }

    @Test
    fun stableQuestion_isNotPhotographedAgain_afterCaptureLatencyAndExposureTransient() {
        session.start()

        show(seed = 2, samples = 30)

        assertEquals(listOf(2), capturedSeeds)
    }

    @Test
    fun threeStableQuestions_produceThreeCaptures() {
        session.start()

        show(seed = 1, samples = 14)
        show(seed = 2, samples = 14)
        show(seed = 3, samples = 14)

        assertEquals(listOf(1, 2, 3), capturedSeeds)
        assertEquals(3, session.autoCaptureCount)
    }

    @Test
    fun differentQuestionsShownConsecutively_areAllRegistered() {
        session.start()

        // Each question stays on screen only ≈ 3.5 s (7 samples), just past capture + stabilization.
        listOf(1, 2, 3, 4).forEach { show(seed = it, samples = 7) }

        assertEquals(listOf(1, 2, 3, 4), capturedSeeds)
    }

    @Test
    fun focusAndExposureChanges_afterACapture_areNotANewQuestion() {
        session.start()
        show(seed = 1, samples = 8)

        show(seed = 1, samples = 6) { it.copy(gain = it.gain * 0.75f) }
        show(seed = 1, samples = 6) { it.copy(blur = 1) }
        show(seed = 1, samples = 6) { it.copy(gain = it.gain * 1.2f, offset = 8f) }

        assertEquals(listOf(1), capturedSeeds)
    }

    @Test
    fun continuousScroll_inDarkMode_neverCaptures() {
        session.start()
        show(seed = 1, samples = 8)
        val before = capturedSeeds.size

        var scroll = 0f
        repeat(20) {
            scroll += 5f
            feed(seed = 1) { v -> v.copy(scroll = scroll) }
        }

        assertEquals(before, capturedSeeds.size)
    }

    @Test
    fun decisions_neverFollowAnOutlierOrExceedTheStillThreshold() {
        session.start()
        show(seed = 1, samples = 10)
        show(seed = 2, samples = 3)
        // A strongly defocused sample in the middle of the new question's stabilization.
        feed(seed = 2) { it.copy(blur = 2) }
        show(seed = 2, samples = 8)

        val decisions = metrics.withIndex().filter { it.value.outcome == DetectionOutcome.CANDIDATE }
        assertTrue(decisions.isNotEmpty())
        decisions.forEach { (index, decision) ->
            assertTrue("motion ${decision.motion} > ${decision.stillThreshold}", decision.motion <= decision.stillThreshold)
            assertTrue("decided right after an outlier", metrics[index - 1].outliers == decision.outliers)
        }
    }

    private companion object {
        /** MAXIMIZE_QUALITY capture + JPEG write on the M52 took ≈ 1–1.5 s. */
        const val CAPTURE_LATENCY_SAMPLES = 3
        const val TRANSIENT_SAMPLES = 2
    }
}
