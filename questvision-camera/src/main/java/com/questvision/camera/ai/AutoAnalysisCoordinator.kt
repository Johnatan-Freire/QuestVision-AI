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
package com.questvision.camera.ai

import com.questvision.camera.data.AiStatus
import com.questvision.camera.data.AnalysisMode
import com.questvision.camera.data.QuestionEntity
import com.questvision.camera.data.QuestionRepository
import com.questvision.camera.data.TokenUsage
import com.questvision.camera.settings.AiModel
import com.questvision.camera.settings.AiSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Whether automatic analysis may make real, billed requests to OpenAI. */
interface AutoSendPolicy {
    val realRequestsAllowed: Boolean
}

/**
 * Phase 3.4: the automatic flow is built and tested end to end with simulated clients only. Real
 * automatic requests stay off until the user explicitly authorizes them; until then a validated
 * question waits for the manual "Analisar", with its confirmations.
 */
@Singleton
class LockedAutoSendPolicy
    @Inject
    constructor() : AutoSendPolicy {
        override val realRequestsAllowed: Boolean = false
    }

enum class AutoPhase {
    /** No monitoring session. */
    IDLE,

    /** Session running; nothing to send right now. */
    WAITING,

    /** A request is in flight (one at a time). */
    ANALYZING,

    /**
     * Nothing new is sent; [AutoAnalysisState.pauseReason] says why. The camera keeps capturing:
     * new questions are stored as PENDING for later. A user pause ends with "resume"; any other reason
     * (limits, errors) only with a new session, so sending never restarts on its own (not even when
     * the 24-hour window frees up).
     */
    PAUSED,
}

enum class AutoPauseReason {
    /** The user paused the session (or the camera closed). Lifted by resuming. */
    USER,

    /** [com.questvision.camera.settings.AiSettings.analysesPerSession] reached. */
    SESSION_LIMIT,

    /** [com.questvision.camera.settings.AiSettings.requestsPerDay] reached in the last 24 h. */
    DAILY_LIMIT,

    /** No usable OpenAI key. */
    NO_KEY,

    /** No answer arrived (network, timeout): it may have been processed and billed, so nothing is retried. */
    CONNECTION,

    /** OpenAI refused the requests (key, region, credits, rate, parameters): the next ones would fail too. */
    API_ERROR,
}

/** What the automatic flow did last, for the session panel. Question numbers as read by the OCR. */
sealed interface AutoEvent {
    val questionNumber: Int?

    /** Complete and new: queued for the AI. */
    data class Queued(
        override val questionNumber: Int?,
    ) : AutoEvent

    /** Not complete or legible enough: kept for manual use, waiting for a better capture. */
    data class Incomplete(
        override val questionNumber: Int?,
        val issue: AssessmentIssue,
    ) : AutoEvent

    /** The AI already saw this question (another session or capture): not sent again. */
    data class AlreadyAnalyzed(
        override val questionNumber: Int?,
    ) : AutoEvent

    /** Validated, but real automatic requests are not authorized: use "Analisar". */
    data class AwaitingAuthorization(
        override val questionNumber: Int?,
    ) : AutoEvent

    data class Answered(
        val questionId: String,
        override val questionNumber: Int?,
        val answer: List<String>,
        /** The chosen alternative's text as read by the OCR, when there is exactly one. */
        val alternativeText: String?,
        val needsReview: Boolean,
    ) : AutoEvent

    data class Failed(
        val questionId: String,
        override val questionNumber: Int?,
        val error: AiError,
    ) : AutoEvent

    /** Not sent: it changed meanwhile (already analyzed, gone) or its text/photo was unusable. */
    data class Skipped(
        override val questionNumber: Int?,
    ) : AutoEvent
}

data class AutoAnalysisState(
    val phase: AutoPhase = AutoPhase.IDLE,
    val pauseReason: AutoPauseReason? = null,
    /** Validated questions waiting to be sent, in capture order. */
    val queued: Int = 0,
    /** Requests made in this session (answered or failed: both may be billed). */
    val requests: Int = 0,
    val answered: Int = 0,
    val sessionLimit: Int = 0,
    /** Observed cost of this session's requests (local estimate from the reported tokens; not the bill). */
    val observedCostUsd: Double = 0.0,
    /** Requests whose cost could not be computed (no usage reported, unknown tier). */
    val requestsWithoutCost: Int = 0,
    /** Validated questions not sent because real automatic requests are off. */
    val awaitingAuthorization: Int = 0,
    val realRequestsAllowed: Boolean = false,
    val lastEvent: AutoEvent? = null,
    val lastAnswer: AutoEvent.Answered? = null,
)

