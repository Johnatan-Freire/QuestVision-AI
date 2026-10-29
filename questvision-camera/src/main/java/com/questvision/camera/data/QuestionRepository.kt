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

import com.questvision.camera.CaptureEntry
import com.questvision.camera.CaptureOrigin
import com.questvision.camera.CaptureRepository
import com.questvision.camera.parseCaptureEntry
import com.questvision.camera.recognition.RecognizedText
import com.questvision.camera.recognition.TextFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Attempts at writing a new capture's row before reporting the failure. */
private const val INSERT_ATTEMPTS = 2

/** What [QuestionRepository.reconcile] changed to make the database match the files. */
data class ReconcileReport(
    /** Files without a row (captures from before the database, or a crash before the insert). */
    val imported: Int = 0,
    /** Rows whose photo had moved (e.g. to `kept/`) and were repointed. */
    val repointed: Int = 0,
    /** Rows whose photo no longer exists (e.g. a crash between deleting the file and the row), never one the AI saw. */
    val removed: Int = 0,
    /** Questions left PROCESSING by a crash, now FAILED (`INTERRUPTED`); never re-sent on their own. */
    val interrupted: Int = 0,
    /** Rows the AI already saw whose photo is gone: kept with their answer and request log (photo missing). */
    val keptWithoutPhoto: Int = 0,
)

/**
 * Token counts OpenAI reported for one response (null when it reported none). [cachedTokens] and
 * [cacheWriteTokens] are part of [inputTokens]; [reasoningTokens] is part of [outputTokens].
 */
data class TokenUsage(
    val inputTokens: Int?,
    val cachedTokens: Int?,
    val outputTokens: Int?,
    val reasoningTokens: Int?,
    val cacheWriteTokens: Int? = null,
    /** Of [cacheWriteTokens], those with the 12-hour lifetime; null when OpenAI didn't break them down. */
    val cacheWrite12hTokens: Int? = null,
)

/**
 * What a manual cleanup of pending captures would remove: questions never sent to the AI (PENDING,
 * no request logged) and not kept, with the space their photos take and their date range.
 */
data class PendingCleanup(
    val ids: List<String>,
    val bytes: Long,
    val oldestAt: Long?,
    val newestAt: Long?,
) {
    val count: Int get() = ids.size
}

/** How a discard ended; anything but [DELETED] is shown to the user. */
enum class DeleteResult {
    DELETED,

    /** Refused: the AI is analyzing this question. */
    IN_ANALYSIS,

    /** The file could not be deleted; nothing was removed. */
    FAILED,
}

/** The size of an image sent in image mode (never its content), for the request log. */
data class SentImage(
    val width: Int,
    val height: Int,
    val bytes: Int,
)

/** The window of the local request limit: it counts what was sent, so restarting the app resets nothing. */
const val AI_LIMIT_WINDOW_MS = 24L * 60 * 60 * 1000

/** How [QuestionRepository.beginAiRequest] went. */
sealed interface AiRequestStart {
    /** Claimed (PROCESSING) and logged as SENT: the request may now be sent. */
    data class Started(
        val requestId: Long,
    ) : AiRequestStart

    /** [AI_LIMIT_WINDOW_MS] already holds the allowed number of requests: nothing changed. */
    data object LimitReached : AiRequestStart

    /** The question is already PROCESSING: nothing changed. */
    data object AlreadyProcessing : AiRequestStart
}

/** Error code recorded for requests and questions the app stopped in the middle of. */
const val INTERRUPTED_ERROR = "INTERRUPTED"

/**
 * The persistent record of captured questions: SQLite (Room) for the data, private files for the
 * photos. The database stores relative paths, never image bytes.
 *
 * Files and rows are kept consistent even across crashes: deletions remove the file first and the
 * row second (a crash in between leaves a dangling row), moves rename first and repoint second, and
 * [reconcile] — run when the app starts — repairs whatever an interruption left behind. All work runs
 * on the IO dispatcher, serialized, so the camera and the main thread never wait for it.
 */
