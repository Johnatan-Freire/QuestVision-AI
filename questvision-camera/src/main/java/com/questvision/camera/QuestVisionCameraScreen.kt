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

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.camera.core.camerax.CameraXPreview
import com.android.camera.core.display.rememberDisplayRotation
import com.android.camera.core.permissions.CameraPermissions
import com.android.camera.coretheme.monoFontFamily
import com.android.camera.coreui.controls.CameraControlsBar
import com.android.camera.coreui.controls.ScrimIconButton
import com.android.camera.coreui.controls.ShutterButton
import com.android.camera.coreui.controls.ZoomControls
import com.android.camera.coreui.overlay.FocusIndicator
import com.android.camera.coreui.overlay.RuleOfThirdsGrid
import com.android.camera.coreui.overlay.ViewfinderTitleChip
import com.android.camera.coreui.preview.CapturedImagePreview
import com.android.camera.coreui.scaffold.CameraSampleScaffold
import com.android.camera.coreui.state.ErrorView
import com.android.camera.coreui.state.LoadingView
import com.android.camera.coreui.state.UnsupportedView
import kotlinx.coroutines.delay

private const val CAPTURE_ERROR_VISIBLE_MS = 3_000L

/**
 * The QuestVision home screen: a live rear-camera viewfinder with tap-to-focus, zoom, manual capture
 * to private storage, and a review of the latest capture.
 *
 * @param onOpenSamples when non-null, shows a shortcut to the original camera samples catalog.
 */
