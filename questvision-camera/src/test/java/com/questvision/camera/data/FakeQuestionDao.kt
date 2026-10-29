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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [QuestionDao] with SQLite's constraints that matter here (unique id and image path,
 * session foreign key) and failure injection, for repository tests on the JVM. The real SQL is
 * covered by the instrumented QuestionDaoTest.
 */
internal class FakeQuestionDao : QuestionDao {
    val sessions = LinkedHashMap<String, CaptureSessionEntity>()
    private val rows = MutableStateFlow<Map<String, QuestionEntity>>(emptyMap())

    /** The next this-many question writes throw, like a full disk or a locked database. */
    var failingWrites = 0

    val questions: Collection<QuestionEntity> get() = rows.value.values

    private fun checkWrite() {
        if (failingWrites > 0) {
            failingWrites--
            throw IllegalStateException("disk I/O error")
        }
    }

    private fun ordered(list: Collection<QuestionEntity>) =
        list.sortedWith(
            compareByDescending<QuestionEntity> {
                it.capturedAt
            }.thenByDescending { it.id },
        )

    override suspend fun insertSession(session: CaptureSessionEntity): Long {
        if (session.id in sessions) return -1
        sessions[session.id] = session
        return sessions.size.toLong()
    }

    override suspend fun endSession(
        id: String,
        endedAt: Long,
    ) {
        sessions[id]?.let { sessions[id] = it.copy(endedAt = endedAt) }
    }

    override suspend fun session(id: String) = sessions[id]

    override suspend fun insertQuestion(question: QuestionEntity): Long {
        checkWrite()
        check(question.sessionId == null || question.sessionId in sessions) { "FOREIGN KEY constraint failed" }
        if (question.id in rows.value || rows.value.values.any { it.imagePath == question.imagePath }) return -1
        rows.value = rows.value + (question.id to question)
        return rows.value.size.toLong()
    }

    override suspend fun updateQuestion(question: QuestionEntity) {
        checkWrite()
        if (question.id in rows.value) rows.value = rows.value + (question.id to question)
    }

    override suspend fun question(id: String) = rows.value[id]

    override suspend fun questionByImagePath(imagePath: String) = rows.value.values.firstOrNull { it.imagePath == imagePath }

    override suspend fun history() = ordered(rows.value.values)

    override fun observeHistory(): Flow<List<QuestionEntity>> = rows.map { ordered(it.values) }

    override suspend fun questionsInSession(sessionId: String) =
        rows.value.values
            .filter { it.sessionId == sessionId }
            .sortedWith(compareBy<QuestionEntity> { it.capturedAt }.thenBy { it.id })

    override suspend fun questionsWithDedupKey(dedupKey: String) =
        rows.value.values
            .filter { it.dedupKey == dedupKey }
            .sortedByDescending { it.capturedAt }

    override suspend fun questionsWithAiStatus(status: AiStatus) =
        rows.value.values
            .filter { it.aiStatus == status }
            .sortedBy { it.capturedAt }

    override suspend fun imagePathsWithAiStatus(statuses: List<AiStatus>) =
        rows.value.values
            .filter { it.aiStatus in statuses }
            .map { it.imagePath }

    override suspend fun imageRefs() = rows.value.values.map { QuestionImageRef(it.id, it.imagePath) }

    private fun change(
        id: String,
        transform: (QuestionEntity) -> QuestionEntity,
    ): Int {
        val row = rows.value[id] ?: return 0
        rows.value = rows.value + (id to transform(row))
        return 1
    }

    override suspend fun updateAiStatus(
        id: String,
        status: AiStatus,
        attempts: Int,
        lastError: String?,
        updatedAt: Long,
    ) = change(id) { it.copy(aiStatus = status, attempts = attempts, lastError = lastError, updatedAt = updatedAt) }

    override suspend fun saveAiResult(
        id: String,
        status: AiStatus,
        response: String,
        model: String,
        answeredAt: Long,
    ) = change(id) {
        it.copy(
            aiStatus = status,
            aiResponse = response,
            aiModel = model,
            aiAnsweredAt = answeredAt,
            lastError = null,
            updatedAt = answeredAt,
        )
    }

