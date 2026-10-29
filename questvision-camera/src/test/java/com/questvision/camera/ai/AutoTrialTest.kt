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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 3.6: the supervised trial of real automatic requests ([ArmableTrialPolicy], armed, in the
 * trial build) with OpenAI played by [FakeAiClient]. Nothing leaves the machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoTrialTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val dao = FakeQuestionDao()
    private val requests = FakeAiRequestDao()
    private val captures by lazy { CaptureRepository(File(folder.root, "captures"), dispatcher) }
    private var now = 1_000L
    private val repository by lazy { QuestionRepository(dao, requests, DirectTransactions, captures, dispatcher) { now++ } }

    // Armed; the user's settings are higher than the trial's caps on purpose.
    private val settings =
        InMemoryAiSettingsStore(
            AiSettings(firstCallConfirmed = true, analysesPerSession = 10, requestsPerDay = 50).armingTrial(TRIAL_ID, nowMs = 1L),
        )

    /** A new coordinator, as after an app restart: only the database and the settings carry over. */
    private fun trial(
        client: AiClient,
        scope: TestScope,
        session: String = "trial",
        consented: Boolean = true,
        trialId: Int = TRIAL_ID,
    ) = AutoAnalysisCoordinator(
        QuestionAnalyzer(repository, FakeKeySource(), client, FakeImageEncoder(), settings),
        repository,
        settings,
        ArmableTrialPolicy(settings, trialId),
    ).apply {
        attach(scope)
        startSession(session, consented)
    }

    private var sequence = 0

    /** A new automatic capture of question [number] (1–3) in [session], PENDING with its OCR text. */
    private suspend fun question(
        number: Int,
        session: String = "trial",
    ): QuestionEntity {
        val seq = ++sequence
        val file = captures.newAutomaticCaptureFile(session, seq, nowMs = 1_000_000L + seq).apply { writeBytes(byteArrayOf(1)) }
        return repository
            .recordCapture(file, CaptureOrigin.AUTOMATIC, session, seq, file.lastModified(), OcrScreens.screen(number), AiStatus.PENDING)
            .getOrThrow()
    }

    private fun assessment(number: Int) = assessQuestion(OcrScreens.screen(number))

    @Test
    fun theTrial_sendsAtMostThreePerSession_whateverTheSetting() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = trial(client, this)

            repeat(5) { i -> auto.submit(question(1 + i % 3), assessment(1 + i % 3)) }

            assertEquals(3, client.calls)
            assertEquals(3, auto.state.value.sessionLimit)
            assertEquals(AutoPauseReason.TRIAL_USED_UP, auto.state.value.pauseReason)
            assertEquals(2, dao.questions.count { it.aiStatus == AiStatus.PENDING })
        }

    // The total is counted in the database: a restart (new coordinator) or a new session resets nothing.
    @Test
    fun theTotalOfThree_survivesRestartsAndNewSessions_andTheFourthStaysPending() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val first = trial(client, this, session = "s1")
            (1..2).forEach { first.submit(question(it, session = "s1"), assessment(it)) }
            first.endSession()

            val afterRestart = trial(client, this, session = "s2")
            assertEquals(1, afterRestart.sessionTerms().maxRequests)
            afterRestart.submit(question(3, session = "s2"), assessment(3))
            val fourth = question(1, session = "s2")
            afterRestart.submit(fourth, assessment(1))

            assertEquals(3, client.calls)
            assertEquals(AiStatus.PENDING, dao.question(fourth.id)!!.aiStatus)
            assertEquals(AutoPauseReason.TRIAL_USED_UP, afterRestart.state.value.pauseReason)
            // It disarmed itself: the next session needs no consent and sends nothing.
            assertFalse(settings.settings.autoTrialArmed)
            val later = trial(client, this, session = "s3", consented = false)
            assertFalse(later.needsConsentToStart)
            later.submit(question(2, session = "s3"), assessment(2))
            assertEquals(3, client.calls)
        }

    @Test
    fun pauseAndResume_neverGetPastTheTotal() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = trial(client, this)
            (1..3).forEach { auto.submit(question(it), assessment(it)) }

            auto.pause()
            auto.resume()
            auto.submit(question(1), assessment(1))

            assertEquals(3, client.calls)
            assertEquals(AutoPauseReason.TRIAL_USED_UP, auto.state.value.pauseReason)
        }

    @Test
    fun pausing_holdsNewQuestionsBack_untilResumed() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = trial(client, this)

            auto.pause()
            val held = question(1)
            auto.submit(held, assessment(1))
            assertEquals(0, client.calls)
            assertEquals(AiStatus.PENDING, dao.question(held.id)!!.aiStatus)

            auto.resume()
            assertEquals(1, client.calls)
        }

    // A request whose outcome is unknown may have been billed: it counts, and is never resent.
    @Test
    fun anUnknownOutcome_countsTowardTheTotal() =
        runTest(dispatcher) {
            val client = FakeAiClient(AiCallResult.Failed(AiError.TIMEOUT, null), completed())
            val first = trial(client, this, session = "s1")
            val lost = question(1, session = "s1")
            first.submit(lost, assessment(1))
            assertEquals(AutoPauseReason.CONNECTION, first.state.value.pauseReason)

            val next = trial(client, this, session = "s2")
            assertEquals(2, next.sessionTerms().maxRequests)
            (1..3).forEach { next.submit(question(it, session = "s2"), assessment(it)) }

            assertEquals(3, client.calls)
            assertEquals(AiStatus.FAILED, dao.question(lost.id)!!.aiStatus)
            assertEquals(1, requests.rows.values.count { it.outcome == AiRequestOutcome.UNKNOWN })
        }

    // Outside the trial build, arming does nothing (and the switch isn't even shown).
    @Test
    fun otherBuilds_neverSend_evenIfArmed() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = trial(client, this, consented = false, trialId = 0)

            assertFalse(auto.needsConsentToStart)
            auto.submit(question(1), assessment(1))

            assertEquals(0, client.calls)
            assertEquals(1, auto.state.value.awaitingAuthorization)
        }

    @Test
    fun theTrialBuild_sendsNothing_untilArmed() =
        runTest(dispatcher) {
            settings.settings = AiSettings(firstCallConfirmed = true)
            val client = FakeAiClient(completed())
            val auto = trial(client, this, consented = false)

            assertFalse(auto.needsConsentToStart)
            assertEquals(0, auto.sessionTerms().maxRequests)
            auto.submit(question(1), assessment(1))

            assertEquals(0, client.calls)
        }

    // Consent is given at the start of a session: arming during one that started without it sends nothing.
    @Test
    fun armingMidSession_doesNotSend() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(autoTrialArmed = false)
            val client = FakeAiClient(completed())
            val auto = trial(client, this, consented = false)

            settings.settings = settings.settings.armingTrial(TRIAL_ID, nowMs = now)
            auto.submit(question(1), assessment(1))

            assertEquals(0, client.calls)
        }

    @Test
    fun disarmingMidSession_stopsSending() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = trial(client, this)
            auto.submit(question(1), assessment(1))

            settings.settings = settings.settings.copy(autoTrialArmed = false)
            auto.submit(question(2), assessment(2))

            assertEquals(1, client.calls)
        }

    // One allowance per trial build: arming again (even past the screen, straight in the settings) renews nothing.
    @Test
    fun rearming_neverRenewsTheAllowance() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val first = trial(client, this, session = "s1")
            first.submit(question(1, session = "s1"), assessment(1))
            settings.settings = settings.settings.copy(autoTrialArmed = false)
            settings.settings = settings.settings.armingTrial(TRIAL_ID, nowMs = now + 1_000)
            assertEquals(1L, settings.settings.autoTrialStartedAt)

            val second = trial(client, this, session = "s2")
            (1..3).forEach { second.submit(question(it, session = "s2"), assessment(it)) }
            assertEquals(3, client.calls)

            settings.settings = settings.settings.copy(autoTrialArmed = true)
            val third = trial(client, this, session = "s3")
            assertEquals(0, third.sessionTerms().maxRequests)
            third.submit(question(1, session = "s3"), assessment(1))

            assertEquals(3, client.calls)
            assertEquals(AutoPauseReason.TRIAL_USED_UP, third.state.value.pauseReason)
        }

    // Only a new trial build (a new id, which needs the user's authorization) brings a new allowance.
    @Test
    fun aNewTrialBuild_isTheOnlyWayToANewAllowance() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val first = trial(client, this, session = "s1")
            (1..3).forEach { first.submit(question(it, session = "s1"), assessment(it)) }

            // Armed for trial 1 doesn't arm trial 2.
            settings.settings = settings.settings.copy(autoTrialArmed = true)
            val otherBuild = trial(client, this, session = "s2", consented = false, trialId = TRIAL_ID + 1)
            assertFalse(otherBuild.needsConsentToStart)

            settings.settings = settings.settings.armingTrial(TRIAL_ID + 1, nowMs = now)
            val next = trial(client, this, session = "s3", trialId = TRIAL_ID + 1)
            (1..4).forEach { next.submit(question(1 + it % 3, session = "s3"), assessment(1 + it % 3)) }

            assertEquals(6, client.calls)
        }

    @Test
    fun withoutConsent_aRealSessionOnlyCaptures() =
        runTest(dispatcher) {
            val client = FakeAiClient(completed())
            val auto = trial(client, this, consented = false)

            auto.submit(question(1), assessment(1))

            assertEquals(0, client.calls)
            assertEquals(AutoPauseReason.NOT_AUTHORIZED, auto.state.value.pauseReason)
            assertEquals(AiStatus.PENDING, dao.questions.single().aiStatus)
        }

    // The 27 pending captures on the M52 come from earlier sessions: they are never sent.
    @Test
    fun pendingQuestionsFromEarlierSessions_areNeverSent() =
        runTest(dispatcher) {
            val backlog = (1..3).map { question(it, session = "20261026-143646") }
            val client = FakeAiClient(completed())
            val auto = trial(client, this, session = "trial")

            backlog.forEachIndexed { i, q -> auto.submit(q, assessment(i + 1)) }
            val fresh = question(2)
            auto.submit(fresh, assessment(2))

            assertEquals(1, client.calls)
            assertTrue(backlog.all { dao.question(it.id)!!.aiStatus == AiStatus.PENDING })
            assertEquals(AiStatus.ANSWERED, dao.question(fresh.id)!!.aiStatus)
        }

    @Test
    fun anUnknownCost_stopsSending() =
        runTest(dispatcher) {
            // A tier the price table doesn't know: the cost can't be computed.
            val client = FakeAiClient(AiCallResult.Completed(answerJson(), "resp_x", 200, SAMPLE_USAGE, serviceTier = "scale"), completed())
            val auto = trial(client, this)

            auto.submit(question(1), assessment(1))
            auto.submit(question(2), assessment(2))

            assertEquals(1, client.calls)
            assertEquals(AutoPauseReason.UNKNOWN_COST, auto.state.value.pauseReason)
            assertEquals(1, auto.state.value.requestsWithoutCost)
        }

    @Test
    fun authenticationOrCreditErrors_stopSending() =
        runTest(dispatcher) {
            for (error in listOf(AiError.AUTH, AiError.QUOTA)) {
                val client = FakeAiClient(AiCallResult.Failed(error, if (error == AiError.AUTH) 401 else 429), completed())
                val auto = trial(client, this, session = "trial-$error")

                auto.submit(question(1, session = "trial-$error"), assessment(1))
                auto.submit(question(2, session = "trial-$error"), assessment(2))

                assertEquals(error.name, 1, client.calls)
                assertEquals(AutoPauseReason.API_ERROR, auto.state.value.pauseReason)
            }
        }

    @Test
    fun aLostConnection_stopsSending_andIsNeverResent() =
        runTest(dispatcher) {
            val client = FakeAiClient(AiCallResult.Failed(AiError.TIMEOUT, null), completed())
            val auto = trial(client, this)
            val first = question(1)
            auto.submit(first, assessment(1))
            auto.submit(question(2), assessment(2))

            // Even a new consented session doesn't send the ambiguous one again.
            val again = trial(client, this, session = "trial")
            again.submit(dao.question(first.id)!!, assessment(1))

            assertEquals(1, client.calls)
            assertEquals(AutoPauseReason.CONNECTION, auto.state.value.pauseReason)
            assertEquals(
                AiRequestOutcome.UNKNOWN,
                requests.rows.values
                    .single()
                    .outcome,
            )
        }

    // Item 7: each request leaves its id, timing, tokens, tier and outcome; the cost follows from them.
    @Test
    fun everyRequest_isRecorded() =
        runTest(dispatcher) {
            val auto = trial(FakeAiClient(completed()), this)

            (1..3).forEach { auto.submit(question(it), assessment(it)) }

            val rows = requests.rows.values.toList()
            assertEquals(3, rows.size)
            rows.forEach { row ->
                assertEquals(AiRequestOutcome.SUCCEEDED, row.outcome)
                assertNotNull(row.responseId)
                assertNotNull(row.finishedAt)
                assertTrue(row.finishedAt!! >= row.startedAt)
                assertEquals(SAMPLE_USAGE.inputTokens, row.inputTokens)
                assertEquals("default", row.serviceTier)
            }
            assertEquals(3 * (412 * 0.10 + 96 * 0.50) / 1_000_000.0, auto.state.value.observedCostUsd, 1e-12)
        }
}

private const val TRIAL_ID = 1
