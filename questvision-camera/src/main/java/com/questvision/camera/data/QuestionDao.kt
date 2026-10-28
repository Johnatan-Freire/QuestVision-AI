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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {
    /** Returns -1 when the session already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: CaptureSessionEntity): Long

    @Query("UPDATE capture_sessions SET ended_at = :endedAt WHERE id = :id")
    suspend fun endSession(
        id: String,
        endedAt: Long,
    )

    @Query("SELECT * FROM capture_sessions WHERE id = :id")
    suspend fun session(id: String): CaptureSessionEntity?

    /** Returns -1 when a row with the same id or image path already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQuestion(question: QuestionEntity): Long

    @Update
    suspend fun updateQuestion(question: QuestionEntity)

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun question(id: String): QuestionEntity?

    @Query("SELECT * FROM questions WHERE image_path = :imagePath")
    suspend fun questionByImagePath(imagePath: String): QuestionEntity?

    /** Newest first; ties (same millisecond) broken by id so the order is stable. */
    @Query("SELECT * FROM questions ORDER BY captured_at DESC, id DESC")
    suspend fun history(): List<QuestionEntity>

    @Query("SELECT * FROM questions ORDER BY captured_at DESC, id DESC")
    fun observeHistory(): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM questions WHERE session_id = :sessionId ORDER BY captured_at ASC, id ASC")
    suspend fun questionsInSession(sessionId: String): List<QuestionEntity>

    /** Earlier questions with the same OCR fingerprint, in any session, newest first. */
    @Query("SELECT * FROM questions WHERE dedup_key = :dedupKey ORDER BY captured_at DESC")
    suspend fun questionsWithDedupKey(dedupKey: String): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE ai_status = :status ORDER BY captured_at ASC")
    suspend fun questionsWithAiStatus(status: AiStatus): List<QuestionEntity>

    @Query("SELECT image_path FROM questions WHERE ai_status IN (:statuses)")
    suspend fun imagePathsWithAiStatus(statuses: List<AiStatus>): List<String>

    @Query("SELECT id, image_path FROM questions")
    suspend fun imageRefs(): List<QuestionImageRef>

    @Query(
        "UPDATE questions SET ai_status = :status, attempts = :attempts, last_error = :lastError, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun updateAiStatus(
        id: String,
        status: AiStatus,
        attempts: Int,
        lastError: String?,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE questions SET ai_status = :status, ai_response = :response, ai_model = :model, " +
            "ai_answered_at = :answeredAt, last_error = NULL, updated_at = :answeredAt WHERE id = :id",
    )
    suspend fun saveAiResult(
        id: String,
        status: AiStatus,
        response: String,
        model: String,
        answeredAt: Long,
    ): Int

    /**
     * Marks the question PROCESSING and counts the attempt, unless it already is: a single statement,
     * so two callers can never both claim it. Returns 1 when claimed.
     */
    @Query(
        "UPDATE questions SET ai_status = 'PROCESSING', attempts = attempts + 1, last_error = NULL, " +
            "updated_at = :updatedAt WHERE id = :id AND ai_status != 'PROCESSING'",
    )
    suspend fun claimForAi(
        id: String,
        updatedAt: Long,
    ): Int

    /** Ends a claimed attempt with an error; the answer of an earlier attempt, if any, is kept. */
    @Query(
        "UPDATE questions SET ai_status = 'FAILED', last_error = :error, updated_at = :updatedAt " +
            "WHERE id = :id AND ai_status = 'PROCESSING'",
    )
    suspend fun failAi(
        id: String,
        error: String,
        updatedAt: Long,
    ): Int

    /** After a restart nothing is in flight anymore: every PROCESSING row becomes FAILED. */
    @Query(
        "UPDATE questions SET ai_status = 'FAILED', last_error = :error, updated_at = :updatedAt " +
            "WHERE ai_status = 'PROCESSING'",
    )
    suspend fun failAllProcessing(
        error: String,
        updatedAt: Long,
    ): Int

    @Query("UPDATE questions SET telegram_status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTelegramStatus(
        id: String,
        status: TelegramStatus,
        updatedAt: Long,
    ): Int

    @Query("UPDATE questions SET image_path = :imagePath, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateImagePath(
        id: String,
        imagePath: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM questions WHERE id = :id")
    suspend fun deleteQuestion(id: String): Int

    @Query("DELETE FROM questions WHERE image_path IN (:imagePaths)")
    suspend fun deleteByImagePaths(imagePaths: List<String>): Int
}
