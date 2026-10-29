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

import com.questvision.camera.CaptureOrigin
import com.questvision.camera.CaptureRepository
import com.questvision.camera.MAX_RECENT_CAPTURES
import com.questvision.camera.data.AI_LIMIT_WINDOW_MS
import com.questvision.camera.data.AiRequestEntity
import com.questvision.camera.data.AiRequestOutcome
import com.questvision.camera.data.AiStatus
import com.questvision.camera.data.AnalysisMode
import com.questvision.camera.data.DirectTransactions
import com.questvision.camera.data.FakeAiRequestDao
import com.questvision.camera.data.FakeQuestionDao
import com.questvision.camera.data.QuestionEntity
import com.questvision.camera.data.QuestionRepository
import com.questvision.camera.data.ReconcileReport
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.settings.AiSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The manual analysis flow end to end with OpenAI played by [FakeAiClient]: nothing leaves the
 * machine and no credit is used.
 */
class QuestionAnalyzerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val dao = FakeQuestionDao()
    private val requests = FakeAiRequestDao()
    private val captures by lazy { CaptureRepository(File(folder.root, "captures"), dispatcher) }
    private var now = 1_000L
    private val repository by lazy { QuestionRepository(dao, requests, DirectTransactions, captures, dispatcher) { now++ } }
    private val keys = FakeKeySource()
    private val images = FakeImageEncoder()
    private val settings = InMemoryAiSettingsStore()

    private fun analyzer(client: AiClient) = QuestionAnalyzer(repository, keys, client, images, settings)

    /** An automatic capture of question [number], recorded PENDING with its OCR text. */
    private suspend fun question(
        number: Int = 2,
        sequence: Int = 1,
        ocr: Boolean = true,
    ): QuestionEntity {
        val file =
            captures.newAutomaticCaptureFile("s1", sequence, nowMs = 1_000_000L + sequence).apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
        return repository
            .recordCapture(
                file,
                CaptureOrigin.AUTOMATIC,
                "s1",
                sequence,
                file.lastModified(),
                if (ocr) OcrScreens.screen(number) else null,
                AiStatus.PENDING,
            ).getOrThrow()
    }

    private suspend fun row(q: QuestionEntity) = dao.question(q.id)!!

    // 1, 3, 11: text mode, structured answer, persisted.
    @Test
    fun textQuestion_isAnswered_andTheResultPersisted() =
        runTest(dispatcher) {
            val q = question(number = 2)
            val client = FakeAiClient(completed())

            val result = analyzer(client).analyze(q.id, AnalysisMode.TEXT)

            result as AnalysisResult.Answered
            assertEquals(AiStatus.ANSWERED, result.status)
            assertEquals(listOf("C"), result.answer.answer)
            // Only the question block went out: no chrome, no image.
            val sent = client.inputs.single()
            assertEquals(AnalysisMode.TEXT, sent.mode)
            assertTrue(sent.text.contains("Questão 02 — Redes de Computadores"))
            assertTrue(sent.text.contains("E) SMTP"))
            assertFalse(sent.text.contains("ChatGPT"))
            assertNull(sent.imageDataUrl)
            assertEquals("gpt-6-luna", client.options.single().model)
            assertEquals(AiSettings().maxOutputTokens, client.options.single().maxOutputTokens)

            val stored = row(q)
            assertEquals(AiStatus.ANSWERED, stored.aiStatus)
            assertEquals("gpt-6-luna", stored.aiModel)
            assertNotNull(stored.aiAnsweredAt)
            assertEquals(1, stored.attempts)
            assertNull(stored.lastError)
            val answer = storedAiAnswer(stored.aiResponse)!!
            assertEquals(listOf("C"), answer.answer)
            assertEquals(AnalysisMode.TEXT, answer.mode)
            assertEquals("O DNS traduz nomes de domínio em endereços IP.", answer.explanation)

            val log = requests.rows.values.single()
            assertEquals(AiRequestOutcome.SUCCEEDED, log.outcome)
            assertEquals(AnalysisMode.TEXT, log.mode)
            assertEquals("resp_test_1", log.responseId)
            assertEquals(412, log.inputTokens)
            assertEquals(96, log.outputTokens)
            assertEquals(40, log.reasoningTokens)
            assertEquals(0, log.cacheWriteTokens)
            assertEquals("default", log.serviceTier)
            assertEquals(200, log.httpStatus)
        }

    // 2: image mode.
    @Test
    fun imageQuestion_sendsThePhoto_downscaledToTheSetting() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(imageMaxDimension = 1_600)
            val q = question(ocr = false)
            val client = FakeAiClient(completed())

            val result = analyzer(client).analyze(q.id, AnalysisMode.IMAGE)

            assertTrue(result is AnalysisResult.Answered)
            val sent = client.inputs.single()
            assertEquals(AnalysisMode.IMAGE, sent.mode)
            assertEquals("data:image/jpeg;base64,/9j/AAAA", sent.imageDataUrl)
            assertEquals(captures.resolve(q.imagePath) to 1_600, images.requests.single())
            assertEquals(AnalysisMode.IMAGE, storedAiAnswer(row(q).aiResponse)!!.mode)
        }

    @Test
    fun uncertainAnswer_isStoredForReview() =
        runTest(dispatcher) {
            val q = question()

            val result =
                analyzer(
                    FakeAiClient(completed(answerJson(type = "cannot_determine", answer = emptyList()))),
                ).analyze(q.id, AnalysisMode.TEXT)

            assertEquals(AiStatus.NEEDS_REVIEW, (result as AnalysisResult.Answered).status)
            assertEquals(AiStatus.NEEDS_REVIEW, row(q).aiStatus)
        }

    // 4: invalid or incomplete responses.
    @Test
    fun invalidOrIncompleteResponse_failsWithoutLosingAnything() =
        runTest(dispatcher) {
            val q = question()
            val invalid = FakeAiClient(completed("{\"answer_type\":\"single\"}"))

            assertEquals(AnalysisResult.Failed(AiError.INVALID_RESPONSE), analyzer(invalid).analyze(q.id, AnalysisMode.TEXT))
            assertEquals(AiStatus.FAILED, row(q).aiStatus)
            assertEquals("INVALID_RESPONSE", row(q).lastError)
            // Billed tokens are still recorded.
            assertEquals(
                412,
                requests.rows.values
                    .single()
                    .inputTokens,
            )

            val incomplete = FakeAiClient(AiCallResult.Failed(AiError.INCOMPLETE_OUTPUT, 200, "resp_x", SAMPLE_USAGE))
            assertEquals(AnalysisResult.Failed(AiError.INCOMPLETE_OUTPUT), analyzer(incomplete).analyze(q.id, AnalysisMode.TEXT))
            assertEquals("INCOMPLETE_OUTPUT", row(q).lastError)
            assertEquals(2, row(q).attempts)
            assertTrue(captures.resolve(q.imagePath).exists())
            assertNotNull(row(q).ocrText)
        }

    // 5: no key.
    @Test
    fun withoutAKey_nothingIsSentNorChanged() =
        runTest(dispatcher) {
            val q = question()
            keys.key = null
            val client = FakeAiClient(completed())

            val result = analyzer(client).analyze(q.id, AnalysisMode.TEXT)

            assertEquals(AnalysisResult.Rejected(AnalysisRejection.NO_KEY), result)
            assertEquals(0, client.calls)
            assertEquals(q, row(q))
            assertTrue(requests.rows.isEmpty())
        }

    // 6, 7, 8, 9: errors are recorded, never retried, and keep the data.
    @Test
    fun apiAndNetworkErrors_failTheAttempt_once_andKeepEverything() =
        runTest(dispatcher) {
            val cases =
                listOf(
                    AiCallResult.Failed(AiError.AUTH, 401) to AiError.AUTH,
                    AiCallResult.Failed(AiError.RATE_LIMIT, 429, retryAfterSeconds = 20) to AiError.RATE_LIMIT,
                    AiCallResult.Failed(AiError.QUOTA, 429) to AiError.QUOTA,
                    AiCallResult.Failed(AiError.NETWORK) to AiError.NETWORK,
                    AiCallResult.Failed(AiError.TIMEOUT) to AiError.TIMEOUT,
                    AiCallResult.Failed(AiError.SERVER, 503) to AiError.SERVER,
                )
            for ((index, case) in cases.withIndex()) {
                val (failure, expected) = case
                val q = question(sequence = index + 1)
                val client = FakeAiClient(failure)

                val result = analyzer(client).analyze(q.id, AnalysisMode.TEXT)

                assertEquals(AnalysisResult.Failed(expected), result)
                assertEquals("no automatic retry", 1, client.calls)
                val stored = row(q)
                assertEquals(AiStatus.FAILED, stored.aiStatus)
                assertEquals(expected.name, stored.lastError)
                assertEquals(q.ocrText, stored.ocrText)
                assertTrue(captures.resolve(q.imagePath).exists())
                val log = requests.requestsFor(q.id).single()
                // OpenAI answered with an error: a known failure. No answer at all: unknown, maybe billed.
                assertEquals(if (expected.outcomeUnknown) AiRequestOutcome.UNKNOWN else AiRequestOutcome.FAILED, log.outcome)
                assertEquals(expected.name, log.errorCode)
                assertEquals(failure.httpStatus, log.httpStatus)
            }
        }

    // 15: a failure after an earlier answer keeps that answer; FAILED photos survive retention.
    @Test
    fun aFailedRetry_keepsTheEarlierAnswer_andThePhotoSurvivesRetention() =
        runTest(dispatcher) {
            val q = question(sequence = 1)
            val client = FakeAiClient(completed(), AiCallResult.Failed(AiError.NETWORK))
            val analyzer = analyzer(client)
            analyzer.analyze(q.id, AnalysisMode.TEXT)

            analyzer.analyze(q.id, AnalysisMode.TEXT)

            val stored = row(q)
            assertEquals(AiStatus.FAILED, stored.aiStatus)
            assertEquals(listOf("C"), storedAiAnswer(stored.aiResponse)!!.answer)
            // Plenty of newer manual photos: the failed question is still protected.
            repeat(MAX_RECENT_CAPTURES + 2) { i ->
                val manual = captures.newManualCaptureFile(nowMs = 2_000_000L + i).apply { writeBytes(byteArrayOf(1)) }
                repository.recordCapture(manual, CaptureOrigin.MANUAL, null, null, manual.lastModified(), null, AiStatus.NOT_REQUESTED)
            }
            captures.resolve(q.imagePath).setLastModified(1_000L)
            repository.prune()
            assertTrue(captures.resolve(q.imagePath).exists())
            assertNotNull(dao.question(q.id))
        }

    // 10: cancellation.
    @Test
    fun cancelling_recordsTheAttemptAsCancelled_andKeepsEverything() =
        runTest(dispatcher) {
            val q = question()
            val client = FakeAiClient(completed()).apply { gate = CompletableDeferred() }

            val job = launch { analyzer(client).analyze(q.id, AnalysisMode.TEXT) }
            assertEquals(AiStatus.PROCESSING, row(q).aiStatus)
            job.cancel()

            val stored = row(q)
            assertEquals(AiStatus.FAILED, stored.aiStatus)
            assertEquals("CANCELLED", stored.lastError)
            assertEquals(
                AiRequestOutcome.CANCELLED,
                requests.rows.values
                    .single()
                    .outcome,
            )
            assertTrue(captures.resolve(q.imagePath).exists())
        }

    // 12: restart while processing.
    @Test
    fun aRestartDuringProcessing_failsItAsInterrupted_withoutSendingAgain() =
        runTest(dispatcher) {
            val q = question()
            val client = FakeAiClient(completed()).apply { gate = CompletableDeferred() }
            // The app dies with the request in flight: the job never finishes.
            launch { analyzer(client).analyze(q.id, AnalysisMode.TEXT) }
            assertEquals(AiStatus.PROCESSING, row(q).aiStatus)

            // Next start: a new repository over the same database runs reconcile.
            val restarted = QuestionRepository(dao, requests, DirectTransactions, captures, dispatcher) { now++ }
            val report = restarted.reconcile()

            assertEquals(ReconcileReport(interrupted = 1), report)
            assertEquals(AiStatus.FAILED, row(q).aiStatus)
            assertEquals("INTERRUPTED", row(q).lastError)
            assertEquals(
                AiRequestOutcome.INTERRUPTED,
                requests.rows.values
                    .single()
                    .outcome,
            )
            assertEquals(1, client.calls)
            client.gate!!.cancel()
        }

    // 12: recovery runs once per process, so it never closes a request this process is waiting for.
    @Test
    fun aLaterReconcileInTheSameProcess_leavesTheRequestInFlightAlone() =
        runTest(dispatcher) {
            repository.reconcile()
            val q = question()
            val client = FakeAiClient(completed()).apply { gate = CompletableDeferred() }
            val job = launch { analyzer(client).analyze(q.id, AnalysisMode.TEXT) }
            assertEquals(AiStatus.PROCESSING, row(q).aiStatus)

            // E.g. the camera screen recreated while the panel waits for the answer.
            assertEquals(0, repository.reconcile().interrupted)
            assertEquals(AiStatus.PROCESSING, row(q).aiStatus)
            client.gate!!.complete(Unit)
            job.join()

            assertEquals(AiStatus.ANSWERED, row(q).aiStatus)
            assertEquals(
                AiRequestOutcome.SUCCEEDED,
                requests.rows.values
                    .single()
                    .outcome,
            )
        }

    // 12: the first claim of a process closes what an earlier process left in flight before claiming.
    @Test
    fun theFirstClaimAfterARestart_closesInterruptedAttemptsFirst_withoutResending() =
        runTest(dispatcher) {
            val left = question(sequence = 1)
            val other = question(sequence = 2)
            dao.claimForAi(left.id, 1L)
            requests.insert(AiRequestEntity(questionId = left.id, model = "gpt-6-luna", mode = AnalysisMode.TEXT, startedAt = 1L))
            val client = FakeAiClient(completed())

            // No reconcile yet: analyzing another question still closes the stale attempt first.
            analyzer(client).analyze(other.id, AnalysisMode.TEXT)

            assertEquals(AiStatus.FAILED, row(left).aiStatus)
            assertEquals("INTERRUPTED", row(left).lastError)
            assertEquals(AiRequestOutcome.INTERRUPTED, requests.requestsFor(left.id).single().outcome)
            assertEquals(listOf(AnalysisMode.TEXT), client.inputs.map { it.mode })
            assertEquals(1, client.calls)
        }

    // 13: duplicates.
    @Test
    fun theSameQuestion_isNeverSentTwiceAtOnce() =
        runTest(dispatcher) {
            val q = question()
            val client = FakeAiClient(completed()).apply { gate = CompletableDeferred() }
            val analyzer = analyzer(client)

            val first = launch { analyzer.analyze(q.id, AnalysisMode.TEXT) }
            val second = analyzer.analyze(q.id, AnalysisMode.IMAGE)
            // Another analyzer instance (e.g. a second screen) is stopped by the database claim.
            val third = analyzer(client).analyze(q.id, AnalysisMode.TEXT)
            client.gate!!.complete(Unit)
            first.join()

            assertEquals(AnalysisResult.Rejected(AnalysisRejection.ALREADY_PROCESSING), second)
            assertEquals(AnalysisResult.Rejected(AnalysisRejection.ALREADY_PROCESSING), third)
            assertEquals(1, client.calls)
            assertEquals(1, row(q).attempts)
            assertEquals(AiStatus.ANSWERED, row(q).aiStatus)
        }

    @Test
    fun theRequestLimit_countsFailures_andSurvivesARestart() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(requestsPerDay = 5)
            val client = FakeAiClient(AiCallResult.Failed(AiError.NETWORK))
            val questions = (1..6).map { question(sequence = it) }

            val results = questions.take(5).map { analyzer(client).analyze(it.id, AnalysisMode.TEXT) }
            assertTrue(results.all { it == AnalysisResult.Failed(AiError.NETWORK) })

            // A new process (repository and analyzer) reads the same log: restarting resets nothing.
            val restarted = QuestionRepository(dao, requests, DirectTransactions, captures, dispatcher) { now++ }
            restarted.reconcile()
            val afterRestart = QuestionAnalyzer(restarted, keys, client, images, settings).analyze(questions.last().id, AnalysisMode.TEXT)

            assertEquals(AnalysisResult.Rejected(AnalysisRejection.REQUEST_LIMIT), afterRestart)
            assertEquals(5, client.calls)
            assertEquals(AiStatus.PENDING, row(questions.last()).aiStatus)
            assertEquals(5, restarted.aiRequestsInLimitWindow())
        }

    @Test
    fun theRequestLimit_isA24HourWindow_andDeletingQuestionsFreesNothing() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(requestsPerDay = 5)
            val client = FakeAiClient(completed())
            val questions = (1..6).map { question(sequence = it) }
            questions.take(5).forEach { analyzer(client).analyze(it.id, AnalysisMode.TEXT) }
            questions.take(5).forEach { dao.deleteQuestion(it.id) }

            assertEquals(
                AnalysisResult.Rejected(AnalysisRejection.REQUEST_LIMIT),
                analyzer(client).analyze(questions.last().id, AnalysisMode.TEXT),
            )

            now += AI_LIMIT_WINDOW_MS
            assertTrue(analyzer(client).analyze(questions.last().id, AnalysisMode.TEXT) is AnalysisResult.Answered)
            assertEquals(6, client.calls)
        }

    @Test
    fun incompleteOcr_orUnreadablePhoto_sendNothing() =
        runTest(dispatcher) {
            val noText = question(ocr = false)
            val client = FakeAiClient(completed())
            images.image = null

            assertEquals(
                AnalysisResult.Rejected(AnalysisRejection.INSUFFICIENT_TEXT),
                analyzer(client).analyze(noText.id, AnalysisMode.TEXT),
            )
            assertEquals(
                AnalysisResult.Rejected(AnalysisRejection.IMAGE_UNAVAILABLE),
                analyzer(client).analyze(noText.id, AnalysisMode.IMAGE),
            )
            assertEquals(AnalysisResult.Rejected(AnalysisRejection.NOT_RECORDED), analyzer(client).analyze("missing", AnalysisMode.TEXT))
            assertEquals(0, client.calls)
            assertEquals(noText, row(noText))
        }

    // Phase 3.5, critical 1: the whole photo is never sent, not even when the user picked the image mode.
    @Test
    fun manualImageMode_withoutTheQuestionsRegion_sendsNothing() =
        runTest(dispatcher) {
            val q = question()
            val client = FakeAiClient(completed())
            images.regionFound = false

            val result = analyzer(client).analyze(q.id, AnalysisMode.IMAGE)

            assertEquals(AnalysisResult.Rejected(AnalysisRejection.QUESTION_REGION_NOT_FOUND), result)
            assertEquals(0, client.calls)
            assertTrue(requests.rows.isEmpty())
            assertEquals(AiStatus.PENDING, row(q).aiStatus)
        }

    // Phase 3.5, critical 3: a cancellation arriving just after the (possibly billed) answer never loses it.
    @Test
    fun aCancellationRightAfterTheAnswerArrived_stillStoresIt() =
        runTest(dispatcher) {
            val q = question()
            val client =
                object : AiClient {
                    override suspend fun send(
                        key: com.questvision.camera.settings.ApiKey,
                        input: AiInput,
                        options: AiRequestOptions,
                    ): AiCallResult {
                        // The user taps "Cancel" right as the response comes in.
                        currentCoroutineContext().job.cancel()
                        return completed()
                    }
                }

            launch { analyzer(client).analyze(q.id, AnalysisMode.TEXT) }.join()

            assertEquals(AiStatus.ANSWERED, row(q).aiStatus)
            assertEquals(
                AiRequestOutcome.SUCCEEDED,
                requests.rows.values
                    .single()
                    .outcome,
            )
        }

    // Phase 3.5, critical 4: no answer is "unknown", never a plain failure: it may have been billed.
    @Test
    fun aLostConnection_isLoggedAsUnknown_notAsAFailure() =
        runTest(dispatcher) {
            val q = question()
            val client = FakeAiClient(AiCallResult.Failed(AiError.TIMEOUT, null))

            analyzer(client).analyze(q.id, AnalysisMode.TEXT)

            assertEquals(
                AiRequestOutcome.UNKNOWN,
                requests.rows.values
                    .single()
                    .outcome,
            )
            assertEquals(AiStatus.FAILED, row(q).aiStatus)
            assertEquals(1, client.calls)
        }

    // Phase 3.5, item 9: the size of the crop actually sent, never its content.
    @Test
    fun theSentImagesSize_isLogged() =
        runTest(dispatcher) {
            val q = question()
            images.image = EncodedImage("data:image/jpeg;base64,/9j/AAAA", 1165, 737, cropped = true, byteCount = 148_553)

            analyzer(FakeAiClient(completed())).analyze(q.id, AnalysisMode.IMAGE)

            val log = requests.rows.values.single()
            assertEquals(1165, log.imageWidth)
            assertEquals(737, log.imageHeight)
            assertEquals(148_553, log.imageBytes)
        }

    @Test
    fun theKey_isUsedOnlyForTheRequest_andNeverStored() =
        runTest(dispatcher) {
            val q = question()
            val client = FakeAiClient(completed())

            analyzer(client).analyze(q.id, AnalysisMode.TEXT)

            assertEquals(TEST_KEY, client.keys.single())
            val everythingStored = dao.questions.toString() + requests.rows.values.toString()
            assertFalse(everythingStored.contains(TEST_KEY.value))
        }
}
