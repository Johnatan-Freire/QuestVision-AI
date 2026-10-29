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
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.recognition.RecognizedText
import com.questvision.camera.settings.AiSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The automatic analysis of a monitoring session, with OpenAI played by [FakeAiClient]: nothing leaves
 * the machine and no credit is used. Numbers refer to the required test list of phase 3.4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoAnalysisCoordinatorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val dao = FakeQuestionDao()
    private val requests = FakeAiRequestDao()
    private val captures by lazy { CaptureRepository(File(folder.root, "captures"), dispatcher) }
    private var now = 1_000L
    private val repository by lazy { QuestionRepository(dao, requests, DirectTransactions, captures, dispatcher) { now++ } }
    private val settings = InMemoryAiSettingsStore(AiSettings(firstCallConfirmed = true))
    private val keys = FakeKeySource()

    private fun coordinator(
        client: AiClient,
        scope: TestScope,
        allowed: Boolean = true,
        repository: QuestionRepository = this.repository,
    ) = autoAnalysis(repository, client, settings, allowed, keys).apply {
        attach(scope)
        startSession()
    }

    /** An automatic capture of question [number] (1–3), recorded PENDING with its OCR text. */
    private suspend fun question(
        number: Int,
        sequence: Int = number,
    ): QuestionEntity {
        val file = captures.newAutomaticCaptureFile("s1", sequence, nowMs = 1_000_000L + sequence).apply { writeBytes(byteArrayOf(1)) }
        return repository
            .recordCapture(file, CaptureOrigin.AUTOMATIC, "s1", sequence, file.lastModified(), OcrScreens.screen(number), AiStatus.PENDING)
            .getOrThrow()
    }

    private fun assessment(number: Int) = assessQuestion(OcrScreens.screen(number))

    private suspend fun status(q: QuestionEntity) = dao.question(q.id)!!.aiStatus

    // 1 + 14: a new complete question is sent on its own, once, and answered.
    @Test
    fun aNewCompleteQuestion_isSentOnce_andAnswered() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed(answerJson(answer = listOf("C"), number = 2)))
            val auto = coordinator(client, this)
            val q = question(2)

            auto.submit(q, assessment(2))
            auto.submit(q, assessment(2))

            assertEquals(1, client.calls)
            assertEquals(AnalysisMode.TEXT, client.inputs.single().mode)
            assertEquals(AiStatus.ANSWERED, status(q))
            with(auto.state.value) {
                assertEquals(AutoPhase.WAITING, phase)
                assertEquals(1, requests)
                assertEquals(1, answered)
                assertEquals("DNS", lastAnswer!!.alternativeText)
                assertEquals(2, lastAnswer!!.questionNumber)
                assertEquals((412 * 0.10 + 96 * 0.50) / 1_000_000.0, observedCostUsd, 1e-12)
            }
        }

    // 7: two questions in quick succession: one request at a time, in capture order.
    @Test
    fun twoQuestionsInQuickSuccession_goOneAtATime_inOrder() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed()).apply { gate = CompletableDeferred() }
            val auto = coordinator(client, this)
            val first = question(1)
            val second = question(2)

            auto.submit(first, assessment(1))
            auto.submit(second, assessment(2))

            assertEquals(1, client.calls)
            assertEquals(AutoPhase.ANALYZING, auto.state.value.phase)
            assertEquals(1, auto.state.value.queued)
            assertEquals(AiStatus.PENDING, status(second))

            client.gate!!.complete(Unit)

            assertEquals(2, client.calls)
            assertTrue(client.inputs[0].text.contains("Questão 01"))
            assertTrue(client.inputs[1].text.contains("Questão 02"))
            assertEquals(AiStatus.ANSWERED, status(first))
            assertEquals(AiStatus.ANSWERED, status(second))
        }

    // 15: the answer arrives after the screen moved on: it is stored on its own question.
    @Test
    fun anAnswerArrivingAfterTheQuestionChanged_isStoredOnItsOwnQuestion() =
        runTest(dispatcher) {
            val client =
                FakeAiClient(
                    completed(answerJson(answer = listOf("B"), number = 1)),
                    completed(answerJson(answer = listOf("C"), number = 2)),
                ).apply { gate = CompletableDeferred() }
            val auto = coordinator(client, this)
            val first = question(1)
            val second = question(2)
            auto.submit(first, assessment(1))
            // The camera already shows question 2 while question 1's answer is on its way.
            auto.submit(second, assessment(2))

            client.gate!!.complete(Unit)

            assertTrue(dao.question(first.id)!!.aiResponse!!.contains("\"B\""))
            assertTrue(dao.question(second.id)!!.aiResponse!!.contains("\"C\""))
            assertEquals(
                second.id,
                auto.state.value.lastAnswer!!
                    .questionId,
            )
            assertEquals("chmod", alternativeText(first.ocrText, "B"))
        }

    // 9: lost connection: the outcome is unknown, so nothing is retried and the session pauses.
    @Test
    fun aLostConnection_pausesTheSession_andIsNeverRetried() =
        runTest(dispatcher) {
            val client = FakeAiClient(AiCallResult.Failed(AiError.NETWORK, null), completed())
            val auto = coordinator(client, this)
            val first = question(1)
            val second = question(2)

            auto.submit(first, assessment(1))
            auto.submit(second, assessment(2))

            assertEquals(1, client.calls)
            assertEquals(AutoPhase.PAUSED, auto.state.value.phase)
            assertEquals(AutoPauseReason.CONNECTION, auto.state.value.pauseReason)
            assertEquals(AiStatus.FAILED, status(first))
            assertEquals(AiStatus.PENDING, status(second))

            // A pause/resume of the monitoring doesn't restart sending after an ambiguous failure.
            auto.pause()
            auto.resume()
            assertEquals(1, client.calls)
            assertEquals(AutoPauseReason.CONNECTION, auto.state.value.pauseReason)

            // Only a new session sends again, and never the ambiguous question.
            auto.startSession()
            auto.submit(first, assessment(1))
            auto.submit(second, assessment(2))

            assertEquals(2, client.calls)
            assertTrue(client.inputs[1].text.contains("Questão 02"))
            assertEquals(AiStatus.FAILED, status(first))
            assertEquals(1, requests.rows.values.count { it.questionId == first.id })
        }

    // 10: an invalid answer fails that question only; the next one still goes.
    @Test
    fun anInvalidAnswer_failsThatQuestion_andTheNextStillGoes() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed(output = "{not json"), completed())
            val auto = coordinator(client, this)
            val first = question(1)
            val second = question(2)

            auto.submit(first, assessment(1))
            auto.submit(second, assessment(2))

            assertEquals(2, client.calls)
            assertEquals(AiStatus.FAILED, status(first))
            assertEquals("INVALID_RESPONSE", dao.question(first.id)!!.lastError)
            assertEquals(AiStatus.ANSWERED, status(second))
            assertEquals(2, auto.state.value.requests)
        }

    // 11: the 24-hour limit, counted in the database (earlier sessions included), pauses the session.
    @Test
    fun theDailyLimit_pausesTheSession_andSendsNothing() =
        runTest(dispatcher) {
            repeat(
                10,
            ) {
                requests.insert(
                    AiRequestEntity(questionId = "earlier-$it", model = "gpt-6-luna", mode = AnalysisMode.TEXT, startedAt = now),
                )
            }
            val client = FakeAiClient(completed())
            val auto = coordinator(client, this)
            val q = question(1)

            auto.submit(q, assessment(1))

            assertEquals(0, client.calls)
            assertEquals(AutoPauseReason.DAILY_LIMIT, auto.state.value.pauseReason)
            assertEquals(1, auto.state.value.queued)
            assertEquals(AiStatus.PENDING, status(q))
        }

    // 11: the session limit stops sending for the rest of the session, even after pause/resume.
    @Test
    fun theSessionLimit_stopsSending_untilANewSession() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(analysesPerSession = 3)
            val client = FakeAiClient(completed())
            val auto = coordinator(client, this)
            val questions = (1..3).map { question(it) } + question(1, sequence = 4) + question(2, sequence = 5)

            questions.forEachIndexed { i, q -> auto.submit(q, assessment(listOf(1, 2, 3, 1, 2)[i])) }

            assertEquals(3, client.calls)
            assertEquals(AutoPauseReason.SESSION_LIMIT, auto.state.value.pauseReason)
            // Later questions are kept for later (capture-only): queued, PENDING, nothing sent.
            assertEquals(2, auto.state.value.queued)
            assertEquals(AiStatus.PENDING, status(questions[3]))
            assertEquals(AiStatus.PENDING, status(questions[4]))

            auto.pause()
            auto.resume()
            assertEquals(3, client.calls)
            assertEquals(AutoPauseReason.SESSION_LIMIT, auto.state.value.pauseReason)
        }

    // 11: when the 24-hour window frees up, sending does not restart on its own.
    @Test
    fun theDailyLimit_doesNotLiftByItself_whenTheWindowFreesUp() =
        runTest(dispatcher) {
            repeat(
                10,
            ) {
                requests.insert(
                    AiRequestEntity(questionId = "earlier-$it", model = "gpt-6-luna", mode = AnalysisMode.TEXT, startedAt = now),
                )
            }
            val client = FakeAiClient(completed())
            val auto = coordinator(client, this)
            auto.submit(question(1), assessment(1))
            assertEquals(AutoPauseReason.DAILY_LIMIT, auto.state.value.pauseReason)

            now += AI_LIMIT_WINDOW_MS + 1
            auto.submit(question(2), assessment(2))
            auto.pause()
            auto.resume()

            assertEquals(0, client.calls)
            assertEquals(AutoPauseReason.DAILY_LIMIT, auto.state.value.pauseReason)
        }

    // Privacy: in the automatic image mode only the question's region may go; if it isn't found, nothing goes.
    @Test
    fun automaticImageMode_sendsOnlyTheQuestionsRegion_orNothing() =
        runTest(dispatcher) {
            val images = FakeImageEncoder(regionFound = false)
            val client = FakeAiClient(completed())
            val auto =
                AutoAnalysisCoordinator(
                    QuestionAnalyzer(repository, keys, client, images, settings),
                    repository,
                    settings,
                    FixedAutoSendPolicy(true),
                ).apply {
                    attach(this@runTest)
                    startSession()
                }
            val q = question(1)
            val asImage = assessment(1).copy(visualContent = true)

            auto.submit(q, asImage)

            assertEquals(listOf(RegionPolicy.QUESTION_ONLY), images.regions)
            assertEquals(0, client.calls)
            assertEquals(AiStatus.PENDING, status(q))
            assertEquals(0, requests.rows.size)
            assertTrue(auto.state.value.lastEvent is AutoEvent.Skipped)
        }

    // Never two requests at once: a manual analysis in flight makes the automatic one wait its turn.
    @Test
    fun aManualAnalysisInFlight_makesTheAutomaticOneWait() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed()).apply { gate = CompletableDeferred() }
            val analyzer = QuestionAnalyzer(repository, keys, client, FakeImageEncoder(), settings)
            val auto =
                AutoAnalysisCoordinator(analyzer, repository, settings, FixedAutoSendPolicy(true)).apply {
                    attach(this@runTest)
                    startSession()
                }
            val manual = question(1)
            val automatic = question(2)
            val manualRun = launch { analyzer.analyze(manual.id, AnalysisMode.TEXT) }

            auto.submit(automatic, assessment(2))

            assertEquals(1, client.calls)
            assertEquals(AiStatus.PENDING, status(automatic))
            assertEquals(1, auto.state.value.queued)

            client.gate!!.complete(Unit)
            manualRun.join()
            // The next capture of the session gives it its turn.
            auto.submit(question(3), assessment(3))

            assertEquals(3, client.calls)
            assertEquals(AiStatus.ANSWERED, status(automatic))
        }

    // 12: pause and resume.
    @Test
    fun pausing_holdsTheQueue_andResumingSendsIt() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = coordinator(client, this)
            auto.pause()
            val q = question(1)

            auto.submit(q, assessment(1))

            assertEquals(0, client.calls)
            assertEquals(AutoPauseReason.USER, auto.state.value.pauseReason)
            assertEquals(1, auto.state.value.queued)

            auto.resume()

            assertEquals(1, client.calls)
            assertEquals(AiStatus.ANSWERED, status(q))
            assertNull(auto.state.value.pauseReason)
        }

    // 6: a question with a chart goes as the photo, naming the question for the model.
    @Test
    fun aQuestionWithAChart_goesAsTheImage() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed(answerJson(number = 9)))
            val auto = coordinator(client, this)
            val screen =
                RecognizedText(
                    listOf(
                        "Questão 09 — Estatística",
                        "Observe o gráfico abaixo e indique o mês de maior venda.",
                        "A) Jan",
                        "B) Fev",
                        "C) Mar",
                        "Pergunte ao ChatGPT",
                    ).map { OcrScreens.words(it) },
                )
            val file = captures.newAutomaticCaptureFile("s1", 9, nowMs = 1_000_009L).apply { writeBytes(byteArrayOf(1)) }
            val q =
                repository
                    .recordCapture(file, CaptureOrigin.AUTOMATIC, "s1", 9, file.lastModified(), screen, AiStatus.PENDING)
                    .getOrThrow()

            auto.submit(q, assessQuestion(screen))

            assertEquals(AnalysisMode.IMAGE, client.inputs.single().mode)
            assertTrue(
                client.inputs
                    .single()
                    .text
                    .endsWith("Responda somente a Questão 9."),
            )
            assertEquals(AiStatus.ANSWERED, status(q))
        }

    // 8: the app dies during an analysis: after the restart nothing is sent again on its own.
    @Test
    fun aRestartDuringAnAnalysis_isNeverResentAutomatically() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed()).apply { gate = CompletableDeferred() }
            val q = question(1)
            coordinator(client, this).submit(q, assessment(1))
            assertEquals(AiStatus.PROCESSING, status(q))

            // Next start: a new repository and coordinator over the same database.
            val restarted = QuestionRepository(dao, requests, DirectTransactions, captures, dispatcher) { now++ }
            restarted.reconcile()
            val auto = coordinator(client, this, repository = restarted)
            auto.submit(dao.question(q.id)!!, assessment(1))

            assertEquals(1, client.calls)
            assertEquals(AiStatus.FAILED, status(q))
            assertEquals(
                AiRequestOutcome.INTERRUPTED,
                requests.rows.values
                    .single()
                    .outcome,
            )
            assertTrue(auto.state.value.lastEvent is AutoEvent.Skipped)
            client.gate!!.cancel()
        }

    // 2 + 14: a question the AI already answered (manually, or in another session) is not sent again.
    @Test
    fun aQuestionAlreadyAnalyzed_isNeverSentAgain() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = coordinator(client, this)
            val earlier = question(2)
            auto.submit(earlier, assessment(2))
            assertEquals(1, client.calls)

            // The same question captured again later, with a different clock and some OCR noise.
            val again = OcrScreens.screen(OcrScreens.questions.getValue(2), clock = "18:42:11")
            val match =
                auto.findSentToAi(
                    com.questvision.camera.data
                        .plainText(again),
                )

            assertEquals(earlier.id, match!!.id)
            assertNull(
                auto.findSentToAi(
                    com.questvision.camera.data
                        .plainText(OcrScreens.screen(3)),
                ),
            )
            // Also when it was answered elsewhere while queued here.
            val other = question(3)
            dao.saveAiResult(other.id, AiStatus.ANSWERED, "{}", "gpt-6-luna", now)
            auto.submit(other, assessment(3))
            assertEquals(1, client.calls)
        }

    // 3, 5: an incomplete question is never queued.
    @Test
    fun anIncompleteQuestion_isNotQueued() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = coordinator(client, this)
            val q = question(1)
            val incomplete = assessQuestion(OcrScreens.screen(1).let { it.copy(lines = it.lines.dropLast(1)) })

            auto.submit(q, incomplete)
            auto.reportIncomplete(incomplete)

            assertEquals(0, client.calls)
            assertEquals(0, auto.state.value.queued)
            assertTrue(auto.state.value.lastEvent is AutoEvent.Incomplete)
        }

    @Test
    fun withoutAuthorizationForRealRequests_nothingIsSent() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = coordinator(client, this, allowed = false)
            val q = question(1)

            auto.submit(q, assessment(1))

            assertEquals(0, client.calls)
            assertEquals(AiStatus.PENDING, status(q))
            assertEquals(1, auto.state.value.awaitingAuthorization)
            assertTrue(auto.state.value.lastEvent is AutoEvent.AwaitingAuthorization)
        }

    @Test
    fun withoutAKey_theSessionPauses_andKeepsTheQuestionQueued() =
        runTest(dispatcher) {
            keys.key = null
            val client = FakeAiClient(completed())
            val auto = coordinator(client, this)
            val q = question(1)

            auto.submit(q, assessment(1))

            assertEquals(0, client.calls)
            assertEquals(AutoPauseReason.NO_KEY, auto.state.value.pauseReason)
            assertEquals(1, auto.state.value.queued)
            assertEquals(AiStatus.PENDING, status(q))
        }

    @Test
    fun endingTheSession_dropsTheQueue_butTheRequestInFlightCompletes() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed()).apply { gate = CompletableDeferred() }
            val auto = coordinator(client, this)
            val first = question(1)
            val second = question(2)
            auto.submit(first, assessment(1))
            auto.submit(second, assessment(2))

            auto.endSession()
            client.gate!!.complete(Unit)

            assertEquals(1, client.calls)
            assertEquals(AiStatus.ANSWERED, status(first))
            assertEquals(AiStatus.PENDING, status(second))
            assertEquals(AutoPhase.IDLE, auto.state.value.phase)
        }

    @Test
    fun errorsThatTheNextRequestWouldRepeat_pauseTheSession() {
        assertEquals(AutoPauseReason.API_ERROR, pauseReasonFor(AiError.QUOTA))
        assertEquals(AutoPauseReason.API_ERROR, pauseReasonFor(AiError.AUTH))
        assertEquals(AutoPauseReason.API_ERROR, pauseReasonFor(AiError.RATE_LIMIT))
        assertEquals(AutoPauseReason.CONNECTION, pauseReasonFor(AiError.TIMEOUT))
        assertNull(pauseReasonFor(AiError.SERVER))
        assertNull(pauseReasonFor(AiError.INVALID_RESPONSE))
    }
}
