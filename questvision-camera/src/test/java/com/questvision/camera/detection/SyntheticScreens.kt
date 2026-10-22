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

import kotlin.random.Random

/** Builds synthetic "screen" luminance grids for detection tests. */
internal object SyntheticScreens {
    val config = DetectionConfig()
    private val width = config.gridWidth
    private val height = config.gridHeight

    private const val BACKGROUND = 215f
    private const val INK = 70f

    /**
     * A light page with dark "words" laid out on text lines, like a question on a monitor. Different
     * seeds produce different text with the same layout (same margins and line spacing).
     */
    fun question(seed: Int): FloatArray {
        val random = Random(seed)
        val pixels = FloatArray(width * height) { BACKGROUND }
        var y = 8
        while (y + 2 < height - 8) {
            var x = 8
            val lineEnd = width - 8 - random.nextInt(0, 30)
            while (x < lineEnd) {
                val wordLength = random.nextInt(3, 12)
                for (wx in x until minOf(x + wordLength, lineEnd)) {
                    for (wy in y until y + 2) pixels[wy * width + wx] = INK
                }
                x += wordLength + random.nextInt(1, 3)
            }
            y += 5
        }
        return pixels
    }

    /** Applies a global brightness/contrast change, as from room lighting or auto-exposure. */
    fun relit(
        pixels: FloatArray,
        gain: Float,
        offset: Float,
    ): FloatArray = FloatArray(pixels.size) { (pixels[it] * gain + offset).coerceIn(0f, 255f) }

    /** Adds per-cell sensor noise of up to ±[amplitude] gray levels. */
    fun noisy(
        pixels: FloatArray,
        amplitude: Float,
        seed: Int,
    ): FloatArray {
        val random = Random(seed)
        return FloatArray(pixels.size) { pixels[it] + (random.nextFloat() * 2 - 1) * amplitude }
    }

    /** Shifts the image by ([dx], [dy]) cells, filling uncovered cells with the background. */
    fun shifted(
        pixels: FloatArray,
        dx: Int,
        dy: Int,
    ): FloatArray =
        FloatArray(pixels.size) { i ->
            val x = i % width - dx
            val y = i / width - dy
            if (x in 0 until width && y in 0 until height) pixels[y * width + x] else BACKGROUND
        }

    fun frame(
        pixels: FloatArray,
        timestampMs: Long,
    ): LumaFrame = LumaFrame.fromLuminance(pixels, width, height, timestampMs, config)
}
