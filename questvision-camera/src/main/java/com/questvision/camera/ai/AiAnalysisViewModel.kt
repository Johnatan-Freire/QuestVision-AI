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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questvision.camera.data.AiRequestEntity
import com.questvision.camera.data.AiStatus
import com.questvision.camera.data.AnalysisMode
import com.questvision.camera.data.QuestionEntity
import com.questvision.camera.data.QuestionRepository
import com.questvision.camera.data.TokenUsage
import com.questvision.camera.settings.AiModel
import com.questvision.camera.settings.AiSettings
import com.questvision.camera.settings.AiSettingsStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class AiAnalysisUiState(
    /** The reviewed photo has a database row; false until loaded or when it has none. */
    val recorded: Boolean = false,
    val loading: Boolean = true,
    val status: AiStatus? = null,
    /** Error code of the last failed attempt. */
    val lastError: AiError? = null,
    /** The stored answer, kept from an earlier attempt even when the last one failed. */
    val answer: AiAnswer? = null,
    val answeredWith: String? = null,
    val attempts: Int = 0,
    /** The stored OCR text holds a complete question, so the text mode can be used. */
    val textAvailable: Boolean = false,
    val mode: AnalysisMode = AnalysisMode.TEXT,
    val model: AiModel = AiSettings().primaryModel,
    /** A request for this question is in flight. */
    val running: Boolean = false,
    /** A request for another question is in flight: one at a time. */
    val busyElsewhere: Boolean = false,
    /** Why the last tap sent nothing. */
    val rejection: AnalysisRejection? = null,
    /** A confirmation the user has to give before anything is sent. */
    val confirming: Confirmation? = null,
    /** Requests started in the last 24 hours (from the request log, so it survives restarts). */
    val requestsInWindow: Int = 0,
    val requestLimit: Int = AiSettings().requestsPerDay,
    val maxOutputTokens: Int = AiSettings().maxOutputTokens,
    val flex: Boolean = AiSettings().flexProcessing,
    /** What one request with the current mode is expected to cost, before sending. */
    val estimate: CostEstimate? = null,
    /** The newest request logged for this question (tokens, mode, outcome). */
    val lastRequest: AiRequestEntity? = null,
    /** [lastRequest]'s cost from its reported tokens (null when they or its tier are unknown). */
    val lastRequestCostUsd: Double? = null,
) {
    val canAnalyze: Boolean get() = recorded && !running && !busyElsewhere && status != AiStatus.PROCESSING
}

enum class Confirmation {
    /** The very first real request (model, mode, estimate), with the advice on spend limits. */
    FIRST_CALL,

    /** Every later manual request: model, mode, estimate and the warning that it uses credits. */
    SEND,

    /** The last request for this question ended without an answer and may have been billed. */
    RESEND_UNKNOWN_OUTCOME,
}

/**
 * The AI analysis of the capture under review. Only ever sends on an explicit tap (and, the very
 * first time, after a confirmation); opening the panel or the app sends nothing.
 */
