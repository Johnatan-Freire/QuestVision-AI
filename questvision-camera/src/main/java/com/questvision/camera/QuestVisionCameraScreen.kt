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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.camera.core.camerax.CameraXPreview
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
import com.questvision.camera.ai.AiAnalysisPanel
import com.questvision.camera.ai.AutoAnalysisStatus
import com.questvision.camera.detection.MonitoringState
import com.questvision.camera.settings.AiSettingsPanel
import kotlinx.coroutines.delay

private const val NOTICE_VISIBLE_MS = 3_000L

private val ChangeColor = Color(0xFFFFC857)

/** Width of the control column used when the available space is wider than tall. */
private val SIDE_PANEL_WIDTH = 248.dp

/** Below this panel height the zoom stop chips are dropped to keep every control on screen. */
private val ZOOM_STOPS_MIN_PANEL_HEIGHT = 420.dp

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
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAnalysis by rememberSaveable { mutableStateOf(false) }

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
                // History and review are overlaid on the live content (rather than swapping branches
                // or navigating away) so the controller, the camera and a monitoring session keep
                // running while they are open.
                CameraContent(
                    state = state,
                    viewModel = viewModel,
                    onOpenSamples = onOpenSamples,
                    onOpenSettings = { showSettings = true },
                )
                state.history?.let { entries ->
                    BackHandler(onBack = viewModel::closeHistory)
                    CaptureHistory(
                        entries = entries,
                        session = state.session,
                        stats = state.monitoringStats,
                        onBack = viewModel::closeHistory,
                        onOpen = viewModel::openCapture,
                        loadThumbnail = viewModel::loadThumbnail,
                    )
                }
                // Composed after the history, so its back handler takes precedence.
                state.review?.let { bitmap ->
                    BackHandler(onBack = viewModel::closeReview)
                    // Keep = preserve forever; Discard = delete; Back = leave it to retention.
                    CapturedImagePreview(
                        bitmap = bitmap,
                        onRetake = viewModel::discardReviewedCapture,
                        onDone = viewModel::keepReviewedCapture,
                        onBack = viewModel::closeReview,
                        doneLabel = stringResource(R.string.questvision_keep_capture),
                    )
                    state.reviewFile?.let { file ->
                        // Sends nothing by itself: the panel only analyzes on an explicit tap.
                        AiAnalysisButton(onClick = { showAnalysis = true })
                        if (showAnalysis) AiAnalysisPanel(file = file, onDismiss = { showAnalysis = false })
                    }
                }
                if (state.review == null && showAnalysis) showAnalysis = false
                // On top of everything; the camera and monitoring keep running underneath.
                if (showSettings) {
                    AiSettingsPanel(onDismiss = { showSettings = false })
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
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller =
        rememberQuestVisionCameraController(
            context = context,
            lifecycleOwner = lifecycleOwner,
            listener = viewModel,
        )
    val displayRotation = rememberLiveDisplayRotation()
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val isOverlayOpen = state.isOverlayOpen
    val overlayOpen by rememberUpdatedState(isOverlayOpen)

    LaunchedEffect(controller, displayRotation) {
        controller.updateTargetRotation(displayRotation)
    }
    LaunchedEffect(controller, state.zoomRatio) {
        controller.setZoomRatio(state.zoomRatio)
    }
    LaunchedEffect(controller, state.pendingCapture) {
        state.pendingCapture?.let(controller::takePicture)
    }
    LaunchedEffect(controller, state.monitoring.isAnalyzing) {
        controller.setFrameAnalysisEnabled(state.monitoring.isAnalyzing)
    }
    // Keyed on the count too, so each new automatic capture restarts the timer.
    LaunchedEffect(state.notice, state.autoCaptureCount) {
        if (state.notice != null) {
            delay(NOTICE_VISIBLE_MS)
            viewModel.dismissNotice()
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

    // System bars are already padded by the scaffold; also keep controls clear of a display cutout
    // (e.g. a punch-hole camera on the side in landscape).
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout.exclude(WindowInsets.systemBars)),
    ) {
        // Decided from the space actually available (window size, split screen, insets), not from the
        // device orientation alone.
        val useSidePanel = maxWidth > maxHeight
        val availableHeight = maxHeight
        CameraLayout(
            useSidePanel = useSidePanel,
            sidePanelWidth = SIDE_PANEL_WIDTH,
            modifier = Modifier.fillMaxSize(),
            preview = {
                PreviewArea(
                    state = state,
                    controller = controller,
                    focusPoint = focusPoint,
                    onFocusPoint = { focusPoint = it },
                    isOverlayOpen = { overlayOpen },
                )
            },
            top = {
                if (!useSidePanel) {
                    TopBar(state = state, onOpenSamples = onOpenSamples, onOpenSettings = onOpenSettings)
                }
            },
            controls = {
                // Hidden under history/review so taps on the overlay can't reach the controls beneath.
                if (!isOverlayOpen) {
                    if (useSidePanel) {
                        SidePanel(
                            state = state,
                            viewModel = viewModel,
                            onOpenSamples = onOpenSamples,
                            onOpenSettings = onOpenSettings,
                            panelHeight = availableHeight,
                        )
                    } else {
                        BottomControls(state = state, viewModel = viewModel)
                    }
                }
            },
        )
    }
}