/**
 * Sends the questions of a monitoring session to the AI on its own, one at a time, after the local
 * validation ([assessQuestion]) and within every limit. Starting the session is the user's consent;
 * pausing or ending it stops new requests (one in flight finishes and is stored).
 *
 * Guarantees, on top of [QuestionAnalyzer]'s: one request in flight at most; a question is queued
 * once per session and only sent while still PENDING, so nothing is sent twice and a failed or
 * interrupted attempt is never retried automatically; sending stops for the rest of the session at
 * the per-session or 24-hour limit, on a missing key, on a lost connection (the outcome is unknown)
 * and on errors the next requests would repeat, while capturing goes on (capture-only mode); nothing
 * survives a restart (the queue is in memory, and earlier PENDING questions are never sent on their
 * own). Driven from one thread (the main thread).
 */
class AutoAnalysisCoordinator
    @Inject
    constructor(
        private val analyzer: QuestionAnalyzer,
        private val questions: QuestionRepository,
        private val settings: AiSettingsStore,
        private val policy: AutoSendPolicy,
    ) {
        private class Item(
            val questionId: String,
            val mode: AnalysisMode,
            val questionNumber: Int?,
        )

        private val _state = MutableStateFlow(AutoAnalysisState(realRequestsAllowed = policy.realRequestsAllowed))
        val state: StateFlow<AutoAnalysisState> = _state.asStateFlow()

        private var scope: CoroutineScope? = null
        private val queue = ArrayDeque<Item>()
        private val seen = HashSet<String>()
        private var worker: Job? = null
        private var active = false
        private var paused = false

        // Why sending stopped for the rest of the session (limits, errors); null while it may send.
        private var halted: AutoPauseReason? = null

        // Another (manual) analysis held the single request slot; retried on the next kick.
        private var busy = false

        /** Where requests run: the owner's scope, so leaving the screen cancels (and records) them. */
        fun attach(scope: CoroutineScope) {
            this.scope = scope
        }

        fun startSession() {
            queue.clear()
            seen.clear()
            active = true
            paused = false
            halted = null
            _state.value =
                AutoAnalysisState(
                    phase = if (worker?.isActive == true) AutoPhase.ANALYZING else AutoPhase.WAITING,
                    sessionLimit = settings.load().analysesPerSession,
                    realRequestsAllowed = policy.realRequestsAllowed,
                )
        }

        fun pause() {
            if (!active || paused) return
            paused = true
            _state.update { it.copy(phase = phaseNow(), pauseReason = halted ?: AutoPauseReason.USER) }
        }

        /** Lifts a user pause only; a limit or an error keeps sending stopped until a new session. */
        fun resume() {
            if (!active || !paused) return
            paused = false
            _state.update { it.copy(phase = phaseNow(), pauseReason = halted) }
            kick()
        }

        /** Ends the session: queued questions stay PENDING (manual use); one in flight completes. */
        fun endSession() {
            active = false
            paused = false
            halted = null
            queue.clear()
            _state.update { it.copy(phase = phaseNow(), pauseReason = null, queued = 0) }
        }

        /** A validated new question of the session; sent when its turn comes, if every check passes. */
        fun submit(
            question: QuestionEntity,
            assessment: QuestionAssessment,
        ) {
            if (!active || !assessment.usable || !seen.add(question.id)) return
            if (!settings.load().processAutomaticCaptures) return
            queue.addLast(Item(question.id, assessment.mode, question.questionNumber ?: assessment.questionNumber))
            _state.update { it.copy(queued = queue.size, lastEvent = AutoEvent.Queued(assessment.questionNumber)) }
            kick()
        }

        fun reportIncomplete(assessment: QuestionAssessment) {
            if (!active) return
            val issue = assessment.issues.minByOrNull { it.ordinal } ?: return
            _state.update { it.copy(lastEvent = AutoEvent.Incomplete(assessment.questionNumber, issue)) }
        }

        fun reportAlreadyAnalyzed(questionNumber: Int?) {
            if (!active) return
            _state.update { it.copy(lastEvent = AutoEvent.AlreadyAnalyzed(questionNumber)) }
        }

        /**
         * A question the AI already saw (in any session) with the same text as [ocrText], up to OCR
         * noise; null if none. Compared on the question block only, not the rest of the screen.
         */
        suspend fun findSentToAi(ocrText: String?): QuestionEntity? {
            if (questionBlock(ocrText) == null) return null
            return questions.questionsSentToAi().lastOrNull { sameQuestion(ocrText, it.ocrText) }
        }

        private fun phaseNow(): AutoPhase =
            when {
                worker?.isActive == true -> AutoPhase.ANALYZING
                !active -> AutoPhase.IDLE
                paused || halted != null -> AutoPhase.PAUSED
                else -> AutoPhase.WAITING
            }

        private val canSend get() = active && !paused && halted == null

        private fun kick() {
            val scope = scope ?: return
            if (worker?.isActive == true || !canSend || queue.isEmpty()) return
            worker = scope.launch { drain() }
        }

        private suspend fun drain() {
            busy = false
            while (canSend && !busy) {
                val item = queue.firstOrNull() ?: break
                if (!policy.realRequestsAllowed) {
                    queue.removeFirst()
                    _state.update {
                        it.copy(
                            queued = queue.size,
                            awaitingAuthorization = it.awaitingAuthorization + 1,
                            lastEvent = AutoEvent.AwaitingAuthorization(item.questionNumber),
                        )
                    }
                    continue
                }
                val config = settings.load()
                if (_state.value.requests >= config.analysesPerSession) {
                    selfPause(AutoPauseReason.SESSION_LIMIT)
                    break
                }
                if (questions.aiRequestsInLimitWindow() >= config.requestsPerDay) {
                    selfPause(AutoPauseReason.DAILY_LIMIT)
                    break
                }
                queue.removeFirst()
                val row = questions.question(item.questionId)
                if (row?.aiStatus != AiStatus.PENDING) {
                    // Answered, failed or in flight meanwhile (e.g. by "Analisar"), or gone: never twice.
                    _state.update { it.copy(queued = queue.size, lastEvent = AutoEvent.Skipped(item.questionNumber)) }
                    continue
                }
                _state.update { it.copy(phase = AutoPhase.ANALYZING, queued = queue.size) }
                val result = analyzer.analyze(item.questionId, item.mode)
                handle(item, row, result)
            }
            worker = null
            _state.update { it.copy(phase = phaseNow(), queued = queue.size) }
        }

        private suspend fun handle(
            item: Item,
            row: QuestionEntity,
            result: AnalysisResult,
        ) {
            when (result) {
                is AnalysisResult.Answered -> {
                    val answered =
                        AutoEvent.Answered(
                            questionId = item.questionId,
                            questionNumber = result.answer.questionNumber ?: item.questionNumber,
                            answer = result.answer.answer,
                            alternativeText =
                                result.answer.answer
                                    .singleOrNull()
                                    ?.let { alternativeText(row.ocrText, it) },
                            needsReview = result.answer.needsReview,
                        )
                    countRequest(item.questionId) { it.copy(answered = it.answered + 1, lastEvent = answered, lastAnswer = answered) }
                }

                is AnalysisResult.Failed -> {
                    countRequest(item.questionId) {
                        it.copy(lastEvent = AutoEvent.Failed(item.questionId, item.questionNumber, result.error))
                    }
                    pauseReasonFor(result.error)?.let(::selfPause)
                }

                is AnalysisResult.Rejected -> {
                    _state.update { it.copy(lastEvent = AutoEvent.Skipped(item.questionNumber)) }
                    when (result.reason) {
                        AnalysisRejection.NO_KEY -> {
                            queue.addFirst(item)
                            selfPause(AutoPauseReason.NO_KEY)
                        }

                        AnalysisRejection.REQUEST_LIMIT -> {
                            queue.addFirst(item)
                            selfPause(AutoPauseReason.DAILY_LIMIT)
                        }

                        AnalysisRejection.ANOTHER_IN_PROGRESS -> {
                            // A manual analysis is running: keep the question first in line; the next
                            // capture (or resume) sends it. Never two requests at once.
                            queue.addFirst(item)
                            busy = true
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            }
        }

        /** Counts a request that was made and adds its observed cost from the stored usage. */
        private suspend fun countRequest(
            questionId: String,
            event: (AutoAnalysisState) -> AutoAnalysisState,
        ) {
            val request = questions.aiRequestsFor(questionId).firstOrNull()
            val cost =
                request?.let {
                    observedCostUsd(
                        AiModel.fromId(it.model),
                        TokenUsage(it.inputTokens, it.cachedTokens, it.outputTokens, it.reasoningTokens, it.cacheWriteTokens),
                        it.serviceTier,
                    )
                }
            _state.update {
                event(
                    it.copy(
                        requests = it.requests + 1,
                        observedCostUsd = it.observedCostUsd + (cost ?: 0.0),
                        requestsWithoutCost = it.requestsWithoutCost + if (cost == null) 1 else 0,
                    ),
                )
            }
        }

        /** Stops sending for the rest of the session; capturing goes on. */
        private fun selfPause(reason: AutoPauseReason) {
            if (!active) return
            halted = reason
            _state.update { it.copy(phase = AutoPhase.PAUSED, pauseReason = reason, queued = queue.size) }
        }
    }

/** Errors after which the session stops sending; null when the next question may still go. */
internal fun pauseReasonFor(error: AiError): AutoPauseReason? =
    when {
        error.outcomeUnknown -> AutoPauseReason.CONNECTION

        error == AiError.AUTH || error == AiError.FORBIDDEN || error == AiError.QUOTA ||
            error == AiError.RATE_LIMIT || error == AiError.BAD_REQUEST -> AutoPauseReason.API_ERROR

        // SERVER, INCOMPLETE_OUTPUT, REFUSAL, INVALID_RESPONSE: this question failed; the next may not.
        else -> null
    }