@Singleton
class QuestionRepository internal constructor(
    private val dao: QuestionDao,
    private val requests: AiRequestDao,
    private val transactions: TransactionRunner,
    private val captures: CaptureRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject
    constructor(
        dao: QuestionDao,
        requests: AiRequestDao,
        transactions: TransactionRunner,
        captures: CaptureRepository,
    ) : this(dao, requests, transactions, captures, Dispatchers.IO)

    private val mutex = Mutex()

    /** Whether this process already closed the AI attempts an earlier process left in flight. */
    private var aiRecovered = false

    private suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { withContext(ioDispatcher) { block() } }

    suspend fun startSession(
        id: String,
        startedAt: Long,
    ) = serialized { dao.insertSession(CaptureSessionEntity(id, startedAt)) }

    suspend fun endSession(
        id: String,
        endedAt: Long,
    ) = serialized { dao.endSession(id, endedAt) }

    /**
     * Records a stored photo. Idempotent per file: recording the same photo again updates its row
     * (e.g. one [reconcile] imported first) instead of duplicating it. Retries once; on a persistent
     * failure the photo stays on disk and is imported by the next [reconcile], and the failure is
     * returned so the caller can tell the user.
     */
    suspend fun recordCapture(
        file: File,
        origin: CaptureOrigin,
        sessionId: String?,
        sequence: Int?,
        capturedAt: Long,
        ocr: RecognizedText?,
        aiStatus: AiStatus,
    ): Result<QuestionEntity> =
        serialized {
            val imagePath = captures.relativePath(file)
            val fingerprint = ocr?.let { TextFingerprint.of(it) }
            var lastError: Exception? = null
            repeat(INSERT_ATTEMPTS) {
                try {
                    return@serialized Result.success(
                        transactions.inTransaction {
                            if (sessionId != null) dao.insertSession(CaptureSessionEntity(sessionId, capturedAt))
                            val existing = dao.questionByImagePath(imagePath)
                            val entity =
                                QuestionEntity(
                                    id = existing?.id ?: UUID.randomUUID().toString(),
                                    sessionId = sessionId,
                                    sequence = sequence,
                                    questionNumber = ocr?.let(::questionNumberOf),
                                    origin = origin,
                                    imagePath = imagePath,
                                    capturedAt = capturedAt,
                                    ocrText = ocr?.let(::plainText),
                                    dedupKey = fingerprint?.let { dedupKeyOf(it) },
                                    aiStatus = aiStatus,
                                    updatedAt = clock(),
                                )
                            if (existing == null) dao.insertQuestion(entity) else dao.updateQuestion(entity)
                            entity
                        },
                    )
                } catch (exc: CancellationException) {
                    throw exc
                } catch (exc: Exception) {
                    lastError = exc
                }
            }
            Result.failure(lastError ?: IllegalStateException("Not recorded"))
        }

    /** Every recorded capture, newest first, for the history. */
    suspend fun history(): List<CaptureEntry> = serialized { dao.history().map(::toEntry) }

    suspend fun question(file: File): QuestionEntity? = serialized { dao.questionByImagePath(captures.relativePath(file)) }

    suspend fun question(id: String): QuestionEntity? = serialized { dao.question(id) }

    /** The photo of [question] on disk. */
    fun fileOf(question: QuestionEntity): File = captures.resolve(question.imagePath)

    suspend fun questionsInSession(sessionId: String): List<QuestionEntity> = serialized { dao.questionsInSession(sessionId) }

    /** Earlier questions with the same OCR fingerprint, across sessions (the future local answer cache). */
    suspend fun questionsLike(dedupKey: String): List<QuestionEntity> = serialized { dao.questionsWithDedupKey(dedupKey) }

    /** The preview of a manual cleanup of pending captures (see [PendingCleanup]); removes nothing. */
    suspend fun pendingCleanupPreview(): PendingCleanup =
        serialized {
            val rows = dao.questionsWithAiStatus(AiStatus.PENDING).filter { cleanable(it) }
            PendingCleanup(
                ids = rows.map { it.id },
                bytes = rows.sumOf { captures.resolve(it.imagePath).length() },
                oldestAt = rows.minOfOrNull { it.capturedAt },
                newestAt = rows.maxOfOrNull { it.capturedAt },
            )
        }

    /**
     * Removes, after the user confirmed [preview], those of its questions that are still cleanable now
     * (a question sent, kept or analyzed meanwhile is left alone): photo first, then the row. Returns
     * how many were removed.
     */
    suspend fun deletePending(preview: PendingCleanup): Int =
        serialized {
            var removed = 0
            for (id in preview.ids) {
                val row = dao.question(id) ?: continue
                if (row.aiStatus != AiStatus.PENDING || !cleanable(row)) continue
                if (!captures.delete(captures.resolve(row.imagePath))) continue
                dao.deleteQuestion(id)
                removed++
            }
            removed
        }

    // Never sent (no request logged for it) and not preserved by the user.
    private suspend fun cleanable(row: QuestionEntity): Boolean =
        !row.imagePath.startsWith("kept/") && requests.requestsFor(row.id).isEmpty()

    /** Questions the AI has seen or is seeing (any status but NOT_REQUESTED and PENDING), oldest first. */
    suspend fun questionsSentToAi(): List<QuestionEntity> =
        serialized {
            listOf(AiStatus.PROCESSING, AiStatus.ANSWERED, AiStatus.NEEDS_REVIEW, AiStatus.FAILED).flatMap { dao.questionsWithAiStatus(it) }
        }

    suspend fun updateAiStatus(
        id: String,
        status: AiStatus,
        attempts: Int,
        lastError: String?,
    ): Boolean = serialized { dao.updateAiStatus(id, status, attempts, lastError, clock()) == 1 }

    /**
     * Gets a request to the AI under way, in one transaction: checks the limit of [maxRequests] per
     * [AI_LIMIT_WINDOW_MS], marks the question PROCESSING (counting the attempt) unless it already is,
     * and logs the request as SENT. The same question is never claimed twice at once, and two callers
     * can never both pass the limit. Finish with [completeAi] or [failAi].
     */
    suspend fun beginAiRequest(
        questionId: String,
        model: String,
        mode: AnalysisMode,
        maxRequests: Int,
        image: SentImage? = null,
    ): AiRequestStart =
        serialized {
            // Attempts left in flight by an earlier process are closed first, never after a claim.
            recoverInterruptedAi()
            transactions.inTransaction {
                val now = clock()
                when {
                    requests.countSince(now - AI_LIMIT_WINDOW_MS) >= maxRequests -> {
                        AiRequestStart.LimitReached
                    }

                    dao.claimForAi(questionId, now) != 1 -> {
                        AiRequestStart.AlreadyProcessing
                    }

                    else -> {
                        AiRequestStart.Started(
                            requests.insert(
                                AiRequestEntity(
                                    questionId = questionId,
                                    model = model,
                                    mode = mode,
                                    startedAt = now,
                                    imageWidth = image?.width,
                                    imageHeight = image?.height,
                                    imageBytes = image?.bytes,
                                ),
                            ),
                        )
                    }
                }
            }
        }

    /** Requests started in the last [AI_LIMIT_WINDOW_MS], whatever their outcome. */
    suspend fun aiRequestsInLimitWindow(): Int = serialized { requests.countSince(clock() - AI_LIMIT_WINDOW_MS) }

    /** Stores the answer of a claimed question and closes its request log entry, atomically. */
    suspend fun completeAi(
        questionId: String,
        requestId: Long,
        status: AiStatus,
        response: String,
        model: String,
        responseId: String?,
        httpStatus: Int,
        usage: TokenUsage?,
        serviceTier: String?,
    ) = serialized {
        transactions.inTransaction {
            val now = clock()
            dao.saveAiResult(questionId, status, response, model, now)
            requests.finish(
                requestId,
                AiRequestOutcome.SUCCEEDED,
                now,
                null,
                httpStatus,
                responseId,
                usage?.inputTokens,
                usage?.cachedTokens,
                usage?.outputTokens,
                usage?.reasoningTokens,
                usage?.cacheWriteTokens,
                usage?.cacheWrite12hTokens,
                serviceTier,
            )
        }
    }

    /**
     * Ends a claimed attempt with [error]: the question becomes FAILED (photo, OCR text and any earlier
     * answer kept) and the request log entry, if one was started, records how it ended.
     */
    suspend fun failAi(
        questionId: String,
        requestId: Long?,
        error: String,
        outcome: AiRequestOutcome = AiRequestOutcome.FAILED,
        httpStatus: Int? = null,
        responseId: String? = null,
        usage: TokenUsage? = null,
        serviceTier: String? = null,
    ) = serialized {
        transactions.inTransaction {
            val now = clock()
            dao.failAi(questionId, error, now)
            if (requestId != null) {
                requests.finish(
                    requestId,
                    outcome,
                    now,
                    error,
                    httpStatus,
                    responseId,
                    usage?.inputTokens,
                    usage?.cachedTokens,
                    usage?.outputTokens,
                    usage?.reasoningTokens,
                    usage?.cacheWriteTokens,
                    usage?.cacheWrite12hTokens,
                    serviceTier,
                )
            }
        }
    }

    /** Requests sent for a question, newest first. */
    suspend fun aiRequestsFor(questionId: String): List<AiRequestEntity> = serialized { requests.requestsFor(questionId) }

    /** Moves a photo to the preserved folder and repoints its row. */
    suspend fun preserve(file: File): File =
        serialized {
            val kept = captures.preserve(file)
            if (kept != file) {
                dao.questionByImagePath(captures.relativePath(file))?.let {
                    dao.updateImagePath(it.id, captures.relativePath(kept), clock())
                }
            }
            kept
        }

    /**
     * Deletes a photo and its row: file first, so an interruption leaves only a dangling row. Refused
     * while the AI is processing it (the answer, possibly billed, would have nowhere to go); when the
     * file can't be deleted, the row is kept too, so nothing is half-removed.
     */
    suspend fun delete(file: File): DeleteResult =
        serialized {
            val row = dao.questionByImagePath(captures.relativePath(file))
            if (row?.aiStatus == AiStatus.PROCESSING) return@serialized DeleteResult.IN_ANALYSIS
            if (!captures.delete(file)) return@serialized DeleteResult.FAILED
            row?.let { dao.deleteQuestion(it.id) }
            DeleteResult.DELETED
        }

    /**
     * Applies the retention policy without touching photos the AI still has to see, nor photos
     * without a row yet (a failed record, imported by the next [reconcile]), and removes the rows of
     * the photos it deleted. Returns how many captures were removed.
     */
    suspend fun prune(): Int =
        serialized {
            val known = dao.imageRefs().mapTo(HashSet()) { it.imagePath }
            val unrecorded = captures.allCaptureFiles().map { captures.relativePath(it) }.filterNot { it in known }
            val protectedPaths =
                dao.imagePathsWithAiStatus(AiStatus.entries.filter { it.keepsPhoto }).toSet() + unrecorded
            val deleted = captures.pruneRecentCaptures(protectedPaths)
            if (deleted.isNotEmpty()) dao.deleteByImagePaths(deleted)
            deleted.size
        }

    /**
     * Once per process (the repository is a singleton), before any claim: nothing survives a restart in
     * flight, so close what an earlier process left SENT / PROCESSING, without re-sending. Their outcome
     * is unknown — OpenAI may have answered and billed them. Running it again later would wrongly close
     * a request this process is still waiting for. Call with [mutex] held.
     */
    private suspend fun recoverInterruptedAi(): Int {
        if (aiRecovered) return 0
        val interrupted =
            transactions.inTransaction {
                val now = clock()
                requests.interruptAllSent(now)
                dao.failAllProcessing(INTERRUPTED_ERROR, now)
            }
        aiRecovered = true
        return interrupted
    }

    /**
     * Closes AI attempts a crash left in flight (FAILED / `INTERRUPTED`, never re-sent automatically) and
     * makes the database match the files: imports photos without a row (from before the database or
     * after an interrupted insert) as [AiStatus.NOT_REQUESTED] — never sent to the AI on their own —,
     * repoints rows whose photo moved, and removes rows whose photo is gone.
     */
    suspend fun reconcile(): ReconcileReport =
        serialized {
            val interrupted = recoverInterruptedAi()
            val files = captures.allCaptureFiles()
            val byPath = files.associateBy { captures.relativePath(it) }
            val byName = files.groupBy { it.name }
            var repointed = 0
            var removed = 0
            var keptWithoutPhoto = 0
            val referenced = HashSet<String>()
            for (ref in dao.imageRefs()) {
                if (ref.imagePath in byPath) {
                    referenced += ref.imagePath
                    continue
                }
                val moved = byName[File(ref.imagePath).name]?.singleOrNull()?.let { captures.relativePath(it) }
                if (moved != null && moved !in referenced) {
                    dao.updateImagePath(ref.id, moved, clock())
                    referenced += moved
                    repointed++
                } else if (dao
                        .question(
                            ref.id,
                        )?.let { (it.aiStatus.keepsPhoto && it.aiStatus != AiStatus.PENDING) || it.aiResponse != null } ==
                    true
                ) {
                    // An answer (possibly paid for) outlives its photo: never removed automatically.
                    keptWithoutPhoto++
                } else {
                    dao.deleteQuestion(ref.id)
                    removed++
                }
            }
            var imported = 0
            for ((path, file) in byPath) {
                if (path in referenced) continue
                val entry = parseCaptureEntry(file, isPreserved = captures.isPreserved(file))
                transactions.inTransaction {
                    entry.session?.let { dao.insertSession(CaptureSessionEntity(it, entry.capturedAtMs)) }
                    dao.insertQuestion(
                        QuestionEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = entry.session,
                            sequence = entry.sequence,
                            questionNumber = null,
                            origin = entry.origin,
                            imagePath = path,
                            capturedAt = entry.capturedAtMs,
                            ocrText = null,
                            dedupKey = null,
                            aiStatus = AiStatus.NOT_REQUESTED,
                            updatedAt = clock(),
                        ),
                    )
                }
                imported++
            }
            ReconcileReport(imported, repointed, removed, interrupted, keptWithoutPhoto)
        }

    private fun toEntry(question: QuestionEntity): CaptureEntry =
        CaptureEntry(
            file = captures.resolve(question.imagePath),
            capturedAtMs = question.capturedAt,
            origin = question.origin,
            session = question.sessionId,
            sequence = question.sequence,
            isPreserved = question.imagePath.startsWith("kept/"),
        )
}

