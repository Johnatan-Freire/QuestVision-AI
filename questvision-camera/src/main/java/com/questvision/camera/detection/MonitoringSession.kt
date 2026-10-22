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

import com.questvision.camera.recognition.QuestionTextMatcher
import com.questvision.camera.recognition.TextComparison
import com.questvision.camera.recognition.TextFingerprint
import com.questvision.camera.recognition.TextVerdict

/**
 * IDLE → MONITORING → CHANGE_DETECTED → STABILIZING → CAPTURING → VERIFYING → MONITORING, plus PAUSED
 * (manual or because the camera closed) and ERROR (repeated failures; restart with
 * [MonitoringSession.start]). VERIFYING (reading the photo's text) only exists with text
 * verification on.
 */
enum class MonitoringState {
    IDLE,
    MONITORING,
    CHANGE_DETECTED,
    STABILIZING,
    CAPTURING,
    VERIFYING,
    PAUSED,
    ERROR,
    ;

    /** Whether the session is running (frames are only processed while it isn't capturing/verifying). */
    val isAnalyzing: Boolean
        get() =
            this == MONITORING || this == CHANGE_DETECTED || this == STABILIZING ||
                this == CAPTURING || this == VERIFYING
}

/** Identifies one automatic capture; results carrying a ticket from an ended session are ignored. */
data class CaptureTicket(
    val sessionId: Int,
    val sequence: Int,
)

/** Audit counters for one monitoring session, to check what the automation actually did. */
data class MonitoringStats(
    /** Times the screen started to differ from the accepted content (incl. the first screen). */
    val changesDetected: Int = 0,
    /** Automatic captures started (photos requested). */
    val capturesRequested: Int = 0,
    /** New questions confirmed (photo on disk and, with verification, new text); the `Auto: n` count. */
    val capturesSaved: Int = 0,
    /** Photos that failed or left no usable file; never counted as saved. */
    val capturesFailed: Int = 0,
    /** Settled content visually matching an earlier capture, skipped without a photo. */
    val duplicatesIgnored: Int = 0,
    /** Photos whose text matched an earlier capture of the session; deleted, not counted. */
    val textDuplicatesIgnored: Int = 0,
    /** Photos with too little readable text; deleted, not counted. */
    val unreadableIgnored: Int = 0,
    /** Photos whose text could not be read (OCR error or timeout); deleted, retried, not counted. */
    val recognitionFailures: Int = 0,
    /** Changes that settled back onto the accepted content (bumps, passing hands). */
    val changesReverted: Int = 0,
    /** Results of captures from an ended session, ignored. */
    val staleResultsIgnored: Int = 0,
)

/** What to do with a verified photo. */
data class TextDecision(
    val verdict: TextVerdict,
    val comparison: TextComparison,
) {
    /** Keep the photo and count it; otherwise delete it. */
    val keep: Boolean get() = verdict == TextVerdict.NEW_QUESTION
}

/**
 * Coordinates a monitoring session: feeds frames to the [ScreenChangeDetector], issues at most one
 * automatic capture at a time, and applies capture and text-verification results. Pure and
 * single-threaded (driven from the main thread by the ViewModel), so it can be unit-tested without
 * a camera or OCR.
 *
 * With [verifyText], a saved photo only counts after its text is read and found new: the visual
 * detector proposes, the text decides. Until then the session is VERIFYING and processes no frames,
 * so only one candidate is handled at a time.
 */