/** The 4:3 viewfinder, as large as the given area allows without cropping or distortion. */
@Composable
private fun PreviewArea(
    state: QuestVisionCameraUiState.Previewing,
    controller: QuestVisionCameraController,
    focusPoint: Offset?,
    onFocusPoint: (Offset) -> Unit,
    isOverlayOpen: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Match the 4:3 capture stream so the viewfinder isn't cropped: what you frame is exactly
        // what gets captured. The stream is displayed rotated to the display, hence the orientation.
        val isLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        // A colored outline signals that the monitored screen changed or is being captured.
        val outline =
            when (state.monitoring) {
                MonitoringState.CHANGE_DETECTED, MonitoringState.STABILIZING -> ChangeColor
                MonitoringState.CAPTURING, MonitoringState.VERIFYING -> Color.White
                else -> Color.Transparent
            }
        Box(
            modifier =
                Modifier
                    .aspectRatio(if (isLandscape) 4f / 3f else 3f / 4f)
                    .border(width = 3.dp, color = outline),
        ) {
            controller.surfaceRequest?.let { request ->
                // CameraXPreview keys its tap handler on the SurfaceRequest only, so these
                // callbacks must read current state at tap time instead of being swapped out.
                CameraXPreview(
                    surfaceRequest = request,
                    onTapToFocus = { surfaceCoords, width, height ->
                        if (!isOverlayOpen()) controller.focus(surfaceCoords, width, height)
                    },
                    onFocusTap = { offset ->
                        if (!isOverlayOpen() && controller.isTapToFocusSupported) onFocusPoint(offset)
                    },
                )
            }
            RuleOfThirdsGrid()
            FocusIndicator(tapOffset = focusPoint)
        }
    }
}

/**
 * Controls for the side-panel layout: identity and status on top, monitoring and zoom in the middle,
 * capture at the bottom. Scrolls only if the window is too short for everything to fit.
 */
@Composable
private fun SidePanel(
    state: QuestVisionCameraUiState.Previewing,
    viewModel: QuestVisionCameraViewModel,
    onOpenSamples: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    panelHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = panelHeight)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ViewfinderTitleChip(text = stringResource(R.string.questvision_app_name))
                    Spacer(modifier = Modifier.width(8.dp))
                    SettingsButton(onClick = onOpenSettings)
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
                StatusChip(state = state)
                NoticeText(state = state, style = MaterialTheme.typography.bodySmall)
                MonitoringControls(state = state, viewModel = viewModel, stacked = true)
                AutoAnalysisStatus(state = state.autoAnalysis)
            }

            if (state.minZoom < state.maxZoom) {
                ZoomControls(
                    zoomRatio = state.zoomRatio,
                    valueRange = state.minZoom..state.maxZoom,
                    onValueChange = viewModel::setZoom,
                    stops =
                        if (panelHeight >= ZOOM_STOPS_MIN_PANEL_HEIGHT) {
                            listOf(0.5f, 1f, 2f, 5f)
                        } else {
                            emptyList()
                        },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HistoryButton(viewModel = viewModel)
                CaptureButton(state = state, viewModel = viewModel)
            }
        }
    }
}

/** Opens the AI analysis of the photo under review (top right, opposite the back button). */
@Composable
private fun AiAnalysisButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        ScrimIconButton(
            onClick = onClick,
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = stringResource(R.string.questvision_ai_open),
            size = 34.dp,
            iconSize = 18.dp,
            modifier = Modifier.statusBarsPadding().padding(16.dp),
        )
    }
}

@Composable
private fun SettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScrimIconButton(
        onClick = onClick,
        imageVector = Icons.Filled.Settings,
        contentDescription = stringResource(R.string.questvision_open_settings),
        size = 34.dp,
        iconSize = 18.dp,
        modifier = modifier,
    )
}

