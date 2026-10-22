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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Floor for the high-pass standard deviation, so near-uniform frames don't amplify sensor noise. */
private const val MIN_STD = 2f

/**
 * A compact, illumination-normalized signature of one camera frame. Immutable, so it can be handed
 * across threads.
 *
 * - [values]: the luminance reduced to a [width]×[height] grid, high-pass filtered (each cell minus
 *   its local mean, dropping lighting gradients and soft reflections) and divided by its standard
 *   deviation (cancelling global brightness/contrast). Sensitive to the sub-pixel phase of glyph
 *   edges, so it is only used for diagnostics ([fineDifference]).
 * - [structure]: the local energy of [values] over blocks ([structureMap]), a
 *   [structureWidth]×[structureHeight] map of where text is. Robust to sensor noise, sub-pixel jitter
 *   and focus breathing; all detection decisions use it.
 * - [meanLuma] and [sharpness] (high-pass standard deviation, in gray levels) are logged to tell
 *   exposure and focus changes apart from real motion.
 */
class LumaFrame internal constructor(
    val width: Int,
    val height: Int,
    internal val values: FloatArray,
    val structureWidth: Int,
    val structureHeight: Int,
    internal val structure: FloatArray,
    val meanLuma: Float,
    val sharpness: Float,
    val timestampMs: Long,
) {
    companion object {
        /** Builds a frame from a YUV image's Y plane, as delivered by CameraX `ImageAnalysis`. */
        fun fromYPlane(
            buffer: ByteBuffer,
            imageWidth: Int,
            imageHeight: Int,
            rowStride: Int,
            pixelStride: Int,
            timestampMs: Long,
            config: DetectionConfig,
        ): LumaFrame {
            val grid =
                downsampleLuma(
                    buffer = buffer,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    rowStride = rowStride,
                    pixelStride = pixelStride,
                    outWidth = config.gridWidth,
                    outHeight = config.gridHeight,
                    marginFraction = config.marginFraction,
                )
            return fromLuminance(grid, config.gridWidth, config.gridHeight, timestampMs, config)
        }

        /** Builds a frame from an already-reduced luminance grid (row-major, 0–255). */
        fun fromLuminance(
            luminance: FloatArray,
            width: Int,
            height: Int,
            timestampMs: Long,
            config: DetectionConfig,
        ): LumaFrame {
            require(luminance.size == width * height) { "Grid size mismatch" }
            val highPass = highPass(luminance, width, height, config.highPassRadius)
            return LumaFrame(
                width = width,
                height = height,
                values = highPass.values,
                structureWidth = width,
                structureHeight = height,
                structure = structureMap(highPass.values, width, height, config.structureWindow),
                meanLuma = luminance.average().toFloat(),
                sharpness = highPass.std,
                timestampMs = timestampMs,
            )
        }
    }
}

/** Changed fraction of the fine high-pass grids. Diagnostic only: see [LumaFrame.values]. */
internal fun fineDifference(
    a: LumaFrame,
    b: LumaFrame,
    cellThreshold: Float,
    maxShift: Int,
): Float {
    require(a.width == b.width && a.height == b.height) { "Frames must share a grid size" }
    return changedFraction(a.values, b.values, a.width, a.height, cellThreshold, maxShift)
}

/** Changed fraction of the structure maps; the measure every detection decision is based on. */
internal fun structureDifference(
    a: LumaFrame,
    b: LumaFrame,
    cellThreshold: Float,
    maxShift: Int,
): Float {
    require(a.structureWidth == b.structureWidth && a.structureHeight == b.structureHeight) {
        "Frames must share a grid size"
    }
    return changedFraction(a.structure, b.structure, a.structureWidth, a.structureHeight, cellThreshold, maxShift)
}

/**
 * Averages the Y plane (minus a [marginFraction] border) into an `outWidth × outHeight` grid. Reads
 * the buffer with absolute indices, honoring [rowStride] / [pixelStride].
 */
internal fun downsampleLuma(
    buffer: ByteBuffer,
    imageWidth: Int,
    imageHeight: Int,
    rowStride: Int,
    pixelStride: Int,
    outWidth: Int,
    outHeight: Int,
    marginFraction: Float,
): FloatArray {
    val left = (imageWidth * marginFraction).toInt()
    val top = (imageHeight * marginFraction).toInt()
    val cropWidth = max(imageWidth - 2 * left, 1)
    val cropHeight = max(imageHeight - 2 * top, 1)

    val sums = LongArray(outWidth * outHeight)
    val counts = IntArray(outWidth * outHeight)
    val columnCell = IntArray(cropWidth) { x -> x * outWidth / cropWidth }
    for (y in 0 until cropHeight) {
        val rowCell = (y * outHeight / cropHeight) * outWidth
        val rowStart = (top + y) * rowStride + left * pixelStride
        for (x in 0 until cropWidth) {
            val cell = rowCell + columnCell[x]
            sums[cell] += (buffer.get(rowStart + x * pixelStride).toInt() and 0xFF).toLong()
            counts[cell]++
        }
    }
    return FloatArray(sums.size) { i -> if (counts[i] == 0) 0f else sums[i].toFloat() / counts[i] }
}

