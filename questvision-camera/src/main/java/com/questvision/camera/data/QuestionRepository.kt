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
    /** Rows whose photo no longer exists (e.g. a crash between deleting the file and the row). */
    val removed: Int = 0,
)

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
    private val transactions: TransactionRunner,
    private val captures: CaptureRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject
    constructor(
        dao: QuestionDao,
        transactions: TransactionRunner,
        captures: CaptureRepository,
    ) : this(dao, transactions, captures, Dispatchers.IO)

    private val mutex = Mutex()

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

    suspend fun questionsInSession(sessionId: String): List<QuestionEntity> = serialized { dao.questionsInSession(sessionId) }

    /** Earlier questions with the same OCR fingerprint, across sessions (the future local answer cache). */
    suspend fun questionsLike(dedupKey: String): List<QuestionEntity> = serialized { dao.questionsWithDedupKey(dedupKey) }

    suspend fun updateAiStatus(
        id: String,
        status: AiStatus,
        attempts: Int,
        lastError: String?,
    ): Boolean = serialized { dao.updateAiStatus(id, status, attempts, lastError, clock()) == 1 }

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

    /** Deletes a photo and its row: file first, so an interruption leaves only a dangling row. */
    suspend fun delete(file: File) =
        serialized {
            captures.delete(file)
            dao.questionByImagePath(captures.relativePath(file))?.let { dao.deleteQuestion(it.id) }
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
     * Makes the database match the files: imports photos without a row (from before the database or
     * after an interrupted insert) as [AiStatus.NOT_REQUESTED] — never sent to the AI on their own —,
     * repoints rows whose photo moved, and removes rows whose photo is gone.
     */
    suspend fun reconcile(): ReconcileReport =
        serialized {
            val files = captures.allCaptureFiles()
            val byPath = files.associateBy { captures.relativePath(it) }
            val byName = files.groupBy { it.name }
            var repointed = 0
            var removed = 0
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
            ReconcileReport(imported, repointed, removed)
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

private val QUESTION_NUMBER = Regex("""^\s*quest[aã]o\s*(?:n[º°o.]?\s*)?(\d{1,3})(?!\d)""", RegexOption.IGNORE_CASE)

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
