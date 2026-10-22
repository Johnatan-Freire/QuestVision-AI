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
package com.questvision.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.questvision.camera.detection.DetectionConfig
import com.questvision.camera.detection.LumaFrame
import com.questvision.camera.detection.LumaFrameAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "QuestVisionCamera"

/** High enough that compression artifacts don't blur small glyphs on a photographed monitor. */
private const val JPEG_QUALITY = 95

/** Analysis frames only feed a 128×96 grid; a small stream keeps CPU, memory and bandwidth low. */
private val ANALYSIS_TARGET_SIZE = Size(640, 480)

/** Camera events the controller reports back; implemented by [QuestVisionCameraViewModel]. */
interface CameraSessionListener {
    fun onCameraStatusChanged(status: CameraStatus)

    fun onZoomRangeChanged(
        min: Float,
        max: Float,
    )

    fun onNoBackCamera()

    fun onBindFailed()

    fun onCaptureSaved(file: File)

    fun onCaptureFailed(file: File)

    /** A sampled analysis frame, delivered on the main thread while frame analysis is enabled. */
    fun onFrameAnalyzed(frame: LumaFrame)

    /** The device can't run frame analysis alongside preview and capture. */
    fun onFrameAnalysisUnavailable()
}

@Composable
fun rememberQuestVisionCameraController(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    listener: CameraSessionListener,
): QuestVisionCameraController =
    remember(context, lifecycleOwner, listener) {
        QuestVisionCameraController(context, lifecycleOwner, listener)
    }

/**
 * Owns the CameraX session for QuestVision: the rear camera bound to a [Preview], an [ImageCapture]
 * tuned for legible text (highest resolution, max-quality mode, no flash) and a low-resolution
 * [ImageAnalysis] used for screen-change detection while monitoring.
 */
