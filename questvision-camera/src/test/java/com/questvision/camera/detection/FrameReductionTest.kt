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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class FrameReductionTest {
    @Test
    fun downsample_averagesBlocksAndHonorsRowStride() {
        // 4×2 image, row stride 6 (2 padding bytes per row), reduced to 2×1 with no margin.
        val bytes =
            byteArrayOf(
                10,
                20,
                30,
                40,
                99,
                99,
                30,
                40,
                50,
                60,
                99,
                99,
            )
        val grid =
            downsampleLuma(
                buffer = ByteBuffer.wrap(bytes),
                imageWidth = 4,
                imageHeight = 2,
                rowStride = 6,
                pixelStride = 1,
                outWidth = 2,
                outHeight = 1,
                marginFraction = 0f,
            )

        assertArrayEquals(floatArrayOf(25f, 45f), grid, 0.001f)
    }

    @Test
    fun downsample_readsUnsignedBytes() {
        val bytes = byteArrayOf(200.toByte(), 250.toByte())
        val grid = downsampleLuma(ByteBuffer.wrap(bytes), 2, 1, 2, 1, 1, 1, 0f)

        assertEquals(225f, grid[0], 0.001f)
    }

    @Test
    fun uniformFrame_normalizesToZero_withoutAmplifyingNoise() {
        val values = highPass(FloatArray(16 * 12) { 128f }, 16, 12, radius = 3).values

        assertTrue(values.all { it == 0f })
    }

    @Test
    fun sampler_letsThroughOneFramePerInterval() {
        val sampler = FrameSampler(intervalMs = 500)

        val sampled = listOf(0L, 100L, 499L, 500L, 900L, 1_000L).map(sampler::shouldSample)

        assertEquals(listOf(true, false, false, true, false, true), sampled)
        assertFalse(sampler.shouldSample(1_200L))
    }
}
