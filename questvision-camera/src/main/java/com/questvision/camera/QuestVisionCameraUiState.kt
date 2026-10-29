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
import com.questvision.camera.ai.AutoAnalysisState
import com.questvision.camera.data.PendingCleanup
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

    /** A photo was saved but its database record failed; it is recovered on the next start. */
    RECORD_FAILED,

    /** An automatic photo showed a question the AI already analyzed; it was discarded, nothing sent. */
    ALREADY_ANALYZED,

    /** An automatic photo was kept, but its question is incomplete or illegible: not sent, a better capture is awaited. */
    INCOMPLETE_SAVED,

    /** Little free storage: monitoring paused before a capture could fail. */
    STORAGE_LOW,

    /** "Discard" was refused: the AI is analyzing this question. */
    DISCARD_REFUSED,

    /** "Discard" failed (the file could not be deleted); nothing was removed. */
    DISCARD_FAILED,

    /** A manual cleanup removed [QuestVisionCameraUiState.Previewing.cleanedCount] pending captures. */
    PENDING_CLEANED,
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
        /** The photo under [review], for its AI analysis. */
        val reviewFile: File? = null,
        /**
         * [reviewFile]'s photo is gone but the AI already saw it: its stored answer is still shown, with
         * no photo (and no discard).
         */
        val reviewPhotoMissing: Boolean = false,
        /** The cleanup of pending captures awaiting the user's confirmation; null when none is asked. */
        val pendingCleanup: PendingCleanup? = null,
        /** How many pending captures the last cleanup removed. */
        val cleanedCount: Int = 0,
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
        /** The automatic analysis of the session's questions. */
        val autoAnalysis: AutoAnalysisState = AutoAnalysisState(),
    ) : QuestVisionCameraUiState {
        val isCapturing: Boolean get() = pendingCapture != null

        /** History or review covers the viewfinder; camera controls are hidden meanwhile. */
        val isOverlayOpen: Boolean get() = review != null || reviewPhotoMissing || history != null
        val canCapture: Boolean
            get() = cameraStatus == CameraStatus.ACTIVE && !isCapturing && !isOverlayOpen
    }

    /** The device has no rear-facing camera. */
    data object Unsupported : QuestVisionCameraUiState

    /** Binding the camera use cases failed; the user can retry. */
    data object Error : QuestVisionCameraUiState
}
