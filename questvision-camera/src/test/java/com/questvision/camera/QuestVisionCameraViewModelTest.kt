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

import com.questvision.camera.data.AiStatus
import com.questvision.camera.data.DirectTransactions
import com.questvision.camera.data.FakeAiRequestDao
import com.questvision.camera.data.FakeQuestionDao
import com.questvision.camera.data.QuestionRepository
import com.questvision.camera.detection.MonitoringState
import com.questvision.camera.detection.SyntheticScreens
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.recognition.RecognizedText
import com.questvision.camera.recognition.TextRecognitionEngine
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

    private fun newViewModel() =
        CaptureRepository(root, dispatcher)
            .let { captures ->
                QuestVisionCameraViewModel(
                    captures,
                    ocr,
                    QuestionRepository(dao, FakeAiRequestDao(), DirectTransactions, captures, dispatcher),
                )
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
}
