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

import android.graphics.Bitmap
import com.questvision.camera.detection.MonitoringState
import com.questvision.camera.detection.MonitoringStats
import java.io.File

/** What the camera device is doing right now, as reported by CameraX `CameraState`. */
enum class CameraStatus {
    STARTING,
    ACTIVE,
    CLOSED,

    /** Another app holds the camera or too many cameras are open; CameraX retries on its own. */
    IN_USE,

    /** The camera was disabled by device policy or the system. */
    DISABLED,

    /** Any other camera error. */
    UNAVAILABLE,
}

/** A transient message shown above the controls. */
enum class Notice {
    CAPTURE_FAILED,
    AUTO_CAPTURE_SAVED,

    /** An automatic photo's text matched an earlier question; it was discarded. */
    DUPLICATE_IGNORED,

    /** An automatic photo had too little readable text; it was discarded. */
    UNREADABLE_IGNORED,

    /** An automatic photo's text could not be read; it was discarded and will be retried. */
    TEXT_READ_FAILED,
}

sealed interface QuestVisionCameraUiState {
    data object Initial : QuestVisionCameraUiState

    data class Previewing(
        val cameraStatus: CameraStatus = CameraStatus.STARTING,
        val zoomRatio: Float = 1f,
        val minZoom: Float = 1f,
        val maxZoom: Float = 1f,
        /** Set while a capture is requested/in flight; the screen hands it to the controller. */
        val pendingCapture: File? = null,
        val notice: Notice? = null,
        /** The capture being reviewed, decoded for display; `null` while the viewfinder is shown. */
        val review: Bitmap? = null,
        /** Stored captures, newest first, while the history is open; `null` when it is closed. */
        val history: List<CaptureEntry>? = null,
        val monitoring: MonitoringState = MonitoringState.IDLE,
        /** Automatic captures of the current session confirmed on disk (`Auto: n`). */
        val autoCaptureCount: Int = 0,
        val monitoringStats: MonitoringStats = MonitoringStats(),
        /** Label of the current (or last) monitoring session, as recorded in capture file names. */
        val session: String? = null,
        /** False when the device can't analyze frames alongside preview and capture. */
        val isMonitoringAvailable: Boolean = true,
    ) : QuestVisionCameraUiState {
        val isCapturing: Boolean get() = pendingCapture != null

        /** History or review covers the viewfinder; camera controls are hidden meanwhile. */
        val isOverlayOpen: Boolean get() = review != null || history != null
        val canCapture: Boolean
            get() = cameraStatus == CameraStatus.ACTIVE && !isCapturing && !isOverlayOpen
    }

    /** The device has no rear-facing camera. */
    data object Unsupported : QuestVisionCameraUiState

    /** Binding the camera use cases failed; the user can retry. */
    data object Error : QuestVisionCameraUiState
}
