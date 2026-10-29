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

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.questvision.camera.CaptureOrigin
import com.questvision.camera.CaptureRepository
import com.questvision.camera.MAX_RECENT_CAPTURES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real Room schema and SQL on a device: constraints, ordering, updates, and that data survives
 * closing and reopening the database file. Works in the test app's cache, never in the app's data.
 */
@RunWith(AndroidJUnit4::class)
class QuestionDaoTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workDir = File(context.cacheDir, "question-dao-test")
    private val dbName = "question-dao-test.db"
    private lateinit var database: QuestVisionDatabase
    private val dao get() = database.questionDao()

    private fun open() = Room.databaseBuilder(context, QuestVisionDatabase::class.java, dbName).build()

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        workDir.deleteRecursively()
        database = open()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(dbName)
        workDir.deleteRecursively()
    }

    private fun question(
        path: String,
        capturedAt: Long,
        session: String? = "s1",
        status: AiStatus = AiStatus.PENDING,
        dedupKey: String? = "00000000000000aa",
    ) = QuestionEntity(
        id = "id-$path",
        sessionId = session,
        sequence = 1,
        questionNumber = 3,
        origin = if (session == null) CaptureOrigin.MANUAL else CaptureOrigin.AUTOMATIC,
        imagePath = path,
        capturedAt = capturedAt,
        ocrText = "Questão 03\nQual comando…",
        dedupKey = dedupKey,
        aiStatus = status,
        updatedAt = capturedAt,
    )

    @Test
    fun roundTrip_keepsEveryField() =
        runBlocking {
            dao.insertSession(CaptureSessionEntity("s1", 1L))
            val stored = question("a.jpg", 10L).copy(aiResponse = "{}", aiModel = "m", aiAnsweredAt = 11L, attempts = 2, lastError = "E")

            assertNotEquals(-1L, dao.insertQuestion(stored))

            assertEquals(stored, dao.question(stored.id))
            assertEquals(stored, dao.questionByImagePath("a.jpg"))
        }

    @Test
    fun imagePath_isUnique_andSessionMustExist() =
        runBlocking {
            dao.insertSession(CaptureSessionEntity("s1", 1L))
            dao.insertQuestion(question("a.jpg", 10L))

            assertEquals(-1L, dao.insertQuestion(question("a.jpg", 20L).copy(id = "other")))
            assertEquals(-1L, dao.insertSession(CaptureSessionEntity("s1", 99L)))
            try {
                dao.insertQuestion(question("b.jpg", 30L, session = "unknown"))
                fail("A question of an unknown session was accepted")
            } catch (expected: SQLiteConstraintException) {
            }
            assertEquals(1, dao.history().size)
        }

    @Test
    fun queries_orderAndFilter() =
        runBlocking {
            dao.insertSession(CaptureSessionEntity("s1", 1L))
            dao.insertSession(CaptureSessionEntity("s2", 2L))
            dao.insertQuestion(question("b.jpg", 20L))
            dao.insertQuestion(question("a.jpg", 10L))
            dao.insertQuestion(question("c.jpg", 30L, session = "s2", dedupKey = "00000000000000bb"))
            dao.insertQuestion(question("m.jpg", 40L, session = null, status = AiStatus.NOT_REQUESTED, dedupKey = null))

            assertEquals(listOf("m.jpg", "c.jpg", "b.jpg", "a.jpg"), dao.history().map { it.imagePath })
            assertEquals(listOf("a.jpg", "b.jpg"), dao.questionsInSession("s1").map { it.imagePath })
            assertEquals(listOf("b.jpg", "a.jpg"), dao.questionsWithDedupKey("00000000000000aa").map { it.imagePath })
            assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), dao.questionsWithAiStatus(AiStatus.PENDING).map { it.imagePath })
            assertEquals(
                setOf("a.jpg", "b.jpg", "c.jpg"),
                dao.imagePathsWithAiStatus(listOf(AiStatus.PENDING, AiStatus.PROCESSING)).toSet(),
            )
        }

    @Test
    fun updates_andDeletes() =
        runBlocking {
            dao.insertSession(CaptureSessionEntity("s1", 1L))
            val q = question("a.jpg", 10L)
            dao.insertQuestion(q)
            dao.insertQuestion(question("b.jpg", 20L))

            assertEquals(1, dao.updateAiStatus(q.id, AiStatus.FAILED, 2, "HTTP_429", 50L))
            assertEquals(1, dao.saveAiResult(q.id, AiStatus.ANSWERED, "{\"answer\":\"B\"}", "model", 60L))
            assertEquals(1, dao.updateTelegramStatus(q.id, TelegramStatus.SENT, 70L))
            assertEquals(1, dao.updateImagePath(q.id, "kept/a.jpg", 80L))
            assertEquals(0, dao.updateAiStatus("missing", AiStatus.FAILED, 1, null, 1L))
            dao.endSession("s1", 90L)

            val updated = dao.question(q.id)!!
            assertEquals(AiStatus.ANSWERED, updated.aiStatus)
            assertEquals("{\"answer\":\"B\"}", updated.aiResponse)
            assertEquals(2, updated.attempts)
            assertNull(updated.lastError)
            assertEquals(TelegramStatus.SENT, updated.telegramStatus)
            assertEquals("kept/a.jpg", updated.imagePath)
            assertEquals(90L, dao.session("s1")!!.endedAt)

            assertEquals(1, dao.deleteByImagePaths(listOf("b.jpg", "nope.jpg")))
            assertEquals(1, dao.deleteQuestion(q.id))
            assertTrue(dao.history().isEmpty())
        }

    @Test
    fun data_survivesClosingAndReopeningTheFile() =
        runBlocking {
            dao.insertSession(CaptureSessionEntity("s1", 1L))
            dao.insertQuestion(question("a.jpg", 10L))
            dao.updateAiStatus("id-a.jpg", AiStatus.PROCESSING, 1, null, 20L)
            database.close()

            database = open()

            assertEquals(AiStatus.PROCESSING, dao.question("id-a.jpg")!!.aiStatus)
            assertEquals(1L, dao.session("s1")!!.startedAt)
        }

    @Test
    fun failedTransaction_leavesNothingBehind() =
        runBlocking {
            runCatching {
                database.withTransaction {
                    dao.insertSession(CaptureSessionEntity("s1", 1L))
                    dao.insertQuestion(question("a.jpg", 10L))
                    error("crash before commit")
                }
            }

            assertNull(dao.session("s1"))
            assertTrue(dao.history().isEmpty())
        }

    /** The repository over real SQLite and real files, as the app runs it. */
    @Test
    fun repository_endToEnd() =
        runBlocking {
            val captures = CaptureRepository(File(workDir, "captures"))

            fun repository() =
                QuestionRepository(
                    dao,
                    database.aiRequestDao(),
                    object : TransactionRunner {
                        override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction { block() }
                    },
                    captures,
                )

            fun photo(file: File) = file.apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val pending = photo(captures.newAutomaticCaptureFile("20261024-100000", 1, nowMs = 1_000L)).apply { setLastModified(1_000L) }
            repository().recordCapture(pending, CaptureOrigin.AUTOMATIC, "20261024-100000", 1, 1_000L, null, AiStatus.PENDING).getOrThrow()
            repeat(MAX_RECENT_CAPTURES + 2) { i ->
                val manual = photo(captures.newManualCaptureFile(nowMs = 10_000L + i)).apply { setLastModified(10_000L + i * 1_000L) }
                repository().recordCapture(manual, CaptureOrigin.MANUAL, null, null, 10_000L + i, null, AiStatus.NOT_REQUESTED).getOrThrow()
            }

            assertEquals(2, repository().prune())
            assertTrue(pending.exists())
            database.close()
            database = open()

            // After a "restart": the rows match the files, nothing to repair.
            val reopened = repository()
            assertEquals(ReconcileReport(), reopened.reconcile())
            assertEquals(MAX_RECENT_CAPTURES + 1, reopened.history().size)
            assertTrue(reopened.history().all { it.file.exists() })
        }

    @Test
    fun aiClaim_isAtomic_andRestartRecoveryFailsInFlightWork() =
        runBlocking {
            dao.insertSession(CaptureSessionEntity("s1", 1L))
            dao.insertQuestion(question("a.jpg", 10L))
            val requests = database.aiRequestDao()

            assertEquals(1, dao.claimForAi("id-a.jpg", 20L))
            assertEquals(0, dao.claimForAi("id-a.jpg", 21L))
            val requestId =
                requests.insert(
                    AiRequestEntity(questionId = "id-a.jpg", model = "gpt-6-luna", mode = AnalysisMode.TEXT, startedAt = 20L),
                )
            assertEquals(AiStatus.PROCESSING, dao.question("id-a.jpg")!!.aiStatus)
            assertEquals(1, dao.question("id-a.jpg")!!.attempts)

            // Process death, then the next start.
            database.close()
            database = open()
            assertEquals(1, dao.failAllProcessing("INTERRUPTED", 30L))
            assertEquals(1, database.aiRequestDao().interruptAllSent(30L))

            val row = dao.question("id-a.jpg")!!
            assertEquals(AiStatus.FAILED, row.aiStatus)
            assertEquals("INTERRUPTED", row.lastError)
            assertEquals(AiRequestOutcome.INTERRUPTED, database.aiRequestDao().request(requestId)!!.outcome)
            // A FAILED question can be claimed again on request.
            assertEquals(1, dao.claimForAi("id-a.jpg", 40L))
            assertEquals(2, dao.question("id-a.jpg")!!.attempts)
        }

    @Test
    fun aiResult_andUsage_arePersisted_andTheLogOutlivesTheQuestion() =
        runBlocking {
            dao.insertSession(CaptureSessionEntity("s1", 1L))
            dao.insertQuestion(question("a.jpg", 10L))
            val requests = database.aiRequestDao()
            dao.claimForAi("id-a.jpg", 20L)
            val requestId =
                requests.insert(
                    AiRequestEntity(questionId = "id-a.jpg", model = "gpt-6-luna", mode = AnalysisMode.IMAGE, startedAt = 20L),
                )

            database.withTransaction {
                dao.saveAiResult("id-a.jpg", AiStatus.ANSWERED, "{\"answer\":[\"B\"]}", "gpt-6-luna", 25L)
                requests.finish(requestId, AiRequestOutcome.SUCCEEDED, 25L, null, 200, "resp_1", 3_400, 0, 120, 60, 0, null, "default")
            }
            dao.deleteQuestion("id-a.jpg")

            val log = requests.requestsFor("id-a.jpg").single()
            assertEquals(AiRequestOutcome.SUCCEEDED, log.outcome)
            assertEquals(AnalysisMode.IMAGE, log.mode)
            assertEquals(3_400, log.inputTokens)
            assertEquals(120, log.outputTokens)
            assertEquals("resp_1", log.responseId)
            assertEquals("default", log.serviceTier)
            assertEquals(0, log.cacheWriteTokens)
            // Deleting the question frees nothing in the request limit.
            assertEquals(1, requests.countSince(0L))
        }

    /** The limit, the claim and the log entry in one SQLite transaction, even across two repositories. */
    @Test
    fun beginAiRequest_isAtomic_overRealSqlite() =
        runBlocking {
            val captures = CaptureRepository(File(workDir, "captures"))

            fun repository() =
                QuestionRepository(
                    dao,
                    database.aiRequestDao(),
                    object : TransactionRunner {
                        override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction { block() }
                    },
                    captures,
                )
            // Each repository does its start-up recovery first, as the app's single instance does. It
            // runs before the rows exist: reconcile() also drops rows whose photo is missing.
            val racers = listOf(repository(), repository()).onEach { it.reconcile() }
            dao.insertSession(CaptureSessionEntity("s1", 1L))
            dao.insertQuestion(question("a.jpg", 10L))
            dao.insertQuestion(question("b.jpg", 11L))

            // Two repositories (two mutexes) racing for the same question: SQLite lets one through.
            val starts =
                racers
                    .map { async(Dispatchers.IO) { it.beginAiRequest("id-a.jpg", "gpt-6-luna", AnalysisMode.TEXT, maxRequests = 10) } }
                    .awaitAll()
            assertEquals(1, starts.count { it is AiRequestStart.Started })
            assertEquals(1, starts.count { it == AiRequestStart.AlreadyProcessing })
            assertEquals(1, database.aiRequestDao().countSince(0L))

            // Limit of 1 already used: nothing is claimed nor logged.
            assertEquals(
                AiRequestStart.LimitReached,
                racers.first().beginAiRequest("id-b.jpg", "gpt-6-luna", AnalysisMode.TEXT, maxRequests = 1),
            )
            assertEquals(AiStatus.PENDING, dao.question("id-b.jpg")!!.aiStatus)
            assertEquals(1, database.aiRequestDao().countSince(0L))
        }
}