/** Opens the capture history; always available, also during monitoring. */
@Composable
private fun HistoryButton(
    viewModel: QuestVisionCameraViewModel,
    modifier: Modifier = Modifier,
) {
    ScrimIconButton(
        onClick = viewModel::openHistory,
        imageVector = Icons.Filled.PhotoLibrary,
        contentDescription = stringResource(R.string.questvision_open_history),
        modifier = modifier,
    )
}

@Composable
private fun CaptureButton(
    state: QuestVisionCameraUiState.Previewing,
    viewModel: QuestVisionCameraViewModel,
    modifier: Modifier = Modifier,
) {
    val captureDescription = stringResource(R.string.questvision_capture)
    ShutterButton(
        onClick = viewModel::capture,
        enabled = state.canCapture,
        modifier = modifier.semantics { contentDescription = captureDescription },
    )
}

@Composable
private fun NoticeText(
    state: QuestVisionCameraUiState.Previewing,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val notice = state.notice ?: return
    Text(
        text =
            when (notice) {
                Notice.CAPTURE_FAILED -> {
                    stringResource(R.string.questvision_capture_failed)
                }

                Notice.AUTO_CAPTURE_SAVED -> {
                    stringResource(R.string.questvision_auto_capture_saved, state.autoCaptureCount)
                }

                Notice.DUPLICATE_IGNORED -> {
                    stringResource(R.string.questvision_duplicate_ignored)
                }

                Notice.UNREADABLE_IGNORED -> {
                    stringResource(R.string.questvision_unreadable_ignored)
                }

                Notice.TEXT_READ_FAILED -> {
                    stringResource(R.string.questvision_text_read_failed)
                }

                Notice.RECORD_FAILED -> {
                    stringResource(R.string.questvision_record_failed)
                }

                Notice.ALREADY_ANALYZED -> {
                    stringResource(R.string.questvision_already_analyzed)
                }

                Notice.INCOMPLETE_SAVED -> {
                    stringResource(R.string.questvision_incomplete_saved)
                }

                Notice.STORAGE_LOW -> {
                    stringResource(R.string.questvision_storage_low)
                }
            },
        style = style,
        color =
            when (notice) {
                Notice.CAPTURE_FAILED, Notice.TEXT_READ_FAILED, Notice.RECORD_FAILED, Notice.STORAGE_LOW -> {
                    MaterialTheme.colorScheme.error
                }

                Notice.AUTO_CAPTURE_SAVED -> {
                    MaterialTheme.colorScheme.primary
                }

                // Skipped candidates are routine: keep them discreet.
                Notice.DUPLICATE_IGNORED, Notice.UNREADABLE_IGNORED, Notice.ALREADY_ANALYZED, Notice.INCOMPLETE_SAVED -> {
                    Color.White.copy(alpha = 0.6f)
                }
            },
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
private fun TopBar(
    state: QuestVisionCameraUiState.Previewing,
    onOpenSamples: (() -> Unit)?,
    onOpenSettings: () -> Unit,
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
        StatusChip(state = state)
        Spacer(modifier = Modifier.width(8.dp))
        SettingsButton(onClick = onOpenSettings)
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
        NoticeText(state = state, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

        MonitoringControls(state = state, viewModel = viewModel)
        AutoAnalysisStatus(state = state.autoAnalysis, modifier = Modifier.padding(horizontal = 24.dp))

        // Fixed-focal-length lenses report min == max; there is nothing to zoom then.
        if (state.minZoom < state.maxZoom) {
            ZoomControls(
                zoomRatio = state.zoomRatio,
                valueRange = state.minZoom..state.maxZoom,
                onValueChange = viewModel::setZoom,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        CameraControlsBar(
            startSlot = {
                HistoryButton(viewModel = viewModel)
            },
            center = { CaptureButton(state = state, viewModel = viewModel) },
        )
    }
}

/**
 * A scrim pill with a colored dot: camera problems first, then the monitoring session state, then
 * the plain camera state.
 */
@Composable
private fun StatusChip(
    state: QuestVisionCameraUiState.Previewing,
    modifier: Modifier = Modifier,
) {
    val (label, dotColor) =
        when {
            state.cameraStatus != CameraStatus.ACTIVE -> {
                statusLabel(state.cameraStatus) to statusColor(state.cameraStatus)
            }

            state.monitoring != MonitoringState.IDLE -> {
                monitoringLabel(state.monitoring) to monitoringColor(state.monitoring)
            }

            state.isCapturing -> {
                stringResource(R.string.questvision_status_capturing) to Color.White
            }

            else -> {
                statusLabel(state.cameraStatus) to statusColor(state.cameraStatus)
            }
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

/**
 * Start / pause / resume / stop for the monitoring session, plus the automatic-capture count. In a
 * narrow column ([stacked]) the buttons wrap if needed and the count sits below them.
 */
@Composable
private fun MonitoringControls(
    state: QuestVisionCameraUiState.Previewing,
    viewModel: QuestVisionCameraViewModel,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
) {
    if (!state.isMonitoringAvailable) {
        Text(
            text = stringResource(R.string.questvision_monitor_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = modifier.padding(horizontal = 24.dp),
        )
        return
    }
    if (stacked) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MonitoringButtons(state = state, viewModel = viewModel)
            }
            AutoCaptureCount(state = state)
        }
    } else {
        Row(
            modifier = modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonitoringButtons(state = state, viewModel = viewModel)
            AutoCaptureCount(state = state)
        }
    }
}

@Composable
private fun MonitoringButtons(
    state: QuestVisionCameraUiState.Previewing,
    viewModel: QuestVisionCameraViewModel,
) {
    when (state.monitoring) {
        MonitoringState.IDLE, MonitoringState.ERROR -> {
            MonitoringPill(
                icon = Icons.Filled.PlayArrow,
                label = stringResource(R.string.questvision_monitor_start),
                onClick = viewModel::startMonitoring,
                highlighted = true,
            )
        }

        MonitoringState.PAUSED -> {
            MonitoringPill(
                icon = Icons.Filled.PlayArrow,
                label = stringResource(R.string.questvision_monitor_resume),
                onClick = viewModel::resumeMonitoring,
                highlighted = true,
            )
            MonitoringPill(
                icon = Icons.Filled.Stop,
                label = stringResource(R.string.questvision_monitor_stop),
                onClick = viewModel::stopMonitoring,
            )
        }

        else -> {
            MonitoringPill(
                icon = Icons.Filled.Pause,
                label = stringResource(R.string.questvision_monitor_pause),
                onClick = viewModel::pauseMonitoring,
            )
            MonitoringPill(
                icon = Icons.Filled.Stop,
                label = stringResource(R.string.questvision_monitor_stop),
                onClick = viewModel::stopMonitoring,
            )
        }
    }
}

@Composable
private fun AutoCaptureCount(
    state: QuestVisionCameraUiState.Previewing,
    modifier: Modifier = Modifier,
) {
    if (state.monitoring == MonitoringState.IDLE) return
    Text(
        text = stringResource(R.string.questvision_monitor_count, state.autoCaptureCount),
        style = TextStyle(fontFamily = monoFontFamily, fontSize = 11.sp),
        color = Color.White.copy(alpha = 0.7f),
        modifier = modifier,
    )
}

@Composable
private fun MonitoringPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val shape = RoundedCornerShape(percent = 50)
    val tint = if (highlighted) MaterialTheme.colorScheme.primary else Color.White
    Row(
        modifier =
            modifier
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.42f))
                .border(1.dp, tint.copy(alpha = if (highlighted) 0.8f else 0.25f), shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
        )
    }
}

@Composable
private fun monitoringLabel(state: MonitoringState): String =
    stringResource(
        when (state) {
            MonitoringState.IDLE, MonitoringState.MONITORING -> R.string.questvision_session_monitoring
            MonitoringState.CHANGE_DETECTED -> R.string.questvision_session_change
            MonitoringState.STABILIZING -> R.string.questvision_session_stabilizing
            MonitoringState.CAPTURING -> R.string.questvision_session_capturing
            MonitoringState.VERIFYING -> R.string.questvision_session_verifying
            MonitoringState.PAUSED -> R.string.questvision_session_paused
            MonitoringState.ERROR -> R.string.questvision_session_error
        },
    )

@Composable
private fun monitoringColor(state: MonitoringState): Color =
    when (state) {
        MonitoringState.IDLE, MonitoringState.MONITORING -> MaterialTheme.colorScheme.primary
        MonitoringState.CHANGE_DETECTED, MonitoringState.STABILIZING -> ChangeColor
        MonitoringState.CAPTURING, MonitoringState.VERIFYING -> Color.White
        MonitoringState.PAUSED -> Color.White.copy(alpha = 0.4f)
        MonitoringState.ERROR -> MaterialTheme.colorScheme.error
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

        CameraStatus.STARTING -> ChangeColor

        CameraStatus.CLOSED -> Color.White.copy(alpha = 0.4f)

        CameraStatus.IN_USE,
        CameraStatus.DISABLED,
        CameraStatus.UNAVAILABLE,
        -> MaterialTheme.colorScheme.error
    }