@HiltViewModel
class AiAnalysisViewModel
    @Inject
    constructor(
        private val analyzer: QuestionAnalyzer,
        private val questions: QuestionRepository,
        private val settingsStore: AiSettingsStore,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(AiAnalysisUiState())
        val uiState: StateFlow<AiAnalysisUiState> = _uiState.asStateFlow()

        private var questionId: String? = null
        private var analysisJob: Job? = null
        private var runningId: String? = null

        private val isRunning get() = analysisJob?.isActive == true

        /** Shows the AI state of the photo [file]. */
        fun select(file: File) {
            viewModelScope.launch {
                val question = questions.question(file)
                questionId = question?.id
                val settings = settingsStore.load()
                _uiState.update {
                    AiAnalysisUiState(
                        loading = false,
                        recorded = question != null,
                        // Text is the default: the cheapest; the image is always an explicit choice.
                        mode = AnalysisMode.TEXT,
                        textAvailable = extractQuestionText(question?.ocrText) != null,
                        model = settings.primaryModel,
                        requestLimit = settings.requestsPerDay,
                        maxOutputTokens = settings.maxOutputTokens,
                        flex = settings.flexProcessing,
                        requestsInWindow = it.requestsInWindow,
                        running = isRunning && runningId == question?.id,
                        busyElsewhere = isRunning && runningId != question?.id,
                    )
                }
                if (question != null) show(question) else refreshRequestCount()
            }
        }

        fun selectMode(mode: AnalysisMode) {
            _uiState.update { it.copy(mode = mode, rejection = null).withEstimate() }
        }

        /**
         * Sends the question, after the confirmations it needs: the one-time confirmation of the first
         * real request, and, when the last request for it ended with an unknown outcome, a confirmation
         * that sending again is a new, separately billed request.
         */
        fun analyze() {
            if (!_uiState.value.canAnalyze) return
            val needed = nextConfirmation(after = null)
            if (needed != null) {
                _uiState.update { it.copy(confirming = needed, rejection = null) }
                return
            }
            start()
        }

        /** The user accepted the confirmation on screen. */
        fun confirm() {
            val current = _uiState.value.confirming ?: return
            if (current == Confirmation.FIRST_CALL) {
                settingsStore.save(settingsStore.load().copy(firstCallConfirmed = true))
            }
            val next = nextConfirmation(after = current)
            _uiState.update { it.copy(confirming = next) }
            if (next == null) start()
        }

        fun dismissConfirmation() {
            _uiState.update { it.copy(confirming = null) }
        }

        /** Every manual request is confirmed (the first one with more advice), then a resend after an unknown outcome. */
        private fun nextConfirmation(after: Confirmation?): Confirmation? {
            val state = _uiState.value
            val resendNeeded = state.status == AiStatus.FAILED && state.lastError?.outcomeUnknown == true
            return when {
                after == null -> if (settingsStore.load().firstCallConfirmed) Confirmation.SEND else Confirmation.FIRST_CALL
                resendNeeded && after != Confirmation.RESEND_UNKNOWN_OUTCOME -> Confirmation.RESEND_UNKNOWN_OUTCOME
                else -> null
            }
        }

        /** Stops waiting for the answer; the attempt is recorded as cancelled (it may still be billed). */
        fun cancel() {
            analysisJob?.cancel()
        }

        private fun start() {
            val id = questionId ?: return
            if (isRunning) return
            val mode = _uiState.value.mode
            runningId = id
            _uiState.update { it.copy(running = true, rejection = null, confirming = null, status = AiStatus.PROCESSING) }
            analysisJob =
                viewModelScope.launch {
                    try {
                        val result = analyzer.analyze(id, mode)
                        if (questionId == id) _uiState.update { it.copy(rejection = (result as? AnalysisResult.Rejected)?.reason) }
                    } finally {
                        _uiState.update { if (questionId == id) it.copy(running = false) else it.copy(busyElsewhere = false) }
                        // Reload what was stored (also after a cancellation).
                        viewModelScope.launch {
                            questions.question(id)?.let { show(it) }
                            refreshRequestCount()
                        }
                    }
                }
        }

        private suspend fun show(question: QuestionEntity) {
            if (question.id != questionId) return
            val lastRequest = questions.aiRequestsFor(question.id).firstOrNull()
            val requestsInWindow = questions.aiRequestsInLimitWindow()
            _uiState.update {
                it
                    .copy(
                        status = question.aiStatus,
                        lastError = AiError.fromCode(question.lastError),
                        answer = storedAiAnswer(question.aiResponse),
                        answeredWith = question.aiModel,
                        attempts = question.attempts,
                        lastRequest = lastRequest,
                        lastRequestCostUsd =
                            lastRequest?.let { request ->
                                observedCostUsd(AiModel.fromId(request.model), request.usage(), request.serviceTier)
                            },
                        requestsInWindow = requestsInWindow,
                    ).withEstimate(question.ocrText)
            }
        }

        private suspend fun refreshRequestCount() {
            val count = questions.aiRequestsInLimitWindow()
            _uiState.update { it.copy(requestsInWindow = count) }
        }

        private var questionChars = 0

        private fun AiAnalysisUiState.withEstimate(ocrText: String? = null): AiAnalysisUiState {
            if (ocrText != null) questionChars = extractQuestionText(ocrText)?.text?.length ?: 0
            return copy(estimate = estimateCost(model, mode, questionChars, maxOutputTokens, flex))
        }
    }

private fun AiRequestEntity.usage() = TokenUsage(inputTokens, cachedTokens, outputTokens, reasoningTokens, cacheWriteTokens)

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AiModule {
    @Binds
    abstract fun bindHttpTransport(transport: UrlConnectionTransport): HttpTransport

    @Binds
    abstract fun bindAiClient(client: OpenAiClient): AiClient

    @Binds
    abstract fun bindImageEncoder(encoder: JpegQuestionImageEncoder): QuestionImageEncoder

    @Binds
    abstract fun bindAutoSendPolicy(policy: LockedAutoSendPolicy): AutoSendPolicy
}