@Stable
class QuestVisionCameraController(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val listener: CameraSessionListener,
    private val detectionConfig: DetectionConfig = DetectionConfig(),
) {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private val providerScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    var surfaceRequest: SurfaceRequest? by mutableStateOf(null)
        private set

    var isTapToFocusSupported: Boolean by mutableStateOf(false)
        private set

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    // Re-applied after every (re)bind, since a fresh binding starts back at 1×.
    private var requestedZoomRatio = 1f

    // Preview and capture share the 4:3 sensor aspect so the viewfinder shows exactly what's captured.
    private val preview =
        Preview
            .Builder()
            .setResolutionSelector(
                ResolutionSelector
                    .Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build(),
            ).build()
            .apply {
                setSurfaceProvider { request ->
                    surfaceRequest = request
                }
            }

    // Reading text off a monitor: favor the sharpest, largest frame over shutter latency, and never
    // fire the flash (it only adds glare on a screen).
    private val imageCapture =
        ImageCapture
            .Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setJpegQuality(JPEG_QUALITY)
            .setResolutionSelector(
                ResolutionSelector
                    .Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            ).build()

    // Only the latest frame matters for change detection; older ones are dropped, never queued.
    private val imageAnalysis =
        ImageAnalysis
            .Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector
                    .Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            ANALYSIS_TARGET_SIZE,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    ).build(),
            ).build()

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Cleared once binding with analysis fails, so later rebinds go straight to the fallback.
    private var isAnalysisSupported = true

    private val cameraStateObserver =
        Observer<CameraState> { state -> listener.onCameraStatusChanged(state.toCameraStatus()) }

    private val zoomStateObserver =
        Observer<ZoomState> { zoom -> listener.onZoomRangeChanged(zoom.minZoomRatio, zoom.maxZoomRatio) }

    fun openCamera() {
        providerScope.launch {
            val provider =
                try {
                    ProcessCameraProvider.getInstance(appContext).await()
                } catch (exc: CancellationException) {
                    throw exc
                } catch (exc: Exception) {
                    Log.e(TAG, "CameraX initialization failed", exc)
                    listener.onBindFailed()
                    return@launch
                }
            cameraProvider = provider

            if (!provider.hasBackCamera()) {
                listener.onNoBackCamera()
                return@launch
            }

            try {
                detachObservers()
                provider.unbindAll()
                val boundCamera = bindUseCases(provider)
                camera = boundCamera
                isTapToFocusSupported = boundCamera.supportsTapToFocus()
                boundCamera.cameraInfo.cameraState.observe(lifecycleOwner, cameraStateObserver)
                boundCamera.cameraInfo.zoomState.observe(lifecycleOwner, zoomStateObserver)
                boundCamera.cameraControl.setZoomRatio(requestedZoomRatio)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                listener.onBindFailed()
            }
        }
    }

    /**
     * Starts or stops delivering sampled analysis frames to the listener. Each start uses a fresh
     * analyzer so sampling restarts; without an analyzer CameraX drops analysis frames immediately.
     */
    fun setFrameAnalysisEnabled(enabled: Boolean) {
        if (enabled) {
            imageAnalysis.setAnalyzer(
                analysisExecutor,
                LumaFrameAnalyzer(detectionConfig) { frame ->
                    mainExecutor.execute { listener.onFrameAnalyzed(frame) }
                },
            )
        } else {
            imageAnalysis.clearAnalyzer()
        }
    }

    fun updateTargetRotation(rotation: Int) {
        imageCapture.targetRotation = rotation
        preview.targetRotation = rotation
    }

    fun setZoomRatio(ratio: Float) {
        requestedZoomRatio = ratio
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    /**
     * Writes a full-resolution JPEG straight to [file]. CameraX encodes and writes it off the main
     * thread; the result is reported to the listener on the main thread. If the camera isn't bound,
     * CameraX reports an error rather than throwing.
     */
    fun takePicture(file: File) {
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            outputOptions,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    listener.onCaptureSaved(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    listener.onCaptureFailed(file)
                }
            },
        )
    }

    fun focus(
        surfaceCoords: Offset,
        width: Float,
        height: Float,
    ) {
        val boundCamera = camera ?: return
        val factory = SurfaceOrientedMeteringPointFactory(width, height)
        val point = factory.createPoint(surfaceCoords.x, surfaceCoords.y)
        // Meter exposure on the tapped region too, so bright screen content isn't blown out.
        val action =
            FocusMeteringAction
                .Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .build()
        if (boundCamera.cameraInfo.isFocusMeteringSupported(action)) {
            boundCamera.cameraControl.startFocusAndMetering(action)
        }
    }

    fun closeCamera() {
        detachObservers()
        cameraProvider?.unbindAll()
        camera = null
        listener.onCameraStatusChanged(CameraStatus.CLOSED)
    }

    fun release() {
        imageAnalysis.clearAnalyzer()
        closeCamera()
        providerScope.cancel()
        analysisExecutor.shutdown()
    }

    /**
     * Binds preview + capture + analysis. If the device can't stream all three at once, falls back to
     * preview + capture so manual capture keeps working, and reports analysis as unavailable.
     */
    private fun bindUseCases(provider: ProcessCameraProvider): Camera {
        if (isAnalysisSupported) {
            try {
                return provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    imageAnalysis,
                )
            } catch (exc: IllegalArgumentException) {
                Log.w(TAG, "Preview + capture + analysis unsupported; binding without analysis", exc)
                provider.unbindAll()
                isAnalysisSupported = false
                listener.onFrameAnalysisUnavailable()
            }
        }
        return provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture,
        )
    }

    private fun detachObservers() {
        camera?.cameraInfo?.let { info ->
            info.cameraState.removeObserver(cameraStateObserver)
            info.zoomState.removeObserver(zoomStateObserver)
        }
    }

    private fun ProcessCameraProvider.hasBackCamera(): Boolean =
        try {
            hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        } catch (exc: Exception) {
            Log.e(TAG, "Unable to query the rear camera", exc)
            false
        }

    private fun Camera.supportsTapToFocus(): Boolean {
        val center = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
        val action = FocusMeteringAction.Builder(center, FocusMeteringAction.FLAG_AF).build()
        return cameraInfo.isFocusMeteringSupported(action)
    }
}

private fun CameraState.toCameraStatus(): CameraStatus {
    val stateError = error
    if (stateError != null) {
        return when (stateError.code) {
            CameraState.ERROR_CAMERA_IN_USE,
            CameraState.ERROR_MAX_CAMERAS_IN_USE,
            -> CameraStatus.IN_USE

            CameraState.ERROR_CAMERA_DISABLED -> CameraStatus.DISABLED

            else -> CameraStatus.UNAVAILABLE
        }
    }
    return when (type) {
        CameraState.Type.OPEN -> CameraStatus.ACTIVE
        CameraState.Type.PENDING_OPEN, CameraState.Type.OPENING -> CameraStatus.STARTING
        else -> CameraStatus.CLOSED
    }
}
