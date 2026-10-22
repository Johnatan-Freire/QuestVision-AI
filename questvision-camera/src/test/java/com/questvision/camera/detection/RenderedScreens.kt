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

import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.random.Random

/**
 * Renders a camera's view of a monitor showing a text question at the real analysis resolution
 * (640×480 Y plane), with the disturbances a phone on a stand actually sees: sub-pixel jitter, focus
 * breathing (blur), exposure drift and sensor noise. Frames go through the production
 * [LumaFrame.fromYPlane] path, unlike [SyntheticScreens] which builds grids directly.
 */
internal class RenderedScreens(
    private val config: DetectionConfig = DetectionConfig(),
) {
    data class View(
        val seed: Int,
        /** Sub-pixel camera offset, in analysis pixels. */
        val dx: Float = 0f,
        val dy: Float = 0f,
        /** Page scroll, in analysis pixels. */
        val scroll: Float = 0f,
        /** Box-blur radius in pixels (0 = sharp), standing in for autofocus breathing. */
        val blur: Int = 0,
        val gain: Float = 1f,
        val offset: Float = 0f,
        val noise: Float = 0f,
        val noiseSeed: Int = 0,
        /** Light text on a dark page (e.g. a chat app in dark theme), as in the field test. */
        val darkMode: Boolean = false,
    )

    private val layouts = HashMap<Int, Layout>()

    fun frame(
        view: View,
        timestampMs: Long,
    ): LumaFrame {
        val pixels = render(view)
        val bytes = ByteArray(WIDTH * HEIGHT) { pixels[it].toInt().coerceIn(0, 255).toByte() }
        return LumaFrame.fromYPlane(ByteBuffer.wrap(bytes), WIDTH, HEIGHT, WIDTH, 1, timestampMs, config)
    }

    private fun render(view: View): FloatArray {
        val layout = layouts.getOrPut(view.seed) { Layout(view.seed) }
        val sharp = FloatArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                // 2×2 supersampling gives anti-aliased edges, so sub-pixel offsets change pixel values.
                var sum = 0f
                for (sy in 0..1) {
                    for (sx in 0..1) {
                        sum += layout.sample(x + view.dx + sx * 0.5f, y + view.dy + sy * 0.5f + view.scroll)
                    }
                }
                sharp[y * WIDTH + x] = sum / 4f
            }
        }
        if (view.darkMode) {
            for (i in sharp.indices) sharp[i] = toDarkMode(sharp[i])
        }
        val blurred = if (view.blur > 0) boxBlur(sharp, view.blur) else sharp
        val random = Random(view.noiseSeed)
        return FloatArray(blurred.size) { i ->
            blurred[i] * view.gain + view.offset + (random.nextFloat() * 2 - 1) * view.noise
        }
    }

    /** Inverts page and ink (keeping anti-aliasing) and keeps the bezel dark. */
    private fun toDarkMode(value: Float): Float =
        if (value >= INK) {
            DARK_PAPER + (PAPER - value) / (PAPER - INK) * (DARK_INK - DARK_PAPER)
        } else {
            value / INK * DARK_PAPER
        }

    private fun boxBlur(
        src: FloatArray,
        radius: Int,
    ): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                var sum = 0f
                var count = 0
                for (yy in maxOf(0, y - radius)..minOf(HEIGHT - 1, y + radius)) {
                    for (xx in maxOf(0, x - radius)..minOf(WIDTH - 1, x + radius)) {
                        sum += src[yy * WIDTH + xx]
                        count++
                    }
                }
                out[y * WIDTH + x] = sum / count
            }
        }
        return out
    }

    /** A page of 4×5-pixel glyphs in words and lines, framed by a dark monitor bezel. */
    private class Layout(
        seed: Int,
    ) {
        private val random = Random(seed)
        private val lines =
            List(LINE_COUNT) {
                val glyphs = ArrayList<Pair<Int, Int>>() // x position to glyph bitmap
                var x = PAGE_LEFT + 4
                val end = PAGE_RIGHT - 4 - random.nextInt(0, 160)
                while (x < end) {
                    repeat(random.nextInt(2, 9)) {
                        if (x < end) glyphs += x to random.nextInt(1, 1 shl 20)
                        x += GLYPH_ADVANCE
                    }
                    x += WORD_GAP
                }
                glyphs
            }

        fun sample(
            x: Float,
            y: Float,
        ): Float {
            if (x < PAGE_LEFT || x >= PAGE_RIGHT || y < PAGE_TOP || y >= PAGE_BOTTOM) return BEZEL
            val lineY = y - PAGE_TOP - 6
            if (lineY < 0) return PAPER
            val line = floor(lineY / LINE_PITCH).toInt()
            val inLine = lineY - line * LINE_PITCH
            if (line >= LINE_COUNT || inLine >= GLYPH_HEIGHT) return PAPER
            val glyphs = lines[line]
            // Glyphs are sorted by x; find the one covering this x.
            var lo = 0
            var hi = glyphs.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val gx = glyphs[mid].first
                when {
                    x < gx -> {
                        hi = mid - 1
                    }

                    x >= gx + GLYPH_WIDTH -> {
                        lo = mid + 1
                    }

                    else -> {
                        val bit = inLine.toInt() * GLYPH_WIDTH + (x - gx).toInt()
                        return if (glyphs[mid].second shr bit and 1 == 1) INK else PAPER
                    }
                }
            }
            return PAPER
        }
    }

    companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        private const val PAGE_LEFT = 40
        private const val PAGE_RIGHT = 600
        private const val PAGE_TOP = 30
        private const val PAGE_BOTTOM = 450
        private const val LINE_PITCH = 11
        private const val LINE_COUNT = 36
        private const val GLYPH_WIDTH = 4
        private const val GLYPH_HEIGHT = 5
        private const val GLYPH_ADVANCE = 5
        private const val WORD_GAP = 4
        private const val PAPER = 205f
        private const val INK = 55f
        private const val BEZEL = 25f
        private const val DARK_PAPER = 30f
        private const val DARK_INK = 215f
    }
}
