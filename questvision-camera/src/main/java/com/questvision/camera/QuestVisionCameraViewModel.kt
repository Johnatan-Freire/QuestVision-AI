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
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questvision.camera.data.AiStatus
import com.questvision.camera.data.QuestionRepository
import com.questvision.camera.detection.CaptureTicket
import com.questvision.camera.detection.FrameMetrics
import com.questvision.camera.detection.LumaFrame
import com.questvision.camera.detection.MonitoringSession
import com.questvision.camera.detection.MonitoringState
import com.questvision.camera.detection.MonitoringStats
import com.questvision.camera.detection.TextDecision
import com.questvision.camera.recognition.RecognizedText
import com.questvision.camera.recognition.TextFingerprint
import com.questvision.camera.recognition.TextRecognitionEngine
import com.questvision.camera.recognition.TextVerdict
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import javax.inject.Inject

private const val DETECTION_TAG = "QuestVisionDetect"
private const val OCR_TAG = "QuestVisionOcr"

/** A recognition taking longer than this counts as failed. */
private const val OCR_TIMEOUT_MS = 15_000L

@HiltViewModel
class QuestVisionCameraViewModel
    @Inject
    constructor(
        private val captureRepository: CaptureRepository,
        private val textRecognition: TextRecognitionEngine,
        private val questionRepository: QuestionRepository,
    ) : ViewModel(),
        CameraSessionListener {
        private val _uiState =
            MutableStateFlow<QuestVisionCameraUiState>(QuestVisionCameraUiState.Initial)
        val uiState: StateFlow<QuestVisionCameraUiState> = _uiState.asStateFlow()

        // The file shown in review; automatic captures may land while it is open.
        private var reviewFile: File? = null
        private var reviewJob: Job? = null

        // The visual detector proposes candidates; the photo's text decides (verifyText).
        private val monitoringSession =
            MonitoringSession(verifyText = true, onMetrics = { metrics ->
                lastMetrics = metrics
                logMetrics(metrics)
            })

        // Text recognition of the photo being verified; one at a time, like the session's candidate.
        private var verificationJob: Job? = null

        // Metrics of the most recent sample, i.e. of the decision when a capture is requested.
        private var lastMetrics: FrameMetrics? = null

        // When the in-flight automatic capture was requested, to log the decision → photo latency.
        private var autoCaptureRequestedAtNanos = 0L

        // Label of the current monitoring session, recorded in automatic captures' file names.
        private var sessionLabel: String? = null

        // The automatic capture in flight, if any. Kept after the session stops so its late result
        // is still recognized as automatic (no review pops up) and ignored by the session.
        private var autoCapture: Pair<CaptureTicket, File>? = null

        init {
            viewModelScope.launch {
                // Repair what an interruption may have left between files and database (and import
                // captures from before the database), then apply retention.
                val report = questionRepository.reconcile()
                Log.i(DETECTION_TAG, "database reconciled: $report")
                questionRepository.prune()
                _uiState.value = previewingState()
            }
        }

        fun setZoom(ratio: Float) {
            updatePreviewing { it.copy(zoomRatio = ratio.coerceIn(it.minZoom, it.maxZoom)) }
        }

        /** Requests a manual capture; the screen hands the resulting `pendingCapture` to the controller. */
        fun capture() {
            val state = _uiState.value as? QuestVisionCameraUiState.Previewing ?: return
            // canCapture is false while a capture is pending, so a double tap can't queue a second one.
            if (!state.canCapture) return
            _uiState.value =
                state.copy(
                    pendingCapture = captureRepository.newManualCaptureFile(),
                    notice = null,
                )
        }

        fun startMonitoring() =
            updateMonitoring {
                val startsNewSession =
                    monitoringSession.state == MonitoringState.IDLE ||
                        monitoringSession.state == MonitoringState.ERROR
                monitoringSession.start()
                if (startsNewSession) {
                    val startedAt = System.currentTimeMillis()
                    val label = formatSessionLabel(startedAt)
                    sessionLabel = label
                    viewModelScope.launch { questionRepository.startSession(label, startedAt) }
                }
            }

        fun pauseMonitoring() = updateMonitoring { monitoringSession.pause() }

        fun resumeMonitoring() = updateMonitoring { monitoringSession.resume() }

        fun stopMonitoring() =
            updateMonitoring {
                monitoringSession.stop()
                // An unverified photo is never kept as a valid capture.
                verificationJob?.cancel()
                sessionLabel?.let { label ->
                    viewModelScope.launch { questionRepository.endSession(label, System.currentTimeMillis()) }
                }
            }

        /**
         * Opens the capture history over the viewfinder. The camera and any monitoring session keep
         * running underneath, so automatic captures continue (and appear in the list) meanwhile.
         */
        fun openHistory() {
            updatePreviewing { it.copy(history = it.history ?: emptyList()) }
            refreshHistory()
        }

        fun closeHistory() {
            updatePreviewing { it.copy(history = null) }
        }

        /** Opens a stored capture full size. */
        fun openCapture(file: File) = openReview(file)

        /** A small upright thumbnail for the history grid (cached by the repository). */
        suspend fun loadThumbnail(file: File): Bitmap? = captureRepository.loadThumbnail(file)

        fun closeReview() {
            reviewJob?.cancel()
            reviewFile = null
            updatePreviewing { it.copy(review = null, reviewFile = null) }
        }

        /** Preserves the capture under review (exempt from retention) and closes the review. */
        fun keepReviewedCapture() {
            val file = reviewFile
            closeReview()
            if (file == null) return
            viewModelScope.launch {
                questionRepository.preserve(file)
                refreshHistory()
            }
        }

        /** Deletes the capture under review and closes the review. */
        fun discardReviewedCapture() {
            val file = reviewFile
            closeReview()
            if (file == null) return
            viewModelScope.launch {
                questionRepository.delete(file)
                refreshHistory()
            }
        }

        fun dismissNotice() {
            updatePreviewing { it.copy(notice = null) }
        }

        fun retry() {
            _uiState.value = previewingState()
        }

        override fun onCameraStatusChanged(status: CameraStatus) {
            // Frames stop when the camera closes (app in background, screen left): pause the session
            // so it never resumes analyzing on its own.
            if (status == CameraStatus.CLOSED) monitoringSession.pause()
            updatePreviewing { it.copy(cameraStatus = status).withMonitoring() }
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

        override fun onFrameAnalyzed(frame: LumaFrame) {
            val state = _uiState.value as? QuestVisionCameraUiState.Previewing ?: return
            val canCaptureNow = state.pendingCapture == null && state.cameraStatus == CameraStatus.ACTIVE
            val startedNanos = System.nanoTime()
            val ticket = monitoringSession.onFrame(frame, canCaptureNow)
            Log.d(DETECTION_TAG, "detector step ${(System.nanoTime() - startedNanos) / 1_000}µs")
            if (ticket == null) {
                _uiState.value = state.withMonitoring()
                return
            }
            // No user involvement from here: the screen hands pendingCapture to the controller, which
            // writes the JPEG and reports back through onCaptureSaved / onCaptureFailed.
            val label = sessionLabel ?: formatSessionLabel(System.currentTimeMillis()).also { sessionLabel = it }
            val file = captureRepository.newAutomaticCaptureFile(label, ticket.sequence)
            autoCapture = ticket to file
            autoCaptureRequestedAtNanos = System.nanoTime()
            Log.i(DETECTION_TAG, "auto capture #${ticket.sequence} requested -> ${file.name} because ${decisionReason()}")
            _uiState.value = state.copy(pendingCapture = file).withMonitoring()
        }

        override fun onFrameAnalysisUnavailable() {
            monitoringSession.stop()
            updatePreviewing { it.copy(isMonitoringAvailable = false).withMonitoring() }
        }

        override fun onCaptureSaved(file: File) {
            val auto = autoCapture?.takeIf { it.second == file }
            if (auto == null) {
                // Manual capture: show it so the user can keep or discard it. Recorded without OCR and
                // never sent to the AI on its own (only on command, in a later phase).
                updatePreviewing { it.copy(pendingCapture = null) }
                openReview(file)
                val sessionId = sessionLabel.takeIf { monitoringSession.state != MonitoringState.IDLE }
                viewModelScope.launch {
                    record(file, CaptureOrigin.MANUAL, sessionId, sequence = null, ocr = null, AiStatus.NOT_REQUESTED)
                    onCaptureStored()
                }
                return
            }
            autoCapture = null
            viewModelScope.launch {
                // Count only what is really on disk: CameraX reporting success isn't enough.
                val complete = captureRepository.isCompleteCapture(file)
                val current = monitoringSession.onCaptureFinished(auto.first, success = complete)
                Log.i(
                    DETECTION_TAG,
                    "auto capture ${file.name} complete=$complete verifying=$current " +
                        "latency=${(System.nanoTime() - autoCaptureRequestedAtNanos) / 1_000_000}ms",
                )
                updatePreviewing {
                    it
                        .copy(
                            pendingCapture = null,
                            notice = if (!complete) Notice.CAPTURE_FAILED else it.notice,
                        ).withMonitoring()
                }
                when {
                    current -> verify(auto.first, file)

                    // Incomplete, or from a session that has ended: never kept as a valid capture.
                    else -> captureRepository.delete(file)
                }
            }
        }

        /**
         * Reads the text of a saved automatic photo and lets the session decide: a new question is
         * kept and counted; a duplicate or unreadable photo is deleted; a recognition error or
         * timeout deletes it too, and the same screen is retried. Runs off the main thread (decoding
         * on IO, ML Kit on its own threads); the session is VERIFYING meanwhile, so no other
         * candidate is processed.
         */
        private fun verify(
            ticket: CaptureTicket,
            file: File,
        ) {
            verificationJob =
                viewModelScope.launch {
                    val startedNanos = System.nanoTime()
                    var decided = false
                    var recognized: RecognizedText? = null
                    try {
                        val text =
                            try {
                                val raw = withTimeout(OCR_TIMEOUT_MS) { textRecognition.recognize(file) }
                                recognized = raw
                                TextFingerprint.of(raw)
                            } catch (exc: TimeoutCancellationException) {
                                Log.w(OCR_TAG, "OCR timed out for ${file.name}")
                                null
                            } catch (exc: CancellationException) {
                                throw exc
                            } catch (exc: Exception) {
                                Log.w(OCR_TAG, "OCR failed for ${file.name}", exc)
                                null
                            }
                        val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
                        val decision = text?.let { monitoringSession.onTextRecognized(ticket, it) }
                        decided = true
                        when {
                            text == null -> {
                                monitoringSession.onTextRecognitionFailed(ticket)
                                captureRepository.delete(file)
                                notify(Notice.TEXT_READ_FAILED)
                            }

                            decision == null -> {
                                // The session ended meanwhile.
                                captureRepository.delete(file)
                            }

                            else -> {
                                logDecision(file, text, decision, elapsedMs)
                                applyDecision(ticket, file, decision, recognized)
                            }
                        }
                    } finally {
                        // Cancelled (session stopped) before a decision: drop the unverified photo.
                        if (!decided) withContext(NonCancellable) { captureRepository.delete(file) }
                    }
                }
        }

        private suspend fun applyDecision(
            ticket: CaptureTicket,
            file: File,
            decision: TextDecision,
            recognized: RecognizedText?,
        ) {
            if (decision.keep) {
                // A new question: recorded with its OCR text, waiting for the AI (phase 3.4).
                val recorded =
                    record(file, CaptureOrigin.AUTOMATIC, sessionLabel, ticket.sequence, recognized, AiStatus.PENDING)
                if (recorded) notify(Notice.AUTO_CAPTURE_SAVED)
                onCaptureStored()
            } else {
                captureRepository.delete(file)
                notify(
                    if (decision.verdict == TextVerdict.UNREADABLE) {
                        Notice.UNREADABLE_IGNORED
                    } else {
                        Notice.DUPLICATE_IGNORED
                    },
                )
            }
        }

        private fun notify(notice: Notice) {
            updatePreviewing { it.copy(notice = notice).withMonitoring() }
        }

        private fun logDecision(
            file: File,
            text: TextFingerprint,
            decision: TextDecision,
            elapsedMs: Long,
        ) {
            Log.i(OCR_TAG, ocrDecisionLog(file.name, text, decision, elapsedMs, monitoringSession.stats))
        }

        override fun onCaptureFailed(file: File) {
            autoCapture?.takeIf { it.second == file }?.let { auto ->
                autoCapture = null
                monitoringSession.onCaptureFinished(auto.first, success = false)
                Log.i(DETECTION_TAG, "auto capture ${file.name} failed ${monitoringSession.stats}")
            }
            updatePreviewing {
                it.copy(pendingCapture = null, notice = Notice.CAPTURE_FAILED).withMonitoring()
            }
            viewModelScope.launch { captureRepository.delete(file) }
        }

        /**
         * Records a stored photo in the database. A failure is never silent: the user sees a notice,
         * the photo stays on disk, and the next start imports it (see QuestionRepository.reconcile).
         */
        private suspend fun record(
            file: File,
            origin: CaptureOrigin,
            sessionId: String?,
            sequence: Int?,
            ocr: RecognizedText?,
            aiStatus: AiStatus,
        ): Boolean {
            val capturedAt = parseCaptureEntry(file, isPreserved = false).capturedAtMs
            val result = questionRepository.recordCapture(file, origin, sessionId, sequence, capturedAt, ocr, aiStatus)
            result.exceptionOrNull()?.let { error ->
                // The exception type only: no OCR text or paths beyond the file name.
                Log.w(DETECTION_TAG, "could not record ${file.name}: ${error.javaClass.simpleName}")
                notify(Notice.RECORD_FAILED)
            }
            return result.isSuccess
        }

        /** Applies retention after a new capture and refreshes an open history. */
        private fun onCaptureStored() {
            viewModelScope.launch {
                // The new capture is the newest, so retention can never remove it here.
                questionRepository.prune()
                refreshHistory()
            }
        }

        private fun refreshHistory() {
            if ((_uiState.value as? QuestVisionCameraUiState.Previewing)?.history == null) return
            viewModelScope.launch {
                val entries = questionRepository.history()
                updatePreviewing { if (it.history != null) it.copy(history = entries) else it }
            }
        }

        private fun openReview(file: File) {
            reviewJob?.cancel()
            reviewJob =
                viewModelScope.launch {
                    val bitmap = captureRepository.loadForReview(file)
                    if (bitmap != null) {
                        reviewFile = file
                        updatePreviewing { it.copy(review = bitmap, reviewFile = file) }
                    } else {
                        // The file vanished (e.g. pruned) or is unreadable: show what remains.
                        refreshHistory()
                    }
                }
        }

        private fun previewingState(): QuestVisionCameraUiState.Previewing = QuestVisionCameraUiState.Previewing().withMonitoring()

        private fun QuestVisionCameraUiState.Previewing.withMonitoring(): QuestVisionCameraUiState.Previewing =
            copy(
                monitoring = monitoringSession.state,
                autoCaptureCount = monitoringSession.autoCaptureCount,
                monitoringStats = monitoringSession.stats,
                session = sessionLabel,
            )

        private inline fun updateMonitoring(action: () -> Unit) {
            action()
            updatePreviewing { it.withMonitoring() }
        }

        private inline fun updatePreviewing(
            crossinline transform: (QuestVisionCameraUiState.Previewing) -> QuestVisionCameraUiState.Previewing,
        ) {
            _uiState.update { current ->
                if (current is QuestVisionCameraUiState.Previewing) transform(current) else current
            }
        }

        /** Why the detector asked for the capture, from the deciding sample's metrics. */
        private fun decisionReason(): String {
            val m = lastMetrics ?: return "unknown"
            return String.format(
                Locale.US,
                "change=%.4f>%.4f stable=%dms motion=%.4f drift=%.4f<=%.4f outliers=%d nearestCapture=%.4f>%.4f",
                m.change,
                m.changeThreshold,
                m.stableForMs,
                m.motion,
                m.drift,
                m.stillThreshold,
                m.outliers,
                m.nearestCapture,
                m.duplicateThreshold,
            )
        }

        // motion/median/drift/floor/thr: structure metric (decisions). fine: pre-fix metric (diagnosis).
        // luma/sharp: exposure and focus, to tell lighting or autofocus apart from real movement.
        private fun logMetrics(metrics: FrameMetrics) {
            Log.d(
                DETECTION_TAG,
                String.format(
                    Locale.US,
                    "t=%d motion=%.4f median=%.4f fine=%.4f drift=%.4f floor=%.4f thr=%.4f " +
                        "change=%.4f changeThr=%.4f dupThr=%.4f nearest=%.4f stable=%dms outliers=%d " +
                        "luma=%.1f sharp=%.1f phase=%s outcome=%s hold=%s",
                    metrics.timestampMs,
                    metrics.motion,
                    metrics.medianMotion,
                    metrics.fineMotion,
                    metrics.drift,
                    metrics.noiseFloor,
                    metrics.stillThreshold,
                    metrics.change,
                    metrics.changeThreshold,
                    metrics.duplicateThreshold,
                    metrics.nearestCapture,
                    metrics.stableForMs,
                    metrics.outliers,
                    metrics.meanLuma,
                    metrics.sharpness,
                    metrics.phase,
                    metrics.outcome,
                    metrics.hold,
                ),
            )
        }
    }

/**
 * The OCR diagnostic line: verdict, timing, word and line counts, match metrics and the session
 * counters. Never any recognized text, not even a few words, so the questions stay out of Logcat. The
 * 64-bit simhash identifies the word set for telling duplicates apart without revealing it.
 */
internal fun ocrDecisionLog(
    fileName: String,
    text: TextFingerprint,
    decision: TextDecision,
    elapsedMs: Long,
    stats: MonitoringStats,
): String {
    val c = decision.comparison
    return String.format(
        Locale.US,
        "%s %s ocr=%dms tokens=%d lines=%d words=%d novel=%d ratio=%.3f run=%d closest=%d simhash=%016x %s",
        fileName,
        decision.verdict,
        elapsedMs,
        text.tokens.size,
        text.lines.size,
        c.confidentTokens,
        c.novelTokens,
        c.novelRatio,
        c.longestNovelRun,
        c.closestIndex,
        text.simHash,
        stats,
    )
}
