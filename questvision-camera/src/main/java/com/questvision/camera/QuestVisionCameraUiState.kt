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

sealed interface QuestVisionCameraUiState {
    data object Initial : QuestVisionCameraUiState

    data class Previewing(
        val cameraStatus: CameraStatus = CameraStatus.STARTING,
        val zoomRatio: Float = 1f,
        val minZoom: Float = 1f,
        val maxZoom: Float = 1f,
        /** Set while a capture is requested/in flight; the screen hands it to the controller. */
        val pendingCapture: File? = null,
        val lastCapture: File? = null,
        val lastCaptureFailed: Boolean = false,
        /** The capture being reviewed, decoded for display; `null` while the viewfinder is shown. */
        val review: Bitmap? = null,
    ) : QuestVisionCameraUiState {
        val isCapturing: Boolean get() = pendingCapture != null
        val canCapture: Boolean
            get() = cameraStatus == CameraStatus.ACTIVE && !isCapturing && review == null
    }

    /** The device has no rear-facing camera. */
    data object Unsupported : QuestVisionCameraUiState

    /** Binding the camera use cases failed; the user can retry. */
    data object Error : QuestVisionCameraUiState
}