@Composable
fun QuestVisionCameraScreen(
    onOpenSamples: (() -> Unit)? = null,
    viewModel: QuestVisionCameraViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CameraSampleScaffold(
        permissions = CameraPermissions.PHOTO,
        api = null,
        rationale = stringResource(R.string.questvision_permission_rationale),
        deniedMessage = stringResource(R.string.questvision_permission_denied),
    ) {
        when (val state = uiState) {
            QuestVisionCameraUiState.Initial -> {
                LoadingView()
            }

            QuestVisionCameraUiState.Unsupported -> {
                UnsupportedView(message = stringResource(R.string.questvision_no_back_camera))
            }

            QuestVisionCameraUiState.Error -> {
                ErrorView(
                    errorMessage = stringResource(R.string.questvision_camera_error),
                    onRetry = viewModel::retry,
                )
            }

            is QuestVisionCameraUiState.Previewing -> {
                // The review is overlaid on the live content (rather than swapping branches) so the
                // controller and camera session survive opening and closing it.
                CameraContent(
                    state = state,
                    viewModel = viewModel,
                    onOpenSamples = onOpenSamples,
                )
                state.review?.let { bitmap ->
                    BackHandler(onBack = viewModel::closeReview)
                    // Keep = preserve forever; Discard = delete; Back = leave it to retention.
                    CapturedImagePreview(
                        bitmap = bitmap,
                        onRetake = viewModel::discardLastCapture,
                        onDone = viewModel::keepLastCapture,
                        onBack = viewModel::closeReview,
                        doneLabel = stringResource(R.string.questvision_keep_capture),
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraContent(
    state: QuestVisionCameraUiState.Previewing,
    viewModel: QuestVisionCameraViewModel,
    onOpenSamples: (() -> Unit)?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller =
        rememberQuestVisionCameraController(
            context = context,
            lifecycleOwner = lifecycleOwner,
            listener = viewModel,
        )
    val displayRotation = rememberDisplayRotation()
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val isReviewing = state.review != null
    val reviewing by rememberUpdatedState(isReviewing)

    LaunchedEffect(controller, displayRotation) {
        controller.updateTargetRotation(displayRotation)
    }
    LaunchedEffect(controller, state.zoomRatio) {
        controller.setZoomRatio(state.zoomRatio)
    }
    LaunchedEffect(controller, state.pendingCapture) {
        state.pendingCapture?.let(controller::takePicture)
    }
    LaunchedEffect(state.lastCaptureFailed) {
        if (state.lastCaptureFailed) {
            delay(CAPTURE_ERROR_VISIBLE_MS)
            viewModel.dismissCaptureError()
        }
    }

    DisposableEffect(lifecycleOwner, controller) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        controller.openCamera()
                    }

                    Lifecycle.Event.ON_PAUSE -> {
                        controller.closeCamera()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            status = state.cameraStatus,
            isCapturing = state.isCapturing,
            onOpenSamples = onOpenSamples,
        )

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // Match the 4:3 capture stream so the viewfinder isn't cropped: what you frame is exactly
            // what gets captured.
            val isLandscape =
                LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            Box(modifier = Modifier.aspectRatio(if (isLandscape) 4f / 3f else 3f / 4f)) {
                controller.surfaceRequest?.let { request ->
                    // CameraXPreview keys its tap handler on the SurfaceRequest only, so these
                    // callbacks must read current state at tap time instead of being swapped out.
                    CameraXPreview(
                        surfaceRequest = request,
                        onTapToFocus = { surfaceCoords, width, height ->
                            if (!reviewing) controller.focus(surfaceCoords, width, height)
                        },
                        onFocusTap = { offset ->
                            if (!reviewing && controller.isTapToFocusSupported) focusPoint = offset
                        },
                    )
                }
                RuleOfThirdsGrid()
                FocusIndicator(tapOffset = focusPoint)
            }
        }

        // Hidden while reviewing so taps on the review overlay can't reach the shutter beneath it.
        if (!isReviewing) {
            BottomControls(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun TopBar(
    status: CameraStatus,
    isCapturing: Boolean,
    onOpenSamples: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewfinderTitleChip(text = stringResource(R.string.questvision_app_name))
        Spacer(modifier = Modifier.weight(1f))
        CameraStatusChip(status = status, isCapturing = isCapturing)
        if (onOpenSamples != null) {
            Spacer(modifier = Modifier.width(8.dp))
            ScrimIconButton(
                onClick = onOpenSamples,
                imageVector = Icons.Filled.Apps,
                contentDescription = stringResource(R.string.questvision_open_samples),
                size = 34.dp,
                iconSize = 18.dp,
            )
        }
    }
}

@Composable
private fun BottomControls(
    state: QuestVisionCameraUiState.Previewing,
    viewModel: QuestVisionCameraViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.lastCaptureFailed) {
            Text(
                text = stringResource(R.string.questvision_capture_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }

        // Fixed-focal-length lenses report min == max; there is nothing to zoom then.
        if (state.minZoom < state.maxZoom) {
            ZoomControls(
                zoomRatio = state.zoomRatio,
                valueRange = state.minZoom..state.maxZoom,
                onValueChange = viewModel::setZoom,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        val captureDescription = stringResource(R.string.questvision_capture)
        CameraControlsBar(
            startSlot = {
                if (state.lastCapture != null) {
                    ScrimIconButton(
                        onClick = viewModel::openLastCapture,
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = stringResource(R.string.questvision_open_last_capture),
                    )
                }
            },
            center = {
                ShutterButton(
                    onClick = viewModel::capture,
                    enabled = state.canCapture,
                    modifier = Modifier.semantics { contentDescription = captureDescription },
                )
            },
        )
    }
}

/** A scrim pill with a colored dot summarizing what the camera is doing. */
@Composable
private fun CameraStatusChip(
    status: CameraStatus,
    isCapturing: Boolean,
    modifier: Modifier = Modifier,
) {
    val (label, dotColor) =
        when {
            isCapturing -> stringResource(R.string.questvision_status_capturing) to Color.White
            else -> statusLabel(status) to statusColor(status)
        }
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.Black.copy(alpha = 0.42f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label.uppercase(),
            style =
                TextStyle(
                    fontFamily = monoFontFamily,
                    fontSize = 10.sp,
                    letterSpacing = 0.1.em,
                ),
            color = Color.White,
        )
    }
}

@Composable
private fun statusLabel(status: CameraStatus): String =
    stringResource(
        when (status) {
            CameraStatus.STARTING -> R.string.questvision_status_starting
            CameraStatus.ACTIVE -> R.string.questvision_status_active
            CameraStatus.CLOSED -> R.string.questvision_status_closed
            CameraStatus.IN_USE -> R.string.questvision_status_in_use
            CameraStatus.DISABLED -> R.string.questvision_status_disabled
            CameraStatus.UNAVAILABLE -> R.string.questvision_status_unavailable
        },
    )

@Composable
private fun statusColor(status: CameraStatus): Color =
    when (status) {
        CameraStatus.ACTIVE -> MaterialTheme.colorScheme.primary

        CameraStatus.STARTING -> Color(0xFFFFC857)

        CameraStatus.CLOSED -> Color.White.copy(alpha = 0.4f)

        CameraStatus.IN_USE,
        CameraStatus.DISABLED,
        CameraStatus.UNAVAILABLE,
        -> MaterialTheme.colorScheme.error
    }