    override suspend fun updateTelegramStatus(
        id: String,
        status: TelegramStatus,
        updatedAt: Long,
    ) = change(id) { it.copy(telegramStatus = status, updatedAt = updatedAt) }

    override suspend fun updateImagePath(
        id: String,
        imagePath: String,
        updatedAt: Long,
    ) = change(id) { it.copy(imagePath = imagePath, updatedAt = updatedAt) }

    override suspend fun claimForAi(
        id: String,
        updatedAt: Long,
    ): Int {
        val row = rows.value[id]?.takeIf { it.aiStatus != AiStatus.PROCESSING } ?: return 0
        return change(id) { row.copy(aiStatus = AiStatus.PROCESSING, attempts = row.attempts + 1, lastError = null, updatedAt = updatedAt) }
    }

    override suspend fun failAi(
        id: String,
        error: String,
        updatedAt: Long,
    ): Int {
        if (rows.value[id]?.aiStatus != AiStatus.PROCESSING) return 0
        return change(id) { it.copy(aiStatus = AiStatus.FAILED, lastError = error, updatedAt = updatedAt) }
    }

    override suspend fun failAllProcessing(
        error: String,
        updatedAt: Long,
    ): Int =
        rows.value.values
            .filter { it.aiStatus == AiStatus.PROCESSING }
            .sumOf { row -> change(row.id) { it.copy(aiStatus = AiStatus.FAILED, lastError = error, updatedAt = updatedAt) } }

    override suspend fun deleteQuestion(id: String): Int {
        if (id !in rows.value) return 0
        rows.value = rows.value - id
        return 1
    }

    override suspend fun deleteByImagePaths(imagePaths: List<String>): Int {
        val doomed =
            rows.value.values
                .filter { it.imagePath in imagePaths }
                .map { it.id }
        rows.value = rows.value - doomed.toSet()
        return doomed.size
    }
}

/** In-memory [AiRequestDao]. */
internal class FakeAiRequestDao : AiRequestDao {
    val rows = LinkedHashMap<Long, AiRequestEntity>()
    private var nextId = 1L

    override suspend fun insert(request: AiRequestEntity): Long {
        val id = nextId++
        rows[id] = request.copy(id = id)
        return id
    }

    override suspend fun finish(
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
        cacheWrite12hTokens: Int?,
        serviceTier: String?,
    ): Int {
        val row = rows[id] ?: return 0
        rows[id] =
            row.copy(
                outcome = outcome,
                finishedAt = finishedAt,
                errorCode = errorCode,
                httpStatus = httpStatus,
                responseId = responseId,
                inputTokens = inputTokens,
                cachedTokens = cachedTokens,
                outputTokens = outputTokens,
                reasoningTokens = reasoningTokens,
                cacheWriteTokens = cacheWriteTokens,
                cacheWrite12hTokens = cacheWrite12hTokens,
                serviceTier = serviceTier,
            )
        return 1
    }

    override suspend fun request(id: Long) = rows[id]

    override suspend fun requestsFor(questionId: String) =
        rows.values
            .filter {
                it.questionId == questionId
            }.sortedWith(compareByDescending<AiRequestEntity> { it.startedAt }.thenByDescending { it.id })

    override suspend fun all() = rows.values.sortedWith(compareByDescending<AiRequestEntity> { it.startedAt }.thenByDescending { it.id })

    override suspend fun countSince(since: Long) = rows.values.count { it.startedAt >= since }

    override suspend fun interruptAllSent(finishedAt: Long): Int {
        val sent = rows.values.filter { it.outcome == AiRequestOutcome.SENT }
        sent.forEach { rows[it.id] = it.copy(outcome = AiRequestOutcome.INTERRUPTED, finishedAt = finishedAt) }
        return sent.size
    }
}

/** Runs the block directly; atomicity is SQLite's job and is covered by the instrumented tests. */
internal object DirectTransactions : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}
