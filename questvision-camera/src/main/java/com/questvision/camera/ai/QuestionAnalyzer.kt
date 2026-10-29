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

import android.util.Log
import com.questvision.camera.data.AI_LIMIT_WINDOW_MS
import com.questvision.camera.data.AiRequestOutcome
import com.questvision.camera.data.AiRequestStart
import com.questvision.camera.data.AiStatus
import com.questvision.camera.data.AnalysisMode
import com.questvision.camera.data.QuestionEntity
import com.questvision.camera.data.QuestionRepository
import com.questvision.camera.data.SentImage
import com.questvision.camera.data.TokenUsage
import com.questvision.camera.settings.AiSettings
import com.questvision.camera.settings.AiSettingsStore
import com.questvision.camera.settings.ApiKey
import com.questvision.camera.settings.ApiKeySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val AI_TAG = "QuestVisionAi"

/** Outcome, status and token counts only: never the key, the question, the answer or the image. */
private fun logRequest(
    mode: AnalysisMode,
    model: String,
    outcome: String,
    httpStatus: Int?,
    usage: TokenUsage?,
    serviceTier: String?,
    image: SentImage?,
) {
    Log.i(
        AI_TAG,
        "request mode=$mode model=$model tier=${serviceTier ?: "-"} outcome=$outcome http=${httpStatus ?: "-"} " +
            "tokens in=${usage?.inputTokens ?: "-"} cached=${usage?.cachedTokens ?: "-"} " +
            "cacheWrite=${usage?.cacheWriteTokens ?: "-"} cacheWrite12h=${usage?.cacheWrite12hTokens ?: "-"} " +
            "out=${usage?.outputTokens ?: "-"} reasoning=${usage?.reasoningTokens ?: "-"} " +
            "image=${image?.let { "${it.width}x${it.height}/${it.bytes}B" } ?: "-"}",
    )
}

sealed interface AnalysisResult {
    /** Stored as ANSWERED or, when [answer] needs a human look, NEEDS_REVIEW. */
    data class Answered(
        val answer: AiAnswer,
        val status: AiStatus,
        val usage: TokenUsage?,
    ) : AnalysisResult

    /** A request was made (or started) and failed; the question is FAILED with [error]. */
    data class Failed(
        val error: AiError,
    ) : AnalysisResult

    /** Nothing was sent and nothing changed. */
    data class Rejected(
        val reason: AnalysisRejection,
    ) : AnalysisResult
}

/**
 * Sends one stored question to the AI on the user's request and records the outcome.
 *
 * Guarantees: one request per call and never a retry; the same question is never in flight twice (an
 * in-memory guard plus an atomic claim in SQLite), and no two questions are either (one request in
 * flight in the whole app); at most [AiSettings.requestsPerDay] requests per
 * [AI_LIMIT_WINDOW_MS], counted in the database, so restarting the app resets nothing; the photo, the
 * OCR text and any earlier answer are never deleted by a failure; a cancelled or interrupted request is
 * recorded as such (it may still have been processed and billed). Nothing here runs on its own: there
 * is no queue and no start-up processing.
 */