/** A high-pass grid scaled to unit variance, and the standard deviation it was divided by. */
internal class HighPass(
    val values: FloatArray,
    val std: Float,
)

/** Subtracts each cell's local box mean (radius [radius]) and scales the result to unit variance. */
internal fun highPass(
    luminance: FloatArray,
    width: Int,
    height: Int,
    radius: Int,
): HighPass {
    // Summed-area table, padded with a zero row/column.
    val stride = width + 1
    val integral = DoubleArray(stride * (height + 1))
    for (y in 0 until height) {
        var rowSum = 0.0
        for (x in 0 until width) {
            rowSum += luminance[y * width + x]
            integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + rowSum
        }
    }

    val highPass = FloatArray(luminance.size)
    var sumSquares = 0.0
    for (y in 0 until height) {
        val y0 = max(y - radius, 0)
        val y1 = min(y + radius, height - 1) + 1
        for (x in 0 until width) {
            val x0 = max(x - radius, 0)
            val x1 = min(x + radius, width - 1) + 1
            val area = (y1 - y0) * (x1 - x0)
            val boxSum =
                integral[y1 * stride + x1] - integral[y0 * stride + x1] -
                    integral[y1 * stride + x0] + integral[y0 * stride + x0]
            val value = luminance[y * width + x] - (boxSum / area).toFloat()
            highPass[y * width + x] = value
            sumSquares += value * value
        }
    }

    val rawStd = sqrt(sumSquares / highPass.size).toFloat()
    val std = max(rawStd, MIN_STD)
    for (i in highPass.indices) highPass[i] /= std
    return HighPass(highPass, rawStd)
}

/**
 * Local text "energy": the absolute high-pass averaged over a sliding [window]×[window] box (anchored
 * at each cell, clipped at the edges) and divided by its mean. It records where text is (lines, words, paragraphs) rather than the exact
 * phase of each glyph edge, so sub-pixel jitter, focus breathing and exposure changes barely affect
 * it. Same size as the input grid, so drift can be compensated one cell at a time.
 */
internal fun structureMap(
    highPass: FloatArray,
    width: Int,
    height: Int,
    window: Int,
): FloatArray {
    val stride = width + 1
    val integral = DoubleArray(stride * (height + 1))
    for (y in 0 until height) {
        var rowSum = 0.0
        for (x in 0 until width) {
            rowSum += abs(highPass[y * width + x])
            integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + rowSum
        }
    }
    val map = FloatArray(width * height)
    var total = 0.0
    for (y in 0 until height) {
        val y1 = min(y + window, height)
        for (x in 0 until width) {
            val x1 = min(x + window, width)
            val boxSum =
                integral[y1 * stride + x1] - integral[y * stride + x1] -
                    integral[y1 * stride + x] + integral[y * stride + x]
            val value = (boxSum / ((y1 - y) * (x1 - x))).toFloat()
            map[y * width + x] = value
            total += value
        }
    }
    val mean = (total / map.size).toFloat()
    val scale = if (mean > 1e-6f) 1f / mean else 0f
    for (i in map.indices) map[i] *= scale
    return map
}

/**
 * Fraction (0–1) of cells of two [width]×[height] grids that differ by more than [cellThreshold],
 * taking the smallest result over every offset of up to [maxShift] cells so slight camera drift
 * isn't counted as change. Only the region valid for every offset is compared.
 */
internal fun changedFraction(
    a: FloatArray,
    b: FloatArray,
    width: Int,
    height: Int,
    cellThreshold: Float,
    maxShift: Int,
): Float {
    val total = (width - 2 * maxShift) * (height - 2 * maxShift)
    if (total <= 0) return 0f
    var best = Int.MAX_VALUE
    for (dy in -maxShift..maxShift) {
        for (dx in -maxShift..maxShift) {
            var changed = 0
            for (y in maxShift until height - maxShift) {
                val rowA = y * width
                val rowB = (y + dy) * width + dx
                for (x in maxShift until width - maxShift) {
                    if (abs(a[rowA + x] - b[rowB + x]) > cellThreshold) changed++
                }
                // This offset can no longer beat the best one found so far.
                if (changed >= best) break
            }
            best = min(best, changed)
        }
    }
    return best.toFloat() / total
}
