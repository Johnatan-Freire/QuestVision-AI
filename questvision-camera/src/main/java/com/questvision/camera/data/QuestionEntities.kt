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
package com.questvision.camera.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.questvision.camera.CaptureOrigin

/**
 * AI processing of a question: `PENDING → PROCESSING → ANSWERED` (or `NEEDS_REVIEW`), and
 * `PROCESSING → FAILED` on an error. Any state but PROCESSING can be analyzed again on request.
 */
enum class AiStatus {
    /** Not to be sent automatically: manual captures (on command only) and captures imported from before. */
    NOT_REQUESTED,

    /** New automatic question waiting for the AI (sent on request; automatically from phase 3.4 on). */
    PENDING,

    /** A request is in flight. Left over after a crash, it becomes FAILED (`INTERRUPTED`) on the next start. */
    PROCESSING,
    ANSWERED,

    /** Answered, but the answer needs a human look (low confidence, illegible, incomplete). */
    NEEDS_REVIEW,

    /** The last attempt failed; `last_error` says why. Nothing is retried without the user. */
    FAILED,
    ;

    /**
     * Its photo and row survive the automatic cleanup once the AI is involved: waiting, in flight,
     * failed or answered. An answer may have been paid for, and the cleanup also deletes the row.
     */
    val keepsPhoto: Boolean get() = this != NOT_REQUESTED
}

/** What a question is sent to the AI as. */
enum class AnalysisMode {
    /** The question block of the stored OCR text: cheapest. */
    TEXT,

    /** The photo itself, downscaled: for formulas, figures or text the OCR got wrong. */
    IMAGE,
}

/** How a request to the AI ended. */
enum class AiRequestOutcome {
    /** Sent and not finished yet (or the app died meanwhile, until the next start marks it INTERRUPTED). */
    SENT,
    SUCCEEDED,
    FAILED,

    /** Cancelled by the user after sending: OpenAI may still have processed and billed it. */
    CANCELLED,

    /** The app stopped while it was in flight: it may have been processed and billed. */
    INTERRUPTED,

    /**
     * No answer arrived (connection lost, timeout): OpenAI may still have processed and billed it, so
     * it is neither a success nor a known failure. Never resent automatically.
     */
    UNKNOWN,
}

/** Delivery of the answer to Telegram (a later phase). */
enum class TelegramStatus {
    NOT_REQUESTED,
    PENDING,
    SENT,
    FAILED,
}

/** A monitoring session; its id is the label recorded in automatic captures' file names. */
@Entity(tableName = "capture_sessions")
data class CaptureSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
)

/**
 * One stored photo of a question and everything known about it. The JPEG itself stays in private
 * storage; [imagePath] is relative to the captures folder (`capture_….jpg` or `kept/capture_….jpg`),
 * so the database never points outside it and survives a change of the app's data path.
 */
@Entity(
    tableName = "questions",
    foreignKeys = [
        ForeignKey(
            entity = CaptureSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["image_path"], unique = true),
        Index(value = ["session_id"]),
        Index(value = ["captured_at"]),
        Index(value = ["dedup_key"]),
        Index(value = ["ai_status"]),
    ],
)
data class QuestionEntity(
    @PrimaryKey val id: String,
    /** Monitoring session, or null for a manual capture taken outside one. */
    @ColumnInfo(name = "session_id") val sessionId: String?,
    /** Automatic-capture number within the session (as in the file name). */
    val sequence: Int?,
    /** Question number read from the screen ("Questão 05" → 5), when the OCR found one. */
    @ColumnInfo(name = "question_number") val questionNumber: Int?,
    val origin: CaptureOrigin,
    @ColumnInfo(name = "image_path") val imagePath: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    /** Text read by ML Kit, line by line; null when no OCR ran (manual or imported captures). */
    @ColumnInfo(name = "ocr_text") val ocrText: String?,
    /** SimHash of the normalized OCR text (16 hex digits), to find the same question across sessions. */
    @ColumnInfo(name = "dedup_key") val dedupKey: String?,
    @ColumnInfo(name = "ai_status") val aiStatus: AiStatus,
    /** The AI's structured answer (JSON), once there is one. */
    @ColumnInfo(name = "ai_response") val aiResponse: String? = null,
    @ColumnInfo(name = "ai_model") val aiModel: String? = null,
    @ColumnInfo(name = "ai_answered_at") val aiAnsweredAt: Long? = null,
    /** AI attempts so far and the last error code (never a key or a payload). */
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "telegram_status") val telegramStatus: TelegramStatus = TelegramStatus.NOT_REQUESTED,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Just the columns the file reconciliation needs. */
data class QuestionImageRef(
    val id: String,
    @ColumnInfo(name = "image_path") val imagePath: String,
)

/**
 * One request sent to the AI: the local record of usage. Kept when the question is deleted (no
 * foreign key), so the token history stays complete. Never holds the key, the prompt or the image.
 */
@Entity(
    tableName = "ai_requests",
    indices = [Index(value = ["question_id"]), Index(value = ["started_at"])],
)
data class AiRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "question_id") val questionId: String,
    val model: String,
    val mode: AnalysisMode,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    val outcome: AiRequestOutcome = AiRequestOutcome.SENT,
    /** `AiError` name when it failed. */
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    @ColumnInfo(name = "http_status") val httpStatus: Int? = null,
    /** OpenAI's response id (`resp_…`), to match the usage dashboard. */
    @ColumnInfo(name = "response_id") val responseId: String? = null,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int? = null,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "reasoning_tokens") val reasoningTokens: Int? = null,
    /** Input tokens written to the prompt cache (billed above the plain input rate). */
    @ColumnInfo(name = "cache_write_tokens") val cacheWriteTokens: Int? = null,
    /** The `service_tier` OpenAI says served the request (`default`, `flex`, `priority`…): sets the price. */
    @ColumnInfo(name = "service_tier") val serviceTier: String? = null,
    /** Of [cacheWriteTokens], those written with the 12-hour lifetime (2.5× input instead of 1.25×). */
    @ColumnInfo(name = "cache_write_12h_tokens") val cacheWrite12hTokens: Int? = null,
    /** Image mode: the size of the image actually sent (the question's crop), never its content. */
    @ColumnInfo(name = "image_width") val imageWidth: Int? = null,
    @ColumnInfo(name = "image_height") val imageHeight: Int? = null,
    /** Image mode: the JPEG's size in bytes, before base64. */
    @ColumnInfo(name = "image_bytes") val imageBytes: Int? = null,
)
