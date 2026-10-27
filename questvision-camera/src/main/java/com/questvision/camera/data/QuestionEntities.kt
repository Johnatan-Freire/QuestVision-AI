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

/** AI processing of a question. Only states the app can reach today or in the next phases. */
enum class AiStatus {
    /** Not to be sent automatically: manual captures (on command only) and captures imported from before. */
    NOT_REQUESTED,

    /** New automatic question waiting for the AI (processed from phase 3.4 on). */
    PENDING,
    PROCESSING,
    ANSWERED,

    /** Answered, but the answer needs a human look (low confidence, illegible, incomplete). */
    NEEDS_REVIEW,
    FAILED,
    ;

    /** Its photo must survive the automatic cleanup until the AI has seen it. */
    val keepsPhoto: Boolean get() = this == PENDING || this == PROCESSING
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
