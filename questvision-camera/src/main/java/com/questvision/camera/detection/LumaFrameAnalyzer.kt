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

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

private const val TAG = "QuestVisionDetect"
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * CameraX analyzer that samples frames at [DetectionConfig.sampleIntervalMs] and reduces each sampled
 * frame's Y plane to a [LumaFrame]. Runs on the analysis executor; every [ImageProxy] is closed, even
 * when processing throws. Create a new instance per monitoring run so sampling starts fresh.
 */
class LumaFrameAnalyzer(
    private val config: DetectionConfig,
    private val onFrame: (LumaFrame) -> Unit,
) : ImageAnalysis.Analyzer {
    private val sampler = FrameSampler(config.sampleIntervalMs)

    override fun analyze(image: ImageProxy) {
        try {
            val timestampMs = image.imageInfo.timestamp / NANOS_PER_MILLI
            if (!sampler.shouldSample(timestampMs)) return

            val startedNanos = System.nanoTime()
            val plane = image.planes[0]
            val frame =
                LumaFrame.fromYPlane(
                    buffer = plane.buffer,
                    imageWidth = image.width,
                    imageHeight = image.height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    timestampMs = timestampMs,
                    config = config,
                )
            Log.d(
                TAG,
                "frame ${image.width}x${image.height} reduced in " +
                    "${(System.nanoTime() - startedNanos) / 1_000}µs",
            )
            onFrame(frame)
        } catch (exc: Exception) {
            Log.e(TAG, "Frame analysis failed", exc)
        } finally {
            image.close()
        }
    }
}