/** The OCR text as plain lines, as stored in the database. */
internal fun plainText(text: RecognizedText): String = text.lines.joinToString("\n") { line -> line.joinToString(" ") { it.text } }

/** "Questão 3", "QUESTÃO Nº 03", and OCR variants of the "ã" ("Questāo", "Questao", "Questáo"). */
internal val QUESTION_NUMBER = Regex("""^\s*quest[aãâáàäāAÃÂÁÀÄĀ]o\s*(?:n[º°o.]?\s*)?(\d{1,3})(?!\d)""", RegexOption.IGNORE_CASE)

/**
 * The question number shown on screen, from the first line that starts with "Questão N" ("Questão 05
 * — Redes" → 5). Lines that only mention a question further in ("Digite outra para receber a Questão
 * 03") are ignored. Null when there is none.
 */
internal fun questionNumberOf(text: RecognizedText): Int? =
    text.lines
        .asSequence()
        .map { line -> line.joinToString(" ") { it.text } }
        .firstNotNullOfOrNull {
            QUESTION_NUMBER
                .find(it)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        }

/** Fixed-width hex of the text's SimHash: the deduplication key. */
internal fun dedupKeyOf(fingerprint: TextFingerprint): String = String.format(Locale.US, "%016x", fingerprint.simHash)
