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
 * Stabilization on rendered 640×480 scenes with real-world disturbances (sub-pixel jitter, focus
 * breathing, exposure drift, sensor noise), driven through a [MonitoringSession] like on the device.
 * Reproduces the field failure (a still question never stabilized) and guards the fix.
 */
class RenderedStabilityTest {
    private val config = DetectionConfig()
    private val screens = RenderedScreens(config)
    private val jitter = Random(42)
    private val metrics = ArrayList<FrameMetrics>()
    private val session = MonitoringSession(config) { metrics += it }
    private var clockMs = 0L
    private var noiseSeed = 0

    /** A camera on a stand looking at [seed]: the image is never pixel-identical between samples. */
    private fun stillView(
        seed: Int,
        scroll: Float = 0f,
    ) = RenderedScreens.View(
        seed = seed,
        scroll = scroll,
        dx = jitter.nextFloat() - 0.5f,
        dy = jitter.nextFloat() - 0.5f,
        blur = if (jitter.nextInt(3) == 0) 1 else 0,
        gain = 0.96f + jitter.nextFloat() * 0.08f,
        noise = 4f,
        noiseSeed = noiseSeed++,
    )

    /** Feeds one 2 fps sample; automatic captures complete successfully right away. */
    private fun feed(view: RenderedScreens.View): Boolean {
        val ticket = session.onFrame(screens.frame(view, clockMs), canCaptureNow = true)
        clockMs += config.sampleIntervalMs
        if (ticket != null) session.onCaptureFinished(ticket, success = true)
        return ticket != null
    }

    private fun feedStill(
        seed: Int,
        samples: Int,
    ): List<Boolean> = List(samples) { feed(stillView(seed)) }

    @Test
    fun stillQuestion_stabilizesAndIsCapturedOnce() {
        session.start()

        val captures = feedStill(seed = 1, samples = 20)

        // Within ≈ 1–1.5 s of stability, then never again for the remaining 8 s.
        val first = captures.indexOf(true)
        assertTrue("first capture at sample $first", first in 2..4)
        assertEquals(1, captures.count { it })
        assertTrue(metrics.any { it.phase == DetectorPhase.STABILIZING })
        assertTrue(metrics.any { it.stableForMs >= config.stabilizationMs })
    }

    @Test
    fun fieldFailure_oldFineMetricStaysAboveOldThreshold_whileStructureIsStill() {
        session.start()
        feedStill(seed = 1, samples = 10)

        // Samples without a predecessor (session start, right after a capture) have no motion.
        val sampled = metrics.filter { !it.motion.isNaN() }
        // The pre-fix metric (old still threshold 0.015) flags this still scene as moving.
        assertTrue(sampled.any { it.fineMotion > 0.015f })
        // The structure metric keeps every sample within the still threshold.
        assertTrue(sampled.all { it.motion <= config.stillThreshold })
    }

    @Test
    fun continuousScroll_neverCaptures() {
        for (pixelsPerSample in listOf(1f, 2f, 4f, 25f)) {
            session.stop()
            session.start()
            feedStill(seed = 1, samples = 5)

            var scroll = 0f
            val captures =
                List(16) {
                    scroll += pixelsPerSample
                    feed(stillView(seed = 1, scroll = scroll))
                }

            assertTrue("captured while scrolling $pixelsPerSample px/sample", captures.none { it })
        }
    }

    @Test
    fun scrollThenStop_capturesOnceAfterStabilizing() {
        session.start()
        feedStill(seed = 1, samples = 5)

        var scroll = 0f
        val whileScrolling =
            List(10) {
                scroll += 6f
                feed(stillView(seed = 1, scroll = scroll))
            }
        val afterStopping = List(10) { feed(stillView(seed = 1, scroll = scroll)) }

        assertTrue(whileScrolling.none { it })
        assertEquals(1, afterStopping.count { it })
        // The last scrolling sample already shows the final position, so the scene is still from
        // there: the capture comes stabilizationMs (2 samples) later, not before.
        assertEquals(1, afterStopping.indexOf(true))
    }

    @Test
    fun questionChange_isCapturedAfterStabilizing() {
        session.start()
        feedStill(seed = 1, samples = 6)

        val captures = feedStill(seed = 2, samples = 10)

        assertEquals(1, captures.count { it })
        assertTrue(captures.indexOf(true) in 2..5)
        assertEquals(2, session.autoCaptureCount)
    }

    @Test
    fun lightingChange_isNotCaptured() {
        session.start()
        feedStill(seed = 1, samples = 6)

        val dimmer = List(6) { feed(stillView(seed = 1).copy(gain = 0.8f, offset = 20f)) }
        val brighter = List(6) { feed(stillView(seed = 1).copy(gain = 1.15f, offset = -10f)) }

        assertTrue((dimmer + brighter).none { it })
    }

    @Test
    fun autofocusBlip_doesNotPreventCapture() {
        session.start()
        feedStill(seed = 1, samples = 6)

        // New question; one strongly defocused sample in the middle of stabilization.
        val views =
            listOf(stillView(2), stillView(2), stillView(2).copy(blur = 2), stillView(2)) +
                List(6) { stillView(2) }
        val captures = views.map(::feed)

        assertEquals(1, captures.count { it })
    }

    @Test
    fun returningToACapturedQuestion_isADuplicate() {
        session.start()
        feedStill(seed = 1, samples = 6)
        feedStill(seed = 2, samples = 8)

        val captures = feedStill(seed = 1, samples = 8)

        assertTrue(captures.none { it })
        assertTrue(metrics.any { it.outcome == DetectionOutcome.DUPLICATE })
    }

    @Test
    fun briefCameraBump_isNotCaptured() {
        session.start()
        feedStill(seed = 1, samples = 6)

        val bumped = listOf(feed(stillView(1).copy(dx = 18f, dy = 9f)), feed(stillView(1).copy(dx = -12f)))
        val settled = feedStill(seed = 1, samples = 6)

        assertTrue((bumped + settled).none { it })
    }
}
