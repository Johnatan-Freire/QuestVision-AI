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
package com.questvision.camera

import com.questvision.camera.ai.AutoEvent
import com.questvision.camera.ai.AutoPauseReason
import com.questvision.camera.ai.FakeAiClient
import com.questvision.camera.ai.InMemoryAiSettingsStore
import com.questvision.camera.ai.answerJson
import com.questvision.camera.ai.autoAnalysis
import com.questvision.camera.ai.completed
import com.questvision.camera.data.AiRequestEntity
import com.questvision.camera.data.AiStatus
import com.questvision.camera.data.AnalysisMode
import com.questvision.camera.data.DirectTransactions
import com.questvision.camera.data.FakeAiRequestDao
import com.questvision.camera.data.FakeQuestionDao
import com.questvision.camera.data.QuestionRepository
import com.questvision.camera.detection.MonitoringState
import com.questvision.camera.detection.SyntheticScreens
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.recognition.RecognizedText
import com.questvision.camera.recognition.TextRecognitionEngine
import com.questvision.camera.settings.AiSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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

/**
 * The automatic capture flow end to end at the ViewModel level, with the controller and OCR
 * simulated: the test only plays the camera (frames in, JPEG written, CameraX callback) and the text
 * the photo would contain, and never calls anything a user would trigger after starting the session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuestVisionCameraViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val root by lazy { File(folder.root, "captures") }
    private var clockMs = 0L
    private val ocr = FakeTextEngine()

    /** Outlives each ViewModel, like the database file across app restarts. */
    private val dao = FakeQuestionDao()

    /** Stands in for ML Kit: returns the text of the question on screen, or fails, or waits. */
    private class FakeTextEngine : TextRecognitionEngine {
        var text: RecognizedText = RecognizedText(emptyList())
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0

        override suspend fun recognize(file: File): RecognizedText {
            calls++
            gate?.await()
            failure?.let { throw it }
            return text
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Outlives each ViewModel too: the request log in the same database. */
    private val requests = FakeAiRequestDao()

    /** Plays OpenAI for the automatic analysis; nothing is sent unless a test allows it. */
    private val client = FakeAiClient(completed())
    private val settings = InMemoryAiSettingsStore(AiSettings(firstCallConfirmed = true))

    private fun newViewModel(allowRequests: Boolean = false) =
        CaptureRepository(root, dispatcher)
            .let { captures ->
                val repository = QuestionRepository(dao, requests, DirectTransactions, captures, dispatcher)
                QuestVisionCameraViewModel(captures, ocr, repository, autoAnalysis(repository, client, settings, allowRequests))
            }.apply {
                onCameraStatusChanged(CameraStatus.ACTIVE)
            }

    private val QuestVisionCameraViewModel.state
        get() = uiState.value as QuestVisionCameraUiState.Previewing

    /**
     * Plays the camera: shows question [seed] until the ViewModel requests a capture. The photo's
     * text is that question's, unless [text] says otherwise.
     */
    private fun QuestVisionCameraViewModel.showUntilCaptureRequested(
        seed: Int,
        text: RecognizedText = OcrScreens.screen(seed),
    ): File {
        ocr.text = text
        repeat(8) {
            onFrameAnalyzed(SyntheticScreens.frame(SyntheticScreens.question(seed), clockMs))
            clockMs += SyntheticScreens.config.sampleIntervalMs
            state.pendingCapture?.let { return it }
        }
        throw AssertionError("No capture requested for question $seed")
    }

    /** Plays the controller: CameraX writes the JPEG and reports success. */
    private fun QuestVisionCameraViewModel.cameraSaves(file: File) {
        file.writeBytes(ByteArray(64) { it.toByte() })
        onCaptureSaved(file)
    }

    @Test
    fun automaticCapture_happensAndIsSavedWithoutInteraction() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()

        val file = viewModel.showUntilCaptureRequested(seed = 1)
        assertEquals(MonitoringState.CAPTURING, viewModel.state.monitoring)
        assertTrue("${file.name} / ${viewModel.state.session}", file.name.contains("_auto_${viewModel.state.session}_1"))

        viewModel.cameraSaves(file)

        with(viewModel.state) {
            assertEquals(1, autoCaptureCount)
            assertEquals(MonitoringState.MONITORING, monitoring)
            assertNull(pendingCapture)
            // Nothing to confirm: no review opens, only a discreet notice.
            assertNull(review)
            assertEquals(Notice.AUTO_CAPTURE_SAVED, notice)
            assertEquals(1, monitoringStats.capturesRequested)
            assertEquals(1, monitoringStats.capturesSaved)
        }
        assertTrue(file.exists())
    }

    @Test
    fun monitoringContinues_andTwoQuestionsProduceTwoImages() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()

        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 1))
        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 2))
        viewModel.openHistory()

        val history = viewModel.state.history!!
        assertEquals(2, viewModel.state.autoCaptureCount)
        assertEquals(listOf(2, 1), history.map { it.sequence })
        assertTrue(history.all { it.origin == CaptureOrigin.AUTOMATIC && it.session == viewModel.state.session })
        assertTrue(viewModel.state.monitoringStats.changesDetected >= 2)
        // The history opens over the camera without ending the session.
        assertEquals(MonitoringState.MONITORING, viewModel.state.monitoring)
    }

    @Test
    fun writeFailure_isNotCounted() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()

        val file = viewModel.showUntilCaptureRequested(seed = 1)
        viewModel.onCaptureFailed(file)

        with(viewModel.state) {
            assertEquals(0, autoCaptureCount)
            assertEquals(1, monitoringStats.capturesFailed)
            assertEquals(Notice.CAPTURE_FAILED, notice)
            assertEquals(MonitoringState.MONITORING, monitoring)
        }
        assertFalse(file.exists())
    }

    @Test
    fun reportedSuccessWithAnEmptyFile_countsAsAFailure() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()

        val file = viewModel.showUntilCaptureRequested(seed = 1)
        file.writeBytes(ByteArray(0))
        viewModel.onCaptureSaved(file)

        assertEquals(0, viewModel.state.autoCaptureCount)
        assertEquals(1, viewModel.state.monitoringStats.capturesFailed)
        assertFalse(file.exists())
    }

    @Test
    fun counter_matchesSavedFiles_afterMixedResults() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()

        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 1))
        viewModel.onCaptureFailed(viewModel.showUntilCaptureRequested(seed = 2))
        // The failed question is offered again once it is still for another stabilization period.
        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 2))
        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 3))
        viewModel.openHistory()

        val stats = viewModel.state.monitoringStats
        assertEquals(3, viewModel.state.autoCaptureCount)
        assertEquals(stats.capturesSaved, viewModel.state.history!!.count { it.origin == CaptureOrigin.AUTOMATIC })
        assertEquals(stats.capturesRequested, stats.capturesSaved + stats.capturesFailed)
    }

    @Test
    fun captures_persistAcrossARestart() {
        val first = newViewModel()
        first.startMonitoring()
        first.cameraSaves(first.showUntilCaptureRequested(seed = 1))
        first.capture()
        first.cameraSaves(first.state.pendingCapture!!)

        // A new ViewModel over the same storage, as after the app is restarted.
        val restarted = newViewModel()
        restarted.openHistory()

        val origins =
            restarted.state.history!!
                .map { it.origin }
                .toSet()
        assertEquals(setOf(CaptureOrigin.AUTOMATIC, CaptureOrigin.MANUAL), origins)
    }

    @Test
    fun manualCapture_staysAvailableDuringMonitoring_andIsNotCountedAsAutomatic() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()

        viewModel.capture()
        val manual = viewModel.state.pendingCapture!!
        viewModel.cameraSaves(manual)

        assertTrue(manual.name.endsWith("_manual.jpg"))
        assertEquals(0, viewModel.state.autoCaptureCount)
        assertEquals(0, viewModel.state.monitoringStats.capturesRequested)
    }

    @Test
    fun staleResult_afterStoppingTheSession_isNotCountedNorKept() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()
        val file = viewModel.showUntilCaptureRequested(seed = 1)

        viewModel.stopMonitoring()
        viewModel.cameraSaves(file)

        assertEquals(MonitoringState.IDLE, viewModel.state.monitoring)
        assertEquals(0, viewModel.state.autoCaptureCount)
        assertNull(viewModel.state.review)
        // An automatic photo that was never verified is not kept as a valid capture.
        assertFalse(file.exists())
        assertEquals(0, ocr.calls)
    }

    @Test
    fun threeQuestions_produceThreeCapturesAndThreeHistoryEntries() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()

        (1..3).forEach { viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = it)) }
        viewModel.openHistory()

        assertEquals(3, viewModel.state.autoCaptureCount)
        assertEquals(3, viewModel.state.history!!.count { it.origin == CaptureOrigin.AUTOMATIC })
        assertEquals(3, ocr.calls)
    }

    @Test
    fun visualCandidateWithTheSameText_isDiscardedAsDuplicate() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()
        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 1))

        // Exposure/glare after the capture makes the screen look different, but it is the same
        // question: the photo's text matches question 1.
        val repeat = viewModel.showUntilCaptureRequested(seed = 2, text = OcrScreens.screen(1))
        viewModel.cameraSaves(repeat)

        with(viewModel.state) {
            assertEquals(1, autoCaptureCount)
            assertEquals(1, monitoringStats.textDuplicatesIgnored)
            assertEquals(Notice.DUPLICATE_IGNORED, notice)
            assertEquals(MonitoringState.MONITORING, monitoring)
        }
        assertFalse(repeat.exists())
    }

    @Test
    fun unreadablePhoto_isDiscarded() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()

        val file = viewModel.showUntilCaptureRequested(seed = 1, text = RecognizedText(emptyList()))
        viewModel.cameraSaves(file)

        assertEquals(0, viewModel.state.autoCaptureCount)
        assertEquals(1, viewModel.state.monitoringStats.unreadableIgnored)
        assertEquals(Notice.UNREADABLE_IGNORED, viewModel.state.notice)
        assertFalse(file.exists())
    }

    @Test
    fun ocrFailure_isNeverCountedAsAValidCapture_andTheScreenIsRetried() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()
        ocr.failure = IllegalStateException("model not ready")

        val file = viewModel.showUntilCaptureRequested(seed = 1)
        viewModel.cameraSaves(file)

        with(viewModel.state) {
            assertEquals(0, autoCaptureCount)
            assertEquals(1, monitoringStats.recognitionFailures)
            assertEquals(Notice.TEXT_READ_FAILED, notice)
            assertEquals(MonitoringState.MONITORING, monitoring)
        }
        assertFalse(file.exists())

        // Recognition works again: the same screen is proposed again and now counted.
        ocr.failure = null
        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 1))
        assertEquals(1, viewModel.state.autoCaptureCount)
    }

    @Test
    fun oneCandidateAtATime_whileTextIsBeingRead() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()
        ocr.gate = CompletableDeferred()

        val file = viewModel.showUntilCaptureRequested(seed = 1)
        viewModel.cameraSaves(file)
        assertEquals(MonitoringState.VERIFYING, viewModel.state.monitoring)

        // A different screen while verifying: no new capture is requested.
        repeat(6) {
            viewModel.onFrameAnalyzed(SyntheticScreens.frame(SyntheticScreens.question(2), clockMs))
            clockMs += SyntheticScreens.config.sampleIntervalMs
        }
        assertNull(viewModel.state.pendingCapture)

        ocr.gate!!.complete(Unit)
        assertEquals(1, viewModel.state.autoCaptureCount)
        assertEquals(MonitoringState.MONITORING, viewModel.state.monitoring)
    }

    @Test
    fun stoppingDuringOcr_cancelsIt_andKeepsNothing() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()
        ocr.gate = CompletableDeferred()

        val file = viewModel.showUntilCaptureRequested(seed = 1)
        viewModel.cameraSaves(file)
        viewModel.stopMonitoring()
        ocr.gate!!.complete(Unit)

        assertEquals(0, viewModel.state.autoCaptureCount)
        assertEquals(MonitoringState.IDLE, viewModel.state.monitoring)
        assertFalse(file.exists())
    }

    @Test
    fun pausingDuringOcr_appliesTheResultButStaysPaused() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()
        ocr.gate = CompletableDeferred()

        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 1))
        viewModel.pauseMonitoring()
        ocr.gate!!.complete(Unit)

        assertEquals(1, viewModel.state.autoCaptureCount)
        assertEquals(MonitoringState.PAUSED, viewModel.state.monitoring)
    }

    @Test
    fun newQuestion_isRecordedPendingForTheAi_withItsText() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()

        val file = viewModel.showUntilCaptureRequested(seed = 2)
        viewModel.cameraSaves(file)

        val row = dao.questions.single()
        assertEquals(file.name, row.imagePath)
        assertEquals(CaptureOrigin.AUTOMATIC, row.origin)
        assertEquals(viewModel.state.session, row.sessionId)
        assertEquals(1, row.sequence)
        assertEquals(2, row.questionNumber)
        assertEquals(AiStatus.PENDING, row.aiStatus)
        assertTrue(row.ocrText!!.contains("Redes de Computadores"))
        assertTrue(viewModel.state.session!! in dao.sessions)
    }

    @Test
    fun manualCapture_isRecordedButNotSentToTheAi() {
        val viewModel = newViewModel()

        viewModel.capture()
        viewModel.cameraSaves(viewModel.state.pendingCapture!!)

        val row = dao.questions.single()
        assertEquals(CaptureOrigin.MANUAL, row.origin)
        assertEquals(AiStatus.NOT_REQUESTED, row.aiStatus)
        assertNull(row.sessionId)
        assertNull(row.ocrText)
    }

    @Test
    fun duplicateQuestion_leavesNoRow() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()
        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 1))
        // Same text again after a visual change: discarded, never recorded.
        val duplicate = viewModel.showUntilCaptureRequested(seed = 2, text = OcrScreens.screen(1))
        viewModel.cameraSaves(duplicate)

        assertEquals(Notice.DUPLICATE_IGNORED, viewModel.state.notice)
        assertEquals(1, dao.questions.size)
        assertFalse(duplicate.exists())
    }

    @Test
    fun databaseFailure_isReported_andThePhotoIsKeptForRecovery() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()
        dao.failingWrites = 10

        val file = viewModel.showUntilCaptureRequested(seed = 1)
        viewModel.cameraSaves(file)

        assertEquals(Notice.RECORD_FAILED, viewModel.state.notice)
        assertTrue(file.exists())
        assertTrue(dao.questions.isEmpty())

        // The next start imports it, so the history does not lose it.
        dao.failingWrites = 0
        val restarted = newViewModel()
        restarted.openHistory()
        assertEquals(listOf(file), restarted.state.history!!.map { it.file })
        assertEquals(AiStatus.NOT_REQUESTED, dao.questions.single().aiStatus)
    }

    // Phase 3.4, end to end: the session's only input is "start"; the camera, the OCR and OpenAI are simulated.
    @Test
    fun aSession_detectsValidatesAndAnalyzes_withoutAnyTouch() {
        client.replyWith(completed(answerJson(answer = listOf("B"), number = 1)), completed(answerJson(answer = listOf("C"), number = 2)))
        val viewModel = newViewModel(allowRequests = true)
        viewModel.startMonitoring()

        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 1))
        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 2))

        assertEquals(2, client.calls)
        assertTrue(dao.questions.all { it.aiStatus == AiStatus.ANSWERED })
        with(viewModel.state.autoAnalysis) {
            assertEquals(2, answered)
            assertEquals(2, lastAnswer!!.questionNumber)
            assertEquals(listOf("C"), lastAnswer!!.answer)
            assertEquals("DNS", lastAnswer!!.alternativeText)
        }
        assertEquals(AnalysisMode.TEXT, client.inputs.first().mode)
    }

    // 2: the same question in a later session: the AI already saw it, so nothing is sent or kept.
    @Test
    fun aQuestionAnalyzedInAnEarlierSession_isNotSentAgain() {
        val first = newViewModel(allowRequests = true)
        first.startMonitoring()
        first.cameraSaves(first.showUntilCaptureRequested(seed = 2))
        first.stopMonitoring()
        assertEquals(1, client.calls)

        val viewModel = newViewModel(allowRequests = true)
        viewModel.startMonitoring()
        val again =
            viewModel.showUntilCaptureRequested(
                seed = 3,
                text = OcrScreens.screen(OcrScreens.questions.getValue(2), clock = "19:01:44"),
            )
        viewModel.cameraSaves(again)

        assertEquals(1, client.calls)
        assertEquals(Notice.ALREADY_ANALYZED, viewModel.state.notice)
        assertFalse(again.exists())
        assertEquals(1, dao.questions.size)
        assertTrue(viewModel.state.autoAnalysis.lastEvent is AutoEvent.AlreadyAnalyzed)
    }

    // 3 + 5: an incomplete question is kept for manual use but never sent.
    @Test
    fun anIncompleteQuestion_isKept_butNotSent() {
        val viewModel = newViewModel(allowRequests = true)
        viewModel.startMonitoring()
        val cut = OcrScreens.screen(1).let { it.copy(lines = it.lines.dropLast(3)) }

        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 1, text = cut))

        assertEquals(0, client.calls)
        assertEquals(Notice.INCOMPLETE_SAVED, viewModel.state.notice)
        assertEquals(AiStatus.PENDING, dao.questions.single().aiStatus)
        assertTrue(viewModel.state.autoAnalysis.lastEvent is AutoEvent.Incomplete)
    }

    // The app as shipped: real automatic requests are locked, so a validated question waits for "Analisar".
    @Test
    fun withRealAutomaticRequestsLocked_nothingLeavesTheDevice() {
        val viewModel = newViewModel(allowRequests = false)
        viewModel.startMonitoring()

        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 1))

        assertEquals(0, client.calls)
        assertEquals(AiStatus.PENDING, dao.questions.single().aiStatus)
        assertEquals(1, viewModel.state.autoAnalysis.awaitingAuthorization)
        assertFalse(viewModel.state.autoAnalysis.realRequestsAllowed)
    }

    // 11: capture-only. At the 24-hour limit nothing more is sent, but the camera keeps monitoring and
    // new questions keep being captured, stored and kept for later.
    @Test
    fun atTheLimit_theCameraKeepsCapturing_andNothingIsSent() {
        kotlinx.coroutines.runBlocking {
            repeat(10) {
                requests.insert(
                    AiRequestEntity(
                        questionId = "earlier-$it",
                        model = "gpt-6-luna",
                        mode = AnalysisMode.TEXT,
                        startedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        val viewModel = newViewModel(allowRequests = true)
        viewModel.startMonitoring()

        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 1))
        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 2))

        assertEquals(0, client.calls)
        assertEquals(AutoPauseReason.DAILY_LIMIT, viewModel.state.autoAnalysis.pauseReason)
        assertEquals(MonitoringState.MONITORING, viewModel.state.monitoring)
        assertEquals(2, viewModel.state.autoCaptureCount)
        assertEquals(2, dao.questions.count { it.aiStatus == AiStatus.PENDING })
        assertTrue(dao.questions.all { File(root, it.imagePath).exists() })

        // Pausing and resuming the monitoring keeps the analysis stopped.
        viewModel.pauseMonitoring()
        viewModel.resumeMonitoring()
        assertEquals(AutoPauseReason.DAILY_LIMIT, viewModel.state.autoAnalysis.pauseReason)
        assertEquals(0, client.calls)
    }

    // 12: pausing the session stops capturing and sending; resuming continues both.
    @Test
    fun pausingTheSession_holdsCaptureAndAnalysis_andResumingContinues() {
        val viewModel = newViewModel(allowRequests = true)
        viewModel.startMonitoring()
        viewModel.pauseMonitoring()

        assertEquals(MonitoringState.PAUSED, viewModel.state.monitoring)
        repeat(8) {
            viewModel.onFrameAnalyzed(
                SyntheticScreens.frame(SyntheticScreens.question(1), clockMs).also {
                    clockMs +=
                        SyntheticScreens.config.sampleIntervalMs
                },
            )
        }
        assertNull(viewModel.state.pendingCapture)

        viewModel.resumeMonitoring()
        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 1))

        assertEquals(1, client.calls)
        assertEquals(AiStatus.ANSWERED, dao.questions.single().aiStatus)
    }

    // Near-identical questions ("correta" / "incorreta") are both kept within a session.
    @Test
    fun aNegatedVariantOfTheQuestion_isNotTakenForADuplicate() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()
        val correct =
            OcrScreens.Question(
                "Questão 04 — Redes",
                "Sobre o protocolo DNS, qual alternativa está correta?",
                listOf("A) Usa a porta 53", "B) Criptografa tudo", "C) Substitui o IP", "D) Só funciona em LAN"),
            )

        viewModel.cameraSaves(viewModel.showUntilCaptureRequested(seed = 1, text = OcrScreens.screen(correct)))
        val negated = correct.copy(statement = correct.statement.replace("correta", "incorreta"))
        val second = viewModel.showUntilCaptureRequested(seed = 2, text = OcrScreens.screen(negated))
        viewModel.cameraSaves(second)

        assertTrue(second.exists())
        assertEquals(2, dao.questions.size)
        assertEquals(2, viewModel.state.autoCaptureCount)
    }

    // 13: answered questions and their photos survive restarts and the retention cleanup.
    @Test
    fun analyzedQuestions_andTheirPhotos_surviveARestart() {
        val viewModel = newViewModel(allowRequests = true)
        viewModel.startMonitoring()
        val file = viewModel.showUntilCaptureRequested(seed = 1)
        viewModel.cameraSaves(file)
        viewModel.stopMonitoring()
        // Many newer manual captures afterwards.
        repeat(MAX_RECENT_CAPTURES + 3) { i ->
            File(root, "capture_20261028_1200%02d_000_manual.jpg".format(i)).apply {
                writeBytes(byteArrayOf(1))
                setLastModified(System.currentTimeMillis() + 60_000L + i)
            }
        }

        val restarted = newViewModel()
        restarted.openHistory()

        assertTrue(file.exists())
        assertEquals(AiStatus.ANSWERED, dao.questions.single { it.imagePath == file.name }.aiStatus)
        assertTrue(restarted.state.history!!.any { it.file == file })
    }

    // Phase 3.5, item 6: a discard that can't be done says so.
    @Test
    fun discardingDuringAnAnalysis_isRefused_withANotice() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()
        val file = viewModel.showUntilCaptureRequested(seed = 1)
        viewModel.cameraSaves(file)
        val row = dao.questions.single()
        kotlinx.coroutines.runBlocking { dao.claimForAi(row.id, 1L) }

        viewModel.openCapture(file)
        viewModel.discardReviewedCapture()

        assertEquals(Notice.DISCARD_REFUSED, viewModel.state.notice)
        assertTrue(file.exists())
        assertEquals(1, dao.questions.size)
    }

    // Phase 3.5, item 5: the stored answer of a question whose photo is gone can still be opened.
    @Test
    fun anAnsweredQuestionWithoutItsPhoto_stillOpens() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()
        val file = viewModel.showUntilCaptureRequested(seed = 1)
        viewModel.cameraSaves(file)
        val row = dao.questions.single()
        kotlinx.coroutines.runBlocking { dao.saveAiResult(row.id, AiStatus.ANSWERED, "{}", "gpt-6-luna", 1L) }
        file.delete()

        val restarted = newViewModel()
        restarted.openHistory()
        restarted.openCapture(file)

        assertEquals(1, dao.questions.size)
        assertTrue(restarted.state.reviewPhotoMissing)
        assertEquals(file, restarted.state.reviewFile)
        assertTrue(restarted.state.isOverlayOpen)
    }

    // Phase 3.5, item 7: preview, then confirm; cancelling removes nothing.
    @Test
    fun theManualCleanup_showsAPreview_andRemovesOnlyAfterConfirmation() {
        val viewModel = newViewModel()
        viewModel.startMonitoring()
        val first = viewModel.showUntilCaptureRequested(seed = 1).also { viewModel.cameraSaves(it) }
        val second = viewModel.showUntilCaptureRequested(seed = 2).also { viewModel.cameraSaves(it) }

        viewModel.requestPendingCleanup()
        assertEquals(2, viewModel.state.pendingCleanup!!.count)
        viewModel.cancelPendingCleanup()
        assertNull(viewModel.state.pendingCleanup)
        assertEquals(2, dao.questions.size)

        viewModel.requestPendingCleanup()
        viewModel.confirmPendingCleanup()

        assertEquals(Notice.PENDING_CLEANED, viewModel.state.notice)
        assertEquals(2, viewModel.state.cleanedCount)
        assertTrue(dao.questions.isEmpty())
        assertFalse(first.exists() || second.exists())
    }
}
