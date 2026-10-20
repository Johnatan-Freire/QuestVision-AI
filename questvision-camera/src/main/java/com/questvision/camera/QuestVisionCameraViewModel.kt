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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class QuestVisionCameraViewModel
    @Inject
    constructor(
        private val captureRepository: CaptureRepository,
    ) : ViewModel(),
        CameraSessionListener {
        private val _uiState =
            MutableStateFlow<QuestVisionCameraUiState>(QuestVisionCameraUiState.Initial)
        val uiState: StateFlow<QuestVisionCameraUiState> = _uiState.asStateFlow()

        // Kept outside the UiState so the "last capture" shortcut survives an Error → retry cycle.
        private var lastCapture: File? = null

        private var reviewJob: Job? = null

        init {
            viewModelScope.launch {
                captureRepository.pruneRecentCaptures()
                lastCapture = captureRepository.latestCapture()
                _uiState.value = QuestVisionCameraUiState.Previewing(lastCapture = lastCapture)
            }
        }

        fun setZoom(ratio: Float) {
            updatePreviewing { it.copy(zoomRatio = ratio.coerceIn(it.minZoom, it.maxZoom)) }
        }

        /** Requests a capture; the screen hands the resulting `pendingCapture` to the controller. */
        fun capture() {
            val state = _uiState.value as? QuestVisionCameraUiState.Previewing ?: return
            // canCapture is false while a capture is pending, so a double tap can't queue a second one.
            if (!state.canCapture) return
            _uiState.value =
                state.copy(
                    pendingCapture = captureRepository.newCaptureFile(),
                    lastCaptureFailed = false,
                )
        }

        fun openLastCapture() {
            lastCapture?.let(::openReview)
        }

        fun closeReview() {
            reviewJob?.cancel()
            updatePreviewing { it.copy(review = null) }
        }

        /** Preserves the capture under review (exempt from retention) and returns to the viewfinder. */
        fun keepLastCapture() {
            val file = lastCapture
            closeReview()
            if (file == null) return
            viewModelScope.launch {
                val kept = captureRepository.preserve(file)
                // Only repoint if nothing newer was captured while the file was being moved.
                if (lastCapture == file) {
                    lastCapture = kept
                    updatePreviewing { it.copy(lastCapture = kept) }
                }
            }
        }

        /** Deletes the capture under review and returns to the viewfinder. */
        fun discardLastCapture() {
            val file = lastCapture
            closeReview()
            if (file == null) return
            viewModelScope.launch {
                captureRepository.delete(file)
                lastCapture = captureRepository.latestCapture()
                updatePreviewing { it.copy(lastCapture = lastCapture) }
            }
        }

        fun dismissCaptureError() {
            updatePreviewing { it.copy(lastCaptureFailed = false) }
        }

        fun retry() {
            _uiState.value = QuestVisionCameraUiState.Previewing(lastCapture = lastCapture)
        }

        override fun onCameraStatusChanged(status: CameraStatus) {
            updatePreviewing { it.copy(cameraStatus = status) }
        }

        override fun onZoomRangeChanged(
            min: Float,
            max: Float,
        ) {
            updatePreviewing {
                it.copy(minZoom = min, maxZoom = max, zoomRatio = it.zoomRatio.coerceIn(min, max))
            }
        }

        override fun onNoBackCamera() {
            _uiState.value = QuestVisionCameraUiState.Unsupported
        }

        override fun onBindFailed() {
            _uiState.value = QuestVisionCameraUiState.Error
        }

        override fun onCaptureSaved(file: File) {
            lastCapture = file
            updatePreviewing { it.copy(pendingCapture = null, lastCapture = file) }
            openReview(file)
            // The new capture is the newest, so retention can never remove it here.
            viewModelScope.launch { captureRepository.pruneRecentCaptures() }
        }

        override fun onCaptureFailed(file: File) {
            updatePreviewing { it.copy(pendingCapture = null, lastCaptureFailed = true) }
            viewModelScope.launch { captureRepository.delete(file) }
        }

        private fun openReview(file: File) {
            reviewJob?.cancel()
            reviewJob =
                viewModelScope.launch {
                    val bitmap = captureRepository.loadForReview(file)
                    if (bitmap != null) {
                        updatePreviewing { it.copy(review = bitmap) }
                    } else {
                        // The file vanished or is unreadable; fall back to whatever capture remains.
                        lastCapture = captureRepository.latestCapture()
                        updatePreviewing { it.copy(lastCapture = lastCapture) }
                    }
                }
        }

        private inline fun updatePreviewing(
            crossinline transform: (QuestVisionCameraUiState.Previewing) -> QuestVisionCameraUiState.Previewing,
        ) {
            _uiState.update { current ->
                if (current is QuestVisionCameraUiState.Previewing) transform(current) else current
            }
        }
    }
