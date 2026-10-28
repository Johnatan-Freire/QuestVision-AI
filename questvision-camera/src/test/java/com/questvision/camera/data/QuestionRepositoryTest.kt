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

import com.questvision.camera.CaptureOrigin
import com.questvision.camera.CaptureRepository
import com.questvision.camera.MAX_RECENT_CAPTURES
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.recognition.TextFingerprint
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QuestionRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val dao = FakeQuestionDao()
    private val captures by lazy { CaptureRepository(File(folder.root, "captures"), dispatcher) }
    private val repository by lazy { newRepository() }

    private val requests = FakeAiRequestDao()

    private fun newRepository() = QuestionRepository(dao, requests, DirectTransactions, captures, dispatcher) { 9_999L }

    /** A photo CameraX would have written at [timeMs]; manual when [sequence] is null. */
    private fun photo(
        sequence: Int?,
        timeMs: Long,
        session: String = "s1",
    ): File {
        val file =
            if (sequence == null) {
                captures.newManualCaptureFile(nowMs = timeMs)
            } else {
                captures.newAutomaticCaptureFile(session, sequence, nowMs = timeMs)
            }
        return file.apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(timeMs)
        }
    }

    private suspend fun recordAuto(
        file: File,
        question: Int,
        sequence: Int,
        session: String = "s1",
    ) = repository
        .recordCapture(file, CaptureOrigin.AUTOMATIC, session, sequence, file.lastModified(), OcrScreens.screen(question), AiStatus.PENDING)
        .getOrThrow()

    private suspend fun recordManual(file: File) =
        repository.recordCapture(file, CaptureOrigin.MANUAL, null, null, file.lastModified(), null, AiStatus.NOT_REQUESTED).getOrThrow()

    @Test
    fun recordedQuestion_isStoredAndRetrieved() =
        runTest(dispatcher) {
            val file = photo(sequence = 1, timeMs = 1_000_000L)

            val stored = recordAuto(file, question = 2, sequence = 1)

            val loaded = repository.question(file)!!
            assertEquals(stored, loaded)
            assertEquals("s1", loaded.sessionId)
            assertEquals(1, loaded.sequence)
            assertEquals(2, loaded.questionNumber)
            assertTrue(loaded.ocrText!!.contains("traduzir nomes de domínio"))
            assertEquals(dedupKeyOf(TextFingerprint.of(OcrScreens.screen(2))), loaded.dedupKey)
            assertEquals(AiStatus.PENDING, loaded.aiStatus)
            assertNull(loaded.aiResponse)
            assertNull(loaded.aiModel)
            assertEquals(0, loaded.attempts)
            assertEquals(TelegramStatus.NOT_REQUESTED, loaded.telegramStatus)
            // The image stays a file: the row only holds its relative path.
            assertEquals(file.name, loaded.imagePath)
            assertTrue(file.exists())
            assertTrue("s1" in dao.sessions)
        }

    @Test
    fun recordingTheSamePhotoTwice_keepsOneRow() =
        runTest(dispatcher) {
            val file = photo(sequence = 1, timeMs = 1_000_000L)
            val first = recordAuto(file, question = 1, sequence = 1)

            val second = recordAuto(file, question = 1, sequence = 1)

            assertEquals(1, dao.questions.size)
            assertEquals(first.id, second.id)
        }

    @Test
    fun dataSurvivesANewRepositoryInstance() =
        runTest(dispatcher) {
            recordAuto(photo(sequence = 1, timeMs = 1_000_000L), question = 1, sequence = 1)

            val reopened = newRepository()

            assertEquals(1, reopened.history().size)
            assertEquals(ReconcileReport(), reopened.reconcile())
        }

    @Test
    fun processingStates_areUpdated() =
        runTest(dispatcher) {
            val stored = recordAuto(photo(sequence = 1, timeMs = 1_000_000L), question = 1, sequence = 1)

            assertTrue(repository.updateAiStatus(stored.id, AiStatus.PROCESSING, attempts = 1, lastError = null))
            assertTrue(repository.updateAiStatus(stored.id, AiStatus.FAILED, attempts = 2, lastError = "timeout"))
            assertFalse(repository.updateAiStatus("missing", AiStatus.FAILED, attempts = 1, lastError = null))

            val loaded = dao.question(stored.id)!!
            assertEquals(AiStatus.FAILED, loaded.aiStatus)
            assertEquals(2, loaded.attempts)
            assertEquals("timeout", loaded.lastError)
            assertEquals(9_999L, loaded.updatedAt)
        }

    @Test
    fun sameQuestionInAnotherSession_sharesTheDedupKey() =
        runTest(dispatcher) {
            val first = recordAuto(photo(sequence = 1, timeMs = 1_000_000L, session = "s1"), question = 3, sequence = 1, session = "s1")
            val other = recordAuto(photo(sequence = 1, timeMs = 2_000_000L, session = "s2"), question = 1, sequence = 1, session = "s2")
            val again = recordAuto(photo(sequence = 2, timeMs = 3_000_000L, session = "s2"), question = 3, sequence = 2, session = "s2")

            assertEquals(listOf(again.id, first.id), repository.questionsLike(first.dedupKey!!).map { it.id })
            assertEquals(listOf(other.id), repository.questionsLike(other.dedupKey!!).map { it.id })
        }

    @Test
    fun questionsInASession_comeInCaptureOrder() =
        runTest(dispatcher) {
            val b = recordAuto(photo(sequence = 2, timeMs = 2_000_000L), question = 2, sequence = 2)
            val a = recordAuto(photo(sequence = 1, timeMs = 1_000_000L), question = 1, sequence = 1)
            recordAuto(photo(sequence = 1, timeMs = 3_000_000L, session = "s2"), question = 3, sequence = 1, session = "s2")

            assertEquals(listOf(a.id, b.id), repository.questionsInSession("s1").map { it.id })
        }

    @Test
    fun history_isNewestFirst_includingManualAndPreserved() =
        runTest(dispatcher) {
            val old = photo(sequence = null, timeMs = 1_000_000L)
            recordManual(old)
            recordAuto(photo(sequence = 1, timeMs = 2_000_000L), question = 1, sequence = 1)
            recordAuto(photo(sequence = 2, timeMs = 3_000_000L), question = 2, sequence = 2)
            repository.preserve(old)

            val history = repository.history()

            assertEquals(listOf(2, 1, null), history.map { it.sequence })
            assertEquals(listOf(false, false, true), history.map { it.isPreserved })
            assertEquals(CaptureOrigin.MANUAL, history.last().origin)
            assertTrue(history.all { it.file.exists() })
        }

    @Test
    fun delete_removesFileAndRow() =
        runTest(dispatcher) {
            val file = photo(sequence = 1, timeMs = 1_000_000L)
            recordAuto(file, question = 1, sequence = 1)

            repository.delete(file)

            assertFalse(file.exists())
            assertTrue(dao.questions.isEmpty())
        }

    @Test
    fun interruptedDelete_isRepairedOnTheNextStart() =
        runTest(dispatcher) {
            val file = photo(sequence = 1, timeMs = 1_000_000L)
            recordAuto(file, question = 1, sequence = 1)
            // Crash after deleting the file, before deleting the row.
            file.delete()

            assertEquals(ReconcileReport(removed = 1), repository.reconcile())
            assertTrue(dao.questions.isEmpty())
        }

    @Test
    fun interruptedPreserve_keepsTheRowAndItsState() =
        runTest(dispatcher) {
            val file = photo(sequence = 1, timeMs = 1_000_000L)
            val stored = recordAuto(file, question = 1, sequence = 1)
            // Crash after moving the file to kept/, before repointing the row.
            captures.preserve(file)

            assertEquals(ReconcileReport(repointed = 1), repository.reconcile())
            val repaired = dao.question(stored.id)!!
            assertEquals("kept/" + file.name, repaired.imagePath)
            assertEquals(AiStatus.PENDING, repaired.aiStatus)
            assertEquals(stored.ocrText, repaired.ocrText)
        }

    @Test
    fun prune_keepsEveryPhotoTheAiIsInvolvedWith_andRemovesRowsOfDeletedPhotos() =
        runTest(dispatcher) {
            val pending = recordAuto(photo(sequence = 1, timeMs = 10_000L), question = 1, sequence = 1)
            // Answered ones too: the cleanup would also delete the row, and with it a paid answer.
            val analyzed =
                listOf(AiStatus.ANSWERED, AiStatus.NEEDS_REVIEW, AiStatus.FAILED).mapIndexed { i, status ->
                    recordAuto(photo(sequence = 2 + i, timeMs = 11_000L + i), question = 1 + i, sequence = 2 + i).also {
                        dao.saveAiResult(it.id, status, "{\"answer\":[\"C\"]}", "gpt-6-luna", 12_000L)
                    }
                }
            val others =
                (1..MAX_RECENT_CAPTURES + 3).map { i ->
                    photo(sequence = null, timeMs = 100_000L + i * 1_000L).also { recordManual(it) }
                }

            val removed = repository.prune()

            assertEquals(3, removed)
            // The oldest photos are pending for or analyzed by the AI: protected.
            assertTrue((analyzed + pending).all { captures.resolve(it.imagePath).exists() })
            assertTrue(analyzed.all { dao.question(it.id)!!.aiResponse == "{\"answer\":[\"C\"]}" })
            assertTrue(others.take(3).none { it.exists() })
            assertEquals(MAX_RECENT_CAPTURES + 4, dao.questions.size)
            assertTrue(dao.questions.all { captures.resolve(it.imagePath).exists() })
        }

    @Test
    fun discarding_isRefusedWhileTheAiIsProcessing() =
        runTest(dispatcher) {
            val file = photo(sequence = 1, timeMs = 10_000L)
            val stored = recordAuto(file, question = 1, sequence = 1)
            dao.claimForAi(stored.id, 11_000L)

            assertFalse(repository.delete(file))
            assertTrue(file.exists())
            assertEquals(AiStatus.PROCESSING, dao.question(stored.id)!!.aiStatus)

            dao.saveAiResult(stored.id, AiStatus.ANSWERED, "{}", "gpt-6-luna", 12_000L)
            assertTrue(repository.delete(file))
            assertFalse(file.exists())
            assertNull(dao.question(stored.id))
        }

    @Test
    fun earlierHistory_isImportedWithoutBeingSentToTheAi() =
        runTest(dispatcher) {
            // Photos from before the database: unlabeled, automatic, and a preserved manual one.
            File(folder.root, "captures").mkdirs()
            File(folder.root, "captures/capture_20261020_101500_123.jpg").writeBytes(byteArrayOf(1))
            photo(sequence = 4, timeMs = 2_000_000L, session = "20261022-141202")
            captures.preserve(photo(sequence = null, timeMs = 3_000_000L))

            assertEquals(ReconcileReport(imported = 3), repository.reconcile())

            val rows = dao.questions
            assertTrue(rows.all { it.aiStatus == AiStatus.NOT_REQUESTED && it.ocrText == null })
            assertEquals(setOf(CaptureOrigin.UNKNOWN, CaptureOrigin.AUTOMATIC, CaptureOrigin.MANUAL), rows.map { it.origin }.toSet())
            assertTrue("20261022-141202" in dao.sessions)
            assertEquals(listOf(false, false, true), repository.history().map { it.isPreserved }.sorted())
            // Nothing new on a second run.
            assertEquals(ReconcileReport(), repository.reconcile())
        }

    @Test
    fun aTransientWriteFailure_isRetried() =
        runTest(dispatcher) {
            dao.failingWrites = 1

            val result =
                repository.recordCapture(
                    photo(sequence = 1, timeMs = 1_000_000L),
                    CaptureOrigin.AUTOMATIC,
                    "s1",
                    1,
                    1_000_000L,
                    OcrScreens.screen(1),
                    AiStatus.PENDING,
                )

            assertTrue(result.isSuccess)
            assertEquals(1, dao.questions.size)
        }

    @Test
    fun aPersistentWriteFailure_isReported_andRecoveredLater() =
        runTest(dispatcher) {
            val file = photo(sequence = 1, timeMs = 1_000_000L)
            dao.failingWrites = 5

            val result =
                repository.recordCapture(
                    file,
                    CaptureOrigin.AUTOMATIC,
                    "s1",
                    1,
                    1_000_000L,
                    OcrScreens.screen(1),
                    AiStatus.PENDING,
                )

            assertTrue(result.isFailure)
            // Not silently lost: the photo is still there, and the next start imports it.
            assertTrue(file.exists())
            dao.failingWrites = 0
            assertEquals(ReconcileReport(imported = 1), repository.reconcile())
            assertEquals(file.name, dao.questions.single().imagePath)
        }

    @Test
    fun aPhotoWhoseRecordFailed_isNeverPrunedBeforeItIsImported() =
        runTest(dispatcher) {
            val failed = photo(sequence = 1, timeMs = 10_000L)
            dao.failingWrites = 5
            assertTrue(recordAutoResult(failed).isFailure)
            dao.failingWrites = 0
            repeat(MAX_RECENT_CAPTURES + 3) { i -> recordManual(photo(sequence = null, timeMs = 100_000L + i * 1_000L)) }

            assertEquals(3, repository.prune())

            assertTrue(failed.exists())
            assertEquals(ReconcileReport(imported = 1), repository.reconcile())
        }

    @Test
    fun aLegitimateQuestionAgainInANewSession_isRecordedAgain() =
        runTest(dispatcher) {
            val first = recordAuto(photo(sequence = 1, timeMs = 1_000_000L, session = "s1"), question = 1, sequence = 1, session = "s1")
            val second = recordAuto(photo(sequence = 1, timeMs = 2_000_000L, session = "s2"), question = 1, sequence = 1, session = "s2")

            // The key only links them; it never blocks a row.
            assertEquals(first.dedupKey, second.dedupKey)
            assertEquals(2, dao.questions.size)
            assertEquals(listOf("s1", "s2"), dao.questions.map { it.sessionId }.sortedBy { it })
        }

    private suspend fun recordAutoResult(file: File) =
        repository.recordCapture(file, CaptureOrigin.AUTOMATIC, "s1", 1, file.lastModified(), OcrScreens.screen(1), AiStatus.PENDING)

    @Test
    fun questionNumber_comesFromALineThatStartsWithIt() {
        fun numberOf(title: String) = questionNumberOf(OcrScreens.screen(OcrScreens.questions.getValue(1).copy(title = title)))

        assertEquals(1, questionNumberOf(OcrScreens.screen(1)))
        assertEquals(5, numberOf("Questão 05 — Programação"))
        assertEquals(12, numberOf("QUESTÃO Nº 12"))
        assertEquals(3, numberOf("Questao 03Sistemas"))
        // Only a mention further into a line: no number.
        assertNull(numberOf("Digite outra para receber a Questão 03"))
    }

    @Test
    fun plainText_keepsTheLines() {
        val text = plainText(OcrScreens.screen(1))

        assertTrue(text.lines().contains("Questão 01 — Sistemas Operacionais"))
        assertEquals(16, dedupKeyOf(TextFingerprint.of(OcrScreens.screen(1))).length)
    }
}
