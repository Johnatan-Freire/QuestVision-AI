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

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Shows that the structure metric separates a still screen from scrolling, which the pre-fix fine
 * metric could not, and that the default thresholds sit inside the gaps. Prints the before/after
 * table (run with `-i` to see it).
 */
class StructureMetricSeparationTest {
    private val config = DetectionConfig()
    private val screens = RenderedScreens(config)

    private class Range(
        val values: List<Float>,
    ) {
        val min = values.min()
        val max = values.max()

        override fun toString() = "%.3f–%.3f".format(min, max)
    }

    private fun consecutive(
        frames: List<LumaFrame>,
        metric: (LumaFrame, LumaFrame) -> Float,
    ) = Range(frames.zipWithNext(metric))

    private val fine = { a: LumaFrame, b: LumaFrame -> fineDifference(a, b, config.cellDiffThreshold, 1) }
    private val structure = { a: LumaFrame, b: LumaFrame ->
        structureDifference(a, b, config.structureCellThreshold, 0)
    }

    private fun still(): List<LumaFrame> {
        val random = Random(7)
        return List(12) {
            screens.frame(
                RenderedScreens.View(
                    seed = 1,
                    dx = random.nextFloat() - 0.5f,
                    dy = random.nextFloat() - 0.5f,
                    blur = if (it % 3 == 0) 1 else 0,
                    gain = 0.96f + random.nextFloat() * 0.08f,
                    noise = 4f,
                    noiseSeed = it,
                ),
                0,
            )
        }
    }

    private fun scroll(pixelsPerSample: Float) = List(6) { screens.frame(RenderedScreens.View(seed = 1, scroll = it * pixelsPerSample), 0) }

    @Test
    fun structureMetric_separatesStillFromScroll() {
        val stillFrames = still()
        val scrolls = listOf(1f, 2f, 4f, 25f).associateWith { scroll(it) }
        val questions = listOf(1, 2, 3, 4).map { screens.frame(RenderedScreens.View(seed = it), 0) }

        val stillFine = consecutive(stillFrames, fine)
        val stillStructure = consecutive(stillFrames, structure)
        val scrollFine = scrolls.mapValues { consecutive(it.value, fine) }
        val scrollStructure = scrolls.mapValues { consecutive(it.value, structure) }
        val questionChange =
            consecutive(questions) { a, b ->
                structureDifference(a, b, config.structureCellThreshold, config.maxShiftCells)
            }

        println("scene                 fine (before)   structure (after)")
        println("still                 $stillFine     $stillStructure")
        scrolls.keys.forEach { px ->
            println("scroll ${px.toInt()} px/sample    ${scrollFine[px]}     ${scrollStructure[px]}")
        }
        println("question change (±${config.maxShiftCells} cells)            $questionChange")

        // Before: still and slow scroll overlap, so no fixed threshold could separate them.
        assertTrue(stillFine.max >= scrollFine.getValue(4f).min)
        // After: still < base threshold ≤ adaptive cap < slowest scroll.
        assertTrue(stillStructure.max < config.stillThreshold)
        assertTrue(config.maxStillThreshold < scrollStructure.values.minOf { it.min })
        // A different question clears the change threshold.
        assertTrue(questionChange.min > config.changeThreshold)
    }
}