@Singleton
class QuestionAnalyzer
    @Inject
    constructor(
        private val questions: QuestionRepository,
        private val keys: ApiKeySource,
        private val client: AiClient,
        private val images: QuestionImageEncoder,
        private val settings: AiSettingsStore,
    ) {
        private val guard = Mutex()
        private val inFlight = HashSet<String>()

        suspend fun analyze(
            questionId: String,
            mode: AnalysisMode,
        ): AnalysisResult {
            // One analysis at a time in the whole app (manual or automatic): never two requests in flight.
            val admitted =
                guard.withLock {
                    when {
                        questionId in inFlight -> AnalysisRejection.ALREADY_PROCESSING
                        inFlight.isNotEmpty() -> AnalysisRejection.ANOTHER_IN_PROGRESS
                        else -> null.also { inFlight.add(questionId) }
                    }
                }
            if (admitted != null) return AnalysisResult.Rejected(admitted)
            try {
                val question = questions.question(questionId) ?: return AnalysisResult.Rejected(AnalysisRejection.NOT_RECORDED)
                if (question.aiStatus == AiStatus.PROCESSING) return AnalysisResult.Rejected(AnalysisRejection.ALREADY_PROCESSING)
                val config = settings.load()
                if (questions.aiRequestsInLimitWindow() >= config.requestsPerDay) {
                    return AnalysisResult.Rejected(AnalysisRejection.REQUEST_LIMIT)
                }
                val (input, rejection) = prepare(question, mode, config)
                if (input == null) return AnalysisResult.Rejected(rejection ?: AnalysisRejection.IMAGE_UNAVAILABLE)
                return keys.useKey { key -> send(question.id, key, input, config) }
                    ?: AnalysisResult.Rejected(AnalysisRejection.NO_KEY)
            } finally {
                withContext(NonCancellable) { guard.withLock { inFlight.remove(questionId) } }
            }
        }

        /** The request's input, or why nothing can be sent. The image mode only ever sends the question's crop. */
        private suspend fun prepare(
            question: QuestionEntity,
            mode: AnalysisMode,
            config: AiSettings,
        ): Pair<AiInput?, AnalysisRejection?> =
            when (mode) {
                AnalysisMode.TEXT -> {
                    extractQuestionText(question.ocrText)?.let { AiInput(mode, textPrompt(it.text)) to null }
                        ?: (null to AnalysisRejection.INSUFFICIENT_TEXT)
                }

                AnalysisMode.IMAGE -> {
                    when (val encoding = images.encode(questions.fileOf(question), config.imageMaxDimension)) {
                        is ImageEncoding.Encoded -> {
                            val image = encoding.image
                            AiInput(
                                mode,
                                imagePrompt(question.questionNumber),
                                image.dataUrl,
                                SentImage(image.width, image.height, image.byteCount),
                            ) to
                                null
                        }

                        ImageEncoding.Unreadable -> {
                            null to AnalysisRejection.IMAGE_UNAVAILABLE
                        }

                        ImageEncoding.QuestionNotFound -> {
                            null to AnalysisRejection.QUESTION_REGION_NOT_FOUND
                        }
                    }
                }
            }

        private suspend fun send(
            questionId: String,
            key: ApiKey,
            input: AiInput,
            config: AiSettings,
        ): AnalysisResult {
            val model = config.primaryModel.id
            // The limit is checked again here, atomically with the claim and the log entry.
            val requestId =
                when (val start = questions.beginAiRequest(questionId, model, input.mode, config.requestsPerDay, input.image)) {
                    is AiRequestStart.Started -> start.requestId
                    AiRequestStart.LimitReached -> return AnalysisResult.Rejected(AnalysisRejection.REQUEST_LIMIT)
                    AiRequestStart.AlreadyProcessing -> return AnalysisResult.Rejected(AnalysisRejection.ALREADY_PROCESSING)
                }
            try {
                val options = AiRequestOptions(model = model, maxOutputTokens = config.maxOutputTokens, flex = config.flexProcessing)
                val result = client.send(key, input, options)
                // The answer is here (and possibly billed): storing it must not be cut short by a
                // cancellation that arrives now, or a paid answer would be lost.
                return withContext(NonCancellable) { store(questionId, requestId, model, input, result) }
            } catch (exc: CancellationException) {
                withContext(NonCancellable) {
                    questions.failAi(questionId, requestId, AiError.CANCELLED.name, outcome = AiRequestOutcome.CANCELLED)
                }
                throw exc
            } catch (exc: Exception) {
                // E.g. the database failing while saving: never leave the question stuck in PROCESSING.
                runCatching { questions.failAi(questionId, requestId, AiError.UNEXPECTED.name, outcome = AiRequestOutcome.UNKNOWN) }
                return AnalysisResult.Failed(AiError.UNEXPECTED)
            }
        }

        /** Logs and stores what came back for request [requestId]. */
        private suspend fun store(
            questionId: String,
            requestId: Long,
            model: String,
            input: AiInput,
            result: AiCallResult,
        ): AnalysisResult {
            when (result) {
                is AiCallResult.Failed -> {
                    logRequest(
                        input.mode,
                        model,
                        result.error.name,
                        result.httpStatus,
                        result.usage,
                        result.serviceTier,
                        input.image,
                    )
                }

                is AiCallResult.Completed -> {
                    logRequest(
                        input.mode,
                        model,
                        "COMPLETED",
                        result.httpStatus,
                        result.usage,
                        result.serviceTier,
                        input.image,
                    )
                }
            }
            return when (result) {
                is AiCallResult.Failed -> {
                    questions.failAi(
                        questionId,
                        requestId,
                        result.error.name,
                        // No answer at all: it may have been processed and billed.
                        outcome = if (result.error.outcomeUnknown) AiRequestOutcome.UNKNOWN else AiRequestOutcome.FAILED,
                        httpStatus = result.httpStatus,
                        responseId = result.responseId,
                        usage = result.usage,
                        serviceTier = result.serviceTier,
                    )
                    AnalysisResult.Failed(result.error)
                }

                is AiCallResult.Completed -> {
                    val answer = parseAiAnswer(result.outputText, input.mode)
                    if (answer == null) {
                        questions.failAi(
                            questionId,
                            requestId,
                            AiError.INVALID_RESPONSE.name,
                            httpStatus = result.httpStatus,
                            responseId = result.responseId,
                            usage = result.usage,
                            serviceTier = result.serviceTier,
                        )
                        AnalysisResult.Failed(AiError.INVALID_RESPONSE)
                    } else {
                        val status = if (answer.needsReview) AiStatus.NEEDS_REVIEW else AiStatus.ANSWERED
                        questions.completeAi(
                            questionId,
                            requestId,
                            status,
                            answer.toStoredJson(),
                            model,
                            result.responseId,
                            result.httpStatus,
                            result.usage,
                            result.serviceTier,
                        )
                        AnalysisResult.Answered(answer, status, result.usage)
                    }
                }
            }
        }
    }