class MonitoringSession(
    private val config: DetectionConfig = DetectionConfig(),
    private val verifyText: Boolean = false,
    private val textMatcher: QuestionTextMatcher = QuestionTextMatcher(),
    private val onMetrics: (FrameMetrics) -> Unit = {},
) {
    var state: MonitoringState = MonitoringState.IDLE
        private set

    var stats: MonitoringStats = MonitoringStats()
        private set

    /** New questions of this session confirmed on disk. */
    val autoCaptureCount: Int get() = stats.capturesSaved

    private var sessionId = 0
    private var sequence = 0
    private var detector = ScreenChangeDetector(config)
    private var inFlight: Pair<CaptureTicket, LumaFrame>? = null
    private var awaitingText = false
    private var consecutiveFailures = 0

    // Text of every question accepted in this session.
    private val acceptedTexts = ArrayList<TextFingerprint>()

    fun start() {
        if (state != MonitoringState.IDLE && state != MonitoringState.ERROR) return
        sessionId++
        // Numbering restarts per session: it is the capture number shown in the history.
        sequence = 0
        detector = ScreenChangeDetector(config)
        inFlight = null
        awaitingText = false
        acceptedTexts.clear()
        stats = MonitoringStats()
        consecutiveFailures = 0
        state = MonitoringState.MONITORING
    }

    fun pause() {
        if (state.isAnalyzing) state = MonitoringState.PAUSED
    }

    fun resume() {
        if (state != MonitoringState.PAUSED) return
        detector.resetMotion()
        state =
            when {
                inFlight == null -> MonitoringState.MONITORING
                awaitingText -> MonitoringState.VERIFYING
                else -> MonitoringState.CAPTURING
            }
    }

    /** Ends the session. A capture or verification in flight is orphaned: its result is ignored. */
    fun stop() {
        if (state == MonitoringState.IDLE) return
        sessionId++
        inFlight = null
        awaitingText = false
        state = MonitoringState.IDLE
    }

    /**
     * Processes one sampled frame. Returns a ticket when an automatic capture should start now;
     * [canCaptureNow] is false while another (e.g. manual) capture is in flight, in which case the
     * candidate is offered again on the next frame.
     */
    fun onFrame(
        frame: LumaFrame,
        canCaptureNow: Boolean,
    ): CaptureTicket? {
        if (!state.isAnalyzing || inFlight != null) return null
        val wasWatching = detector.phase == DetectorPhase.WATCHING
        val metrics = detector.process(frame)
        onMetrics(metrics)
        // A change that settles within the same sample (e.g. the first screen) still counts.
        if (wasWatching && (metrics.phase != DetectorPhase.WATCHING || metrics.outcome != DetectionOutcome.NONE)) {
            stats = stats.copy(changesDetected = stats.changesDetected + 1)
        }
        when (metrics.outcome) {
            DetectionOutcome.DUPLICATE -> stats = stats.copy(duplicatesIgnored = stats.duplicatesIgnored + 1)
            DetectionOutcome.REVERTED -> stats = stats.copy(changesReverted = stats.changesReverted + 1)
            else -> Unit
        }
        if (metrics.outcome == DetectionOutcome.CANDIDATE) {
            if (!canCaptureNow) {
                state = MonitoringState.STABILIZING
                return null
            }
            val ticket = CaptureTicket(sessionId, ++sequence)
            inFlight = ticket to frame
            stats = stats.copy(capturesRequested = stats.capturesRequested + 1)
            state = MonitoringState.CAPTURING
            return ticket
        }
        state =
            when (detector.phase) {
                DetectorPhase.WATCHING -> MonitoringState.MONITORING
                DetectorPhase.CHANGED -> MonitoringState.CHANGE_DETECTED
                DetectorPhase.STABILIZING -> MonitoringState.STABILIZING
            }
        return null
    }

    /**
     * Applies the photo result of the capture identified by [ticket]. Returns true when the photo
     * belongs to the current session: without text verification it is counted now; with it, the
     * session moves to VERIFYING and waits for [onTextRecognized] / [onTextRecognitionFailed]. Stale
     * tickets are ignored.
     */
    fun onCaptureFinished(
        ticket: CaptureTicket,
        success: Boolean,
    ): Boolean {
        val pending = inFlight
        if (pending == null || pending.first != ticket || awaitingText) {
            stats = stats.copy(staleResultsIgnored = stats.staleResultsIgnored + 1)
            return false
        }
        if (!success) {
            inFlight = null
            detector.discardWindow()
            stats = stats.copy(capturesFailed = stats.capturesFailed + 1)
            fail()
            return false
        }
        if (verifyText) {
            awaitingText = true
            if (state == MonitoringState.CAPTURING) state = MonitoringState.VERIFYING
            return true
        }
        inFlight = null
        detector.accept(pending.second)
        stats = stats.copy(capturesSaved = stats.capturesSaved + 1)
        consecutiveFailures = 0
        settle()
        return true
    }

    /**
     * Decides a verified photo from its [text]. Returns null for a stale ticket (session ended or
     * restarted meanwhile). New text is counted; duplicate or unreadable text is not (the caller
     * deletes the photo), but the scene becomes the visual reference so it isn't re-proposed.
     */
    fun onTextRecognized(
        ticket: CaptureTicket,
        text: TextFingerprint,
    ): TextDecision? {
        val pending = takeVerification(ticket) ?: return null
        val comparison = textMatcher.compare(text, acceptedTexts)
        detector.accept(pending.second)
        consecutiveFailures = 0
        stats =
            when (comparison.verdict) {
                TextVerdict.NEW_QUESTION -> {
                    acceptedTexts += text
                    stats.copy(capturesSaved = stats.capturesSaved + 1)
                }

                TextVerdict.DUPLICATE -> {
                    stats.copy(textDuplicatesIgnored = stats.textDuplicatesIgnored + 1)
                }

                TextVerdict.UNREADABLE -> {
                    stats.copy(unreadableIgnored = stats.unreadableIgnored + 1)
                }
            }
        settle()
        return TextDecision(comparison.verdict, comparison)
    }

    /**
     * The photo's text could not be read. It is never treated as a valid capture: not counted, not
     * accepted as reference, so the same screen is retried after a fresh stabilization. Returns
     * false for a stale ticket.
     */
    fun onTextRecognitionFailed(ticket: CaptureTicket): Boolean {
        takeVerification(ticket) ?: return false
        detector.discardWindow()
        stats = stats.copy(recognitionFailures = stats.recognitionFailures + 1)
        fail()
        return true
    }

    private fun takeVerification(ticket: CaptureTicket): Pair<CaptureTicket, LumaFrame>? {
        val pending = inFlight
        if (pending == null || pending.first != ticket || !awaitingText) {
            stats = stats.copy(staleResultsIgnored = stats.staleResultsIgnored + 1)
            return null
        }
        inFlight = null
        awaitingText = false
        return pending
    }

    private fun fail() {
        consecutiveFailures++
        if (consecutiveFailures >= config.maxConsecutiveCaptureFailures) {
            state = MonitoringState.ERROR
        } else {
            settle()
        }
    }

    /** Back to monitoring after a capture is handled; a pause during it is kept. */
    private fun settle() {
        if (state == MonitoringState.CAPTURING || state == MonitoringState.VERIFYING) {
            state = MonitoringState.MONITORING
        }
    }
}
