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

/**
 * Why a request to the AI failed. Stored by name in `questions.last_error` and `ai_requests.error_code`,
 * so names must not change. None of them is retried automatically: a request that reached OpenAI may
 * have been billed even when it failed.
 */
enum class AiError {
    /** 401: the key is wrong, revoked or from another project. */
    AUTH,

    /** 403: unsupported region or missing permission. */
    FORBIDDEN,

    /** 429 because requests came too fast: try again later. */
    RATE_LIMIT,

    /** 429 because credits, the project/organization spend limit or the usage limit ran out. */
    QUOTA,

    /** 400/404/413/422: the request was rejected (model name, size, parameters). */
    BAD_REQUEST,

    /** 5xx, or the response reported `failed`. */
    SERVER,

    /** No connection, DNS failure, connection reset. */
    NETWORK,

    /** No complete answer within the time limit. */
    TIMEOUT,

    /** Cancelled by the user. */
    CANCELLED,

    /** The response was not the expected JSON. */
    INVALID_RESPONSE,

    /** The model hit `max_output_tokens` (or was stopped) before finishing the answer. */
    INCOMPLETE_OUTPUT,

    /** The model declined to answer. */
    REFUSAL,

    /** The app stopped while the request was in flight (see QuestionRepository.reconcile). */
    INTERRUPTED,

    /** Anything else (e.g. a database error while saving). */
    UNEXPECTED,
    ;

    /**
     * No answer was seen, but the request may still have reached OpenAI, been processed and billed: the
     * missing response proves nothing. Sending again needs the user's explicit confirmation.
     */
    val outcomeUnknown: Boolean get() = this == NETWORK || this == TIMEOUT || this == CANCELLED || this == INTERRUPTED || this == UNEXPECTED

    companion object {
        fun fromCode(code: String?): AiError? = entries.firstOrNull { it.name == code }
    }
}

/** Why an analysis was not started at all: no request was sent and nothing was changed. */
enum class AnalysisRejection {
    /** No usable OpenAI key is stored (missing or no longer decryptable). */
    NO_KEY,

    /** The photo has no database row (e.g. a failed record not yet recovered). */
    NOT_RECORDED,

    /** This question is already being analyzed. */
    ALREADY_PROCESSING,

    /** Another question is being analyzed: one request at a time in the whole app. */
    ANOTHER_IN_PROGRESS,

    /** The local limit of requests per 24 hours was reached (counted in the database, survives restarts). */
    REQUEST_LIMIT,

    /** The stored OCR text has no complete question (statement and alternatives): use the image mode. */
    INSUFFICIENT_TEXT,

    /** The photo could not be read. */
    IMAGE_UNAVAILABLE,

    /**
     * Automatic image mode only: the question's region could not be found safely in the photo, so
     * nothing was sent (the whole photo is never sent automatically). The question stays PENDING.
     */
    QUESTION_REGION_NOT_FOUND,
}
