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
import androidx.room.Query

/** The local log of requests sent to the AI (see [AiRequestEntity]). */
@Dao
interface AiRequestDao {
    @Insert
    suspend fun insert(request: AiRequestEntity): Long

    @Query(
        "UPDATE ai_requests SET outcome = :outcome, finished_at = :finishedAt, error_code = :errorCode, " +
            "http_status = :httpStatus, response_id = :responseId, input_tokens = :inputTokens, " +
            "cached_tokens = :cachedTokens, output_tokens = :outputTokens, reasoning_tokens = :reasoningTokens, " +
            "cache_write_tokens = :cacheWriteTokens, service_tier = :serviceTier WHERE id = :id",
    )
    suspend fun finish(
        id: Long,
        outcome: AiRequestOutcome,
        finishedAt: Long,
        errorCode: String?,
        httpStatus: Int?,
        responseId: String?,
        inputTokens: Int?,
        cachedTokens: Int?,
        outputTokens: Int?,
        reasoningTokens: Int?,
        cacheWriteTokens: Int?,
        serviceTier: String?,
    ): Int

    @Query("SELECT * FROM ai_requests WHERE id = :id")
    suspend fun request(id: Long): AiRequestEntity?

    /** Newest first. */
    @Query("SELECT * FROM ai_requests WHERE question_id = :questionId ORDER BY started_at DESC, id DESC")
    suspend fun requestsFor(questionId: String): List<AiRequestEntity>

    @Query("SELECT * FROM ai_requests ORDER BY started_at DESC, id DESC")
    suspend fun all(): List<AiRequestEntity>

    /**
     * Requests started at or after [since], whatever their outcome: a failed, cancelled or interrupted
     * request may have been billed too. Rows outlive their question, so deleting photos frees nothing.
     */
    @Query("SELECT COUNT(*) FROM ai_requests WHERE started_at >= :since")
    suspend fun countSince(since: Long): Int

    /** After a restart nothing is in flight anymore. */
    @Query("UPDATE ai_requests SET outcome = 'INTERRUPTED', finished_at = :finishedAt WHERE outcome = 'SENT'")
    suspend fun interruptAllSent(finishedAt: Long): Int
}
