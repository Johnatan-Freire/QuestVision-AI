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
package com.questvision.camera.telegram

import com.questvision.camera.CaptureOrigin
import com.questvision.camera.CaptureRepository
import com.questvision.camera.ai.AiCallResult
import com.questvision.camera.ai.AiError
import com.questvision.camera.ai.AnalysisResult
import com.questvision.camera.ai.FakeAiClient
import com.questvision.camera.ai.FakeImageEncoder
import com.questvision.camera.ai.FakeKeySource
import com.questvision.camera.ai.InMemoryAiSettingsStore
import com.questvision.camera.ai.QuestionAnalyzer
import com.questvision.camera.ai.answerJson
import com.questvision.camera.ai.completed
import com.questvision.camera.ai.storedAiAnswer
import com.questvision.camera.data.AiStatus
import com.questvision.camera.data.AnalysisMode
import com.questvision.camera.data.DirectTransactions
import com.questvision.camera.data.FakeAiRequestDao
import com.questvision.camera.data.FakeQuestionDao
import com.questvision.camera.data.QuestionEntity
import com.questvision.camera.data.QuestionRepository
import com.questvision.camera.data.TelegramStatus
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.settings.AiSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Phase 4: answers go to Telegram once, with the Bot API played by [FakeTelegramApi]. */
@OptIn(ExperimentalCoroutinesApi::class)
class TelegramNotifierTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val dao = FakeQuestionDao()
    private val captures by lazy { CaptureRepository(File(folder.root, "captures"), dispatcher) }
    private val repository by lazy { QuestionRepository(dao, FakeAiRequestDao(), DirectTransactions, captures, dispatcher) }
    private val settings =
        InMemoryAiSettingsStore(AiSettings(firstCallConfirmed = true, requestsPerDay = 50, telegramEnabled = true, telegramChatId = 42L))
    private val api = FakeTelegramApi()
    private val tokens = FakeTokenSource()

    private fun notifier(scope: TestScope) = TelegramNotifier(repository, tokens, api, settings, FakeTelegramTexts, scope) { 7L }

    private fun analyzer(
        notifier: TelegramNotifier,
        client: FakeAiClient = FakeAiClient(completed()),
    ) = QuestionAnalyzer(repository, FakeKeySource(), client, FakeImageEncoder(), settings, notifier)

    private var sequence = 0

    /** A PENDING capture of question [number] (1–3). */
    private suspend fun question(number: Int = 2): QuestionEntity {
        val seq = ++sequence
        val file = captures.newAutomaticCaptureFile("s", seq, nowMs = 1_000_000L + seq).apply { writeBytes(byteArrayOf(1)) }
        return repository
            .recordCapture(file, CaptureOrigin.AUTOMATIC, "s", seq, file.lastModified(), OcrScreens.screen(number), AiStatus.PENDING)
            .getOrThrow()
    }

    private fun telegramStatus(id: String) = dao.questions.single { it.id == id }.telegramStatus

    @Test
    fun anAnswer_isSentOnce_asNumberLetterAndAlternative() =
        runTest(dispatcher) {
            val q = question(2)
            val result = analyzer(notifier(this)).analyze(q.id, AnalysisMode.TEXT)

            assertTrue(result is AnalysisResult.Answered)
            assertEquals(listOf(42L to "Questão 2: C) DNS"), api.sent)
            assertEquals(TelegramStatus.SENT, telegramStatus(q.id))
        }

    @Test
    fun theSameQuestion_isNeverSentTwice_evenAfterARestart() =
        runTest(dispatcher) {
            val q = question(2)
            analyzer(notifier(this)).analyze(q.id, AnalysisMode.TEXT)

            // A new notifier, as after a restart, told about the same question again.
            val again = notifier(this)
            again.onAnswered(q.id)
            assertNull(again.deliver(q.id))

            assertEquals(1, api.sent.size)
        }

    @Test
    fun whenSwitchedOff_nothingIsSent_andTheAnswerStaysUnsent() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(telegramEnabled = false)
            val q = question(2)
            analyzer(notifier(this)).analyze(q.id, AnalysisMode.TEXT)

            assertTrue(api.sent.isEmpty())
            assertEquals(TelegramStatus.NOT_REQUESTED, telegramStatus(q.id))
        }

    @Test
    fun withoutAChat_nothingIsSent() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(telegramChatId = null)
            val q = question(2)
            analyzer(notifier(this)).analyze(q.id, AnalysisMode.TEXT)

            assertTrue(api.sent.isEmpty())
        }

    @Test
    fun aFailedAnalysis_sendsNothing() =
        runTest(dispatcher) {
            val q = question(2)
            analyzer(notifier(this), FakeAiClient(AiCallResult.Failed(AiError.AUTH, 401))).analyze(q.id, AnalysisMode.TEXT)

            assertTrue(api.sent.isEmpty())
            assertEquals(TelegramStatus.NOT_REQUESTED, telegramStatus(q.id))
        }

    // No reply: it may have arrived, so it is recorded as unknown and never resent.
    @Test
    fun aLostReply_isUnknown_andNeverResent() =
        runTest(dispatcher) {
            val api = FakeTelegramApi(TelegramSendResult.Failed(TelegramError.NETWORK))
            val notifier = TelegramNotifier(repository, tokens, api, settings, FakeTelegramTexts, this) { 7L }
            val q = question(2)
            analyzer(notifier).analyze(q.id, AnalysisMode.TEXT)
            notifier.onAnswered(q.id)

            assertEquals(1, api.sent.size)
            assertEquals(TelegramStatus.UNKNOWN, telegramStatus(q.id))
            assertEquals(TelegramStatus.UNKNOWN, notifier.lastDelivery.value?.status)
        }

    @Test
    fun aRejectedMessage_isFailed_withItsReason() =
        runTest(dispatcher) {
            val api = FakeTelegramApi(TelegramSendResult.Failed(TelegramError.CHAT_NOT_FOUND, 400))
            val notifier = TelegramNotifier(repository, tokens, api, settings, FakeTelegramTexts, this) { 7L }
            val q = question(2)
            analyzer(notifier).analyze(q.id, AnalysisMode.TEXT)

            assertEquals(TelegramStatus.FAILED, telegramStatus(q.id))
            assertEquals(TelegramError.CHAT_NOT_FOUND, notifier.lastDelivery.value?.error)
            assertEquals(2, notifier.lastDelivery.value?.questionNumber)
        }

    @Test
    fun withoutAToken_itFails_withoutCallingTelegram() =
        runTest(dispatcher) {
            tokens.token = null
            val q = question(2)
            analyzer(notifier(this)).analyze(q.id, AnalysisMode.TEXT)

            assertTrue(api.sent.isEmpty())
            assertEquals(TelegramStatus.FAILED, telegramStatus(q.id))
        }

    // An answer stored before Telegram was switched on is never sent: only new answers are.
    @Test
    fun earlierAnswers_areNotSent() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(telegramEnabled = false)
            val old = question(1)
            val notifier = notifier(this)
            analyzer(notifier).analyze(old.id, AnalysisMode.TEXT)

            settings.settings = settings.settings.copy(telegramEnabled = true)
            val fresh = question(2)
            analyzer(notifier).analyze(fresh.id, AnalysisMode.TEXT)

            assertEquals(1, api.sent.size)
            assertEquals(TelegramStatus.NOT_REQUESTED, telegramStatus(old.id))
        }

    @Test
    fun theMessage_coversEveryKindOfAnswer() =
        runTest(dispatcher) {
            val stored = question(2).ocrText

            fun text(json: String) =
                telegramText(
                    storedAiAnswer(json)!!,
                    questionNumber = 9,
                    ocrText = stored,
                    texts = FakeTelegramTexts,
                )
            assertEquals("Questão 2: C) DNS", text(answerJson()))
            assertEquals("Questão 2: B) FTP; C) DNS", text(answerJson(type = "multiple", answer = listOf("B", "C"))))
            assertEquals("Questão 9: C) DNS", text(answerJson(number = null)))
            // "None correct" always asks for a human look.
            assertEquals("Questão 2: nenhuma alternativa correta (revisar)", text(answerJson(type = "none_correct", answer = emptyList())))
            assertEquals("Questão 2: C) DNS (revisar)", text(answerJson(needsReview = true)))
            // An unknown letter still goes out, without text.
            assertEquals("Questão 2: Z", text(answerJson(answer = listOf("Z"))))
        }
}
