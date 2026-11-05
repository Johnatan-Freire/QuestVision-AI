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
import com.questvision.camera.data.AiRequestOutcome
import com.questvision.camera.data.AiStatus
import com.questvision.camera.data.DirectTransactions
import com.questvision.camera.data.FakeAiRequestDao
import com.questvision.camera.data.FakeQuestionDao
import com.questvision.camera.data.QuestionEntity
import com.questvision.camera.data.QuestionRepository
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.settings.AiSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Automatic sending as the app runs it ([SettingsAutoSendPolicy]: switched on in the settings, one
 * consent when a session starts, no cap per session, the daily limit and the stop rules), with OpenAI
 * played by [FakeAiClient]. Nothing leaves the machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoSendTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val dao = FakeQuestionDao()
    private val requests = FakeAiRequestDao()
    private val captures by lazy { CaptureRepository(File(folder.root, "captures"), dispatcher) }
    private var now = 1_000L
    private val repository by lazy { QuestionRepository(dao, requests, DirectTransactions, captures, dispatcher) { now++ } }

    private val settings = InMemoryAiSettingsStore(AiSettings(firstCallConfirmed = true, requestsPerDay = 75, autoSendEnabled = true))

    /** A new coordinator, as after an app restart: only the database and the settings carry over. */
    private fun session(
        client: AiClient,
        scope: TestScope,
        id: String = "s",
        consented: Boolean = true,
    ) = AutoAnalysisCoordinator(
        QuestionAnalyzer(repository, FakeKeySource(), client, FakeImageEncoder(), settings),
        repository,
        settings,
        SettingsAutoSendPolicy(settings),
    ).apply {
        attach(scope)
        startSession(id, consented)
    }

    private var sequence = 0

    /** A new automatic capture of question [number] (1–3) in session [id], PENDING with its OCR text. */
    private suspend fun question(
        number: Int,
        id: String = "s",
    ): QuestionEntity {
        val seq = ++sequence
        val file = captures.newAutomaticCaptureFile(id, seq, nowMs = 1_000_000L + seq).apply { writeBytes(byteArrayOf(1)) }
        return repository
            .recordCapture(file, CaptureOrigin.AUTOMATIC, id, seq, file.lastModified(), OcrScreens.screen(number), AiStatus.PENDING)
            .getOrThrow()
    }

    private fun assessment(number: Int) = assessQuestion(OcrScreens.screen(number))

    @Test
    fun switchedOn_everyNewQuestionIsSent_withNoCapPerSession() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = session(client, this)

            repeat(12) { i -> auto.submit(question(1 + i % 3), assessment(1 + i % 3)) }

            assertEquals(12, client.calls)
            assertEquals(AutoPhase.WAITING, auto.state.value.phase)
            assertEquals(0, dao.questions.count { it.aiStatus == AiStatus.PENDING })
        }

    // The daily limit is counted in the database: a restart or a new session resets nothing.
    @Test
    fun theDailyLimit_stopsSending_acrossRestarts_andTheRestStayPending() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(requestsPerDay = 3)
            val client = FakeAiClient(completed())
            val first = session(client, this, id = "s1")
            (1..3).forEach { first.submit(question(it, id = "s1"), assessment(it)) }
            val fourth = question(1, id = "s1")
            first.submit(fourth, assessment(1))

            assertEquals(3, client.calls)
            assertEquals(AutoPauseReason.DAILY_LIMIT, first.state.value.pauseReason)
            assertEquals(AiStatus.PENDING, dao.question(fourth.id)!!.aiStatus)

            first.endSession()
            val afterRestart = session(client, this, id = "s2")
            assertEquals(0, afterRestart.sessionTerms().leftToday)
            afterRestart.submit(question(2, id = "s2"), assessment(2))
            assertEquals(3, client.calls)
        }

    @Test
    fun switchedOff_itOnlyCaptures_andAsksNothing() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(autoSendEnabled = false)
            val client = FakeAiClient(completed())
            val auto = session(client, this, consented = false)

            assertFalse(auto.needsConsentToStart)
            auto.submit(question(1), assessment(1))

            assertEquals(0, client.calls)
            assertEquals(1, auto.state.value.awaitingAuthorization)
        }

    @Test
    fun withoutConsent_aSessionOnlyCaptures() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = session(client, this, consented = false)

            assertTrue(auto.needsConsentToStart)
            auto.submit(question(1), assessment(1))

            assertEquals(0, client.calls)
            assertEquals(AutoPauseReason.NOT_AUTHORIZED, auto.state.value.pauseReason)
        }

    // Consent is given when a session starts: switching sending on during one that started without it sends nothing.
    @Test
    fun switchingOnMidSession_doesNotSend() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(autoSendEnabled = false)
            val client = FakeAiClient(completed())
            val auto = session(client, this, consented = false)

            settings.settings = settings.settings.copy(autoSendEnabled = true)
            auto.submit(question(1), assessment(1))

            assertEquals(0, client.calls)
        }

    @Test
    fun switchingOffMidSession_stopsSending() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = session(client, this)
            auto.submit(question(1), assessment(1))

            settings.settings = settings.settings.copy(autoSendEnabled = false)
            auto.submit(question(2), assessment(2))

            assertEquals(1, client.calls)
        }

    // The pending captures of earlier sessions are never sent.
    @Test
    fun pendingQuestionsFromEarlierSessions_areNeverSent() =
        runTest(dispatcher) {
            val backlog = (1..3).map { question(it, id = "20261026-143646") }
            val client = FakeAiClient(completed())
            val auto = session(client, this, id = "now")

            backlog.forEachIndexed { i, q -> auto.submit(q, assessment(i + 1)) }
            val fresh = question(2, id = "now")
            auto.submit(fresh, assessment(2))

            assertEquals(1, client.calls)
            assertTrue(backlog.all { dao.question(it.id)!!.aiStatus == AiStatus.PENDING })
            assertEquals(AiStatus.ANSWERED, dao.question(fresh.id)!!.aiStatus)
        }

    @Test
    fun pausing_holdsNewQuestionsBack_untilResumed() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = session(client, this)

            auto.pause()
            val held = question(1)
            auto.submit(held, assessment(1))
            assertEquals(0, client.calls)
            assertEquals(AiStatus.PENDING, dao.question(held.id)!!.aiStatus)

            auto.resume()
            assertEquals(1, client.calls)
        }

    @Test
    fun anUnknownCost_stopsSending() =
        runTest(dispatcher) {
            // A tier the price table doesn't know: the cost can't be computed.
            val client = FakeAiClient(AiCallResult.Completed(answerJson(), "resp_x", 200, SAMPLE_USAGE, serviceTier = "scale"), completed())
            val auto = session(client, this)

            auto.submit(question(1), assessment(1))
            auto.submit(question(2), assessment(2))

            assertEquals(1, client.calls)
            assertEquals(AutoPauseReason.UNKNOWN_COST, auto.state.value.pauseReason)
        }

    @Test
    fun authenticationOrCreditErrors_stopSending() =
        runTest(dispatcher) {
            for (error in listOf(AiError.AUTH, AiError.QUOTA)) {
                val client = FakeAiClient(AiCallResult.Failed(error, if (error == AiError.AUTH) 401 else 429), completed())
                val auto = session(client, this, id = "s-$error")

                auto.submit(question(1, id = "s-$error"), assessment(1))
                auto.submit(question(2, id = "s-$error"), assessment(2))

                assertEquals(error.name, 1, client.calls)
                assertEquals(AutoPauseReason.API_ERROR, auto.state.value.pauseReason)
            }
        }

    // No reply: it may have been billed, so it counts toward the daily limit and is never resent.
    @Test
    fun aLostConnection_stopsSending_countsToday_andIsNeverResent() =
        runTest(dispatcher) {
            val client = FakeAiClient(AiCallResult.Failed(AiError.TIMEOUT, null), completed())
            val auto = session(client, this)
            val first = question(1)
            auto.submit(first, assessment(1))
            auto.submit(question(2), assessment(2))

            val again = session(client, this)
            again.submit(dao.question(first.id)!!, assessment(1))

            assertEquals(1, client.calls)
            assertEquals(AutoPauseReason.CONNECTION, auto.state.value.pauseReason)
            assertEquals(
                AiRequestOutcome.UNKNOWN,
                requests.rows.values
                    .single()
                    .outcome,
            )
            assertEquals(74, again.sessionTerms().leftToday)
        }

    @Test
    fun theTerms_showTheModelAndWhatIsLeftToday() =
        runTest(dispatcher) {
            val auto = session(FakeAiClient(completed()), this)
            (1..2).forEach { auto.submit(question(it), assessment(it)) }

            val terms = auto.sessionTerms()
            assertEquals(settings.settings.primaryModel.id, terms.model)
            assertEquals(73, terms.leftToday)
        }

    @Test
    fun everyRequest_isRecorded() =
        runTest(dispatcher) {
            val auto = session(FakeAiClient(completed()), this)

            (1..3).forEach { auto.submit(question(it), assessment(it)) }

            val rows = requests.rows.values.toList()
            assertEquals(3, rows.size)
            rows.forEach { row ->
                assertEquals(AiRequestOutcome.SUCCEEDED, row.outcome)
                assertNotNull(row.responseId)
                assertNotNull(row.finishedAt)
                assertEquals(SAMPLE_USAGE.inputTokens, row.inputTokens)
                assertEquals("default", row.serviceTier)
            }
            assertEquals(3 * (412 * 0.10 + 96 * 0.50) / 1_000_000.0, auto.state.value.observedCostUsd, 1e-12)
        }
}
