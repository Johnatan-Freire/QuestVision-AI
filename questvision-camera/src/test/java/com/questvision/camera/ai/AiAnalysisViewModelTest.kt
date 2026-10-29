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

import com.questvision.camera.CameraStatus
import com.questvision.camera.CaptureOrigin
import com.questvision.camera.CaptureRepository
import com.questvision.camera.QuestVisionCameraViewModel
import com.questvision.camera.data.AiStatus
import com.questvision.camera.data.AnalysisMode
import com.questvision.camera.data.DirectTransactions
import com.questvision.camera.data.FakeAiRequestDao
import com.questvision.camera.data.FakeQuestionDao
import com.questvision.camera.data.QuestionRepository
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.recognition.RecognizedText
import com.questvision.camera.recognition.TextRecognitionEngine
import com.questvision.camera.settings.AiSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AiAnalysisViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val dao = FakeQuestionDao()
    private val requests = FakeAiRequestDao()
    private val captures by lazy { CaptureRepository(File(folder.root, "captures"), dispatcher) }
    private val repository by lazy { QuestionRepository(dao, requests, DirectTransactions, captures, dispatcher) }
    private val settings = InMemoryAiSettingsStore(AiSettings())
    private val client = FakeAiClient(completed())
    private val analyzer by lazy { QuestionAnalyzer(repository, FakeKeySource(), client, FakeImageEncoder(), settings) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun storedQuestion(
        sequence: Int = 1,
        status: AiStatus = AiStatus.PENDING,
    ): File {
        val file = captures.newAutomaticCaptureFile("s1", sequence, nowMs = 1_000_000L + sequence).apply { writeBytes(byteArrayOf(1)) }
        repository.recordCapture(file, CaptureOrigin.AUTOMATIC, "s1", sequence, file.lastModified(), OcrScreens.screen(2), status)
        return file
    }

    private fun viewModel() = AiAnalysisViewModel(analyzer, repository, settings)

    // 14: nothing is sent when the app starts, even with pending and interrupted questions.
    @Test
    fun startingTheApp_andOpeningThePanel_sendNothing() =
        runTest(dispatcher) {
            storedQuestion(sequence = 1)
            val interrupted = storedQuestion(sequence = 2)
            dao.claimForAi(repository.question(interrupted)!!.id, 1L)

            QuestVisionCameraViewModel(
                captures,
                object : TextRecognitionEngine {
                    override suspend fun recognize(file: File) = RecognizedText(emptyList())
                },
                repository,
                autoAnalysis(repository, client),
            ).onCameraStatusChanged(CameraStatus.ACTIVE)
            val panel = viewModel()
            panel.select(interrupted)

            assertEquals(0, client.calls)
            assertTrue(requests.rows.isEmpty())
            assertEquals(AiStatus.FAILED, panel.uiState.value.status)
            assertEquals(AiError.INTERRUPTED, panel.uiState.value.lastError)
            assertEquals(1, dao.questions.count { it.aiStatus == AiStatus.PENDING })
        }

    @Test
    fun theFirstRealRequest_needsAConfirmation_once() =
        runTest(dispatcher) {
            val first = storedQuestion(sequence = 1)
            val second = storedQuestion(sequence = 2)
            val panel = viewModel()
            panel.select(first)

            panel.analyze()

            assertEquals(Confirmation.FIRST_CALL, panel.uiState.value.confirming)
            assertEquals(0, client.calls)
            panel.dismissConfirmation()
            assertNull(panel.uiState.value.confirming)
            assertEquals(0, client.calls)

            panel.analyze()
            panel.confirm()

            assertEquals(1, client.calls)
            assertTrue(settings.settings.firstCallConfirmed)
            with(panel.uiState.value) {
                assertEquals(AiStatus.ANSWERED, status)
                assertEquals(listOf("C"), answer!!.answer)
                assertFalse(running)
                assertEquals(1, requestsInWindow)
                assertEquals(412, lastRequest!!.inputTokens)
                // Observed: the reported tokens at gpt-6-luna's Standard prices.
                assertEquals((412 * 0.10 + 96 * 0.50) / 1_000_000.0, lastRequestCostUsd!!, 1e-12)
            }

            // Later requests go straight out.
            panel.select(second)
            panel.analyze()
            assertEquals(2, client.calls)
        }

    @Test
    fun textIsTheDefaultMode_andTheImageAnExplicitChoice() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(firstCallConfirmed = true)
            val file = storedQuestion()
            val panel = viewModel()
            panel.select(file)

            assertEquals(AnalysisMode.TEXT, panel.uiState.value.mode)
            assertTrue(panel.uiState.value.textAvailable)
            assertEquals("gpt-6-luna", panel.uiState.value.model.id)
            assertFalse(
                panel.uiState.value.estimate!!
                    .excludesImage,
            )

            panel.selectMode(AnalysisMode.IMAGE)
            // No image token count is guessed: the estimate says it leaves the photo out.
            assertTrue(
                panel.uiState.value.estimate!!
                    .excludesImage,
            )
            panel.analyze()

            assertEquals(AnalysisMode.IMAGE, client.inputs.single().mode)
        }

    @Test
    fun cancellingFromThePanel_keepsTheQuestion() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(firstCallConfirmed = true)
            client.gate = CompletableDeferred()
            val file = storedQuestion()
            val panel = viewModel()
            panel.select(file)

            panel.analyze()
            assertTrue(panel.uiState.value.running)
            // A second tap while running sends nothing more.
            panel.analyze()
            panel.cancel()

            assertEquals(1, client.calls)
            with(panel.uiState.value) {
                assertFalse(running)
                assertEquals(AiStatus.FAILED, status)
                assertEquals(AiError.CANCELLED, lastError)
                assertNull(answer)
            }
            assertTrue(file.exists())
        }

    @Test
    fun resendingAfterAnUnknownOutcome_needsAConfirmation_butNotAfterAKnownFailure() =
        runTest(dispatcher) {
            settings.settings = settings.settings.copy(firstCallConfirmed = true)
            client.gate = CompletableDeferred()
            val file = storedQuestion()
            val panel = viewModel()
            panel.select(file)
            panel.analyze()
            panel.cancel()
            client.gate = null

            // CANCELLED: it may have been processed and billed.
            panel.analyze()
            assertEquals(Confirmation.RESEND_UNKNOWN_OUTCOME, panel.uiState.value.confirming)
            assertEquals(1, client.calls)
            panel.confirm()
            assertEquals(2, client.calls)
            assertEquals(AiStatus.ANSWERED, panel.uiState.value.status)

            // A failure OpenAI reported (e.g. 401) is known not to have produced an answer.
            val other = storedQuestion(sequence = 2)
            client.replyWith(AiCallResult.Failed(AiError.AUTH, 401))
            panel.select(other)
            panel.analyze()
            assertEquals(AiError.AUTH, panel.uiState.value.lastError)
            panel.analyze()
            assertNull(panel.uiState.value.confirming)
            assertEquals(4, client.calls)
        }

    @Test
    fun aPhotoWithoutARow_cannotBeAnalyzed() =
        runTest(dispatcher) {
            val orphan = captures.newManualCaptureFile(nowMs = 5_000L).apply { writeBytes(byteArrayOf(1)) }
            val panel = viewModel()

            panel.select(orphan)
            panel.analyze()

            assertFalse(panel.uiState.value.recorded)
            assertFalse(panel.uiState.value.canAnalyze)
            assertEquals(0, client.calls)
        }
}
