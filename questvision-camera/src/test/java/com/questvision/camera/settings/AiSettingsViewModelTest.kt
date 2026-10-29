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
package com.questvision.camera.settings

import com.questvision.camera.CaptureRepository
import com.questvision.camera.data.AiRequestEntity
import com.questvision.camera.data.AnalysisMode
import com.questvision.camera.data.DirectTransactions
import com.questvision.camera.data.FakeAiRequestDao
import com.questvision.camera.data.FakeQuestionDao
import com.questvision.camera.data.QuestionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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

@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val store = InMemorySettingsStore()
    private val key = "sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"

    private class InMemorySettingsStore : AiSettingsStore {
        var saved = AiSettings()

        override fun load() = saved

        override fun save(settings: AiSettings) {
            saved = settings
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val requests = FakeAiRequestDao()

    private fun newViewModel(trialId: Int = 0) =
        AiSettingsViewModel(
            OpenAiKeyRepository(File(folder.root, "secure"), FakeSecretCipher(), dispatcher),
            store,
            QuestionRepository(FakeQuestionDao(), requests, DirectTransactions, CaptureRepository(folder.root, dispatcher), dispatcher),
            trialId,
        )

    private fun logRequest(at: Long) =
        runBlocking {
            requests.insert(
                AiRequestEntity(questionId = "q$at", model = "gpt-6-luna", mode = AnalysisMode.TEXT, startedAt = at),
            )
        }

    // Phase 3.6: the trial switch exists only in the trial build and is off by default.
    @Test
    fun theTrialSwitch_isOffByDefault_andOnlyInTheTrialBuild() {
        val regular = newViewModel()
        assertFalse(regular.uiState.value.trialAvailable)
        regular.setAutoTrialArmed(true, nowMs = 5_000L)
        assertFalse(store.saved.autoTrialArmed)
        assertNull(store.saved.autoTrialStartedAt)

        val trial = newViewModel(trialId = 1)
        assertTrue(trial.uiState.value.trialAvailable)
        assertFalse(trial.uiState.value.settings.autoTrialArmed)
        assertEquals(3, trial.uiState.value.trialRequestsLeft)
        trial.setAutoTrialArmed(true, nowMs = 5_000L)
        assertTrue(store.saved.autoTrialArmed)
        assertEquals(5_000L, store.saved.autoTrialStartedAt)
        trial.setAutoTrialArmed(false)
        assertFalse(store.saved.autoTrialArmed)
    }

    // Arming again keeps the first start; once the three are used, it can't be armed again.
    @Test
    fun theTrial_cannotBeRearmedForMoreRequests() {
        val trial = newViewModel(trialId = 1)
        trial.setAutoTrialArmed(true, nowMs = 5_000L)
        logRequest(6_000L)
        trial.setAutoTrialArmed(false)
        trial.setAutoTrialArmed(true, nowMs = 9_000L)
        assertEquals(5_000L, store.saved.autoTrialStartedAt)
        assertEquals(2, trial.uiState.value.trialRequestsLeft)

        logRequest(9_500L)
        logRequest(9_600L)
        trial.setAutoTrialArmed(false)
        val reopened = newViewModel(trialId = 1)
        assertEquals(0, reopened.uiState.value.trialRequestsLeft)
        reopened.setAutoTrialArmed(true, nowMs = 20_000L)
        assertFalse(store.saved.autoTrialArmed)
        assertEquals(5_000L, store.saved.autoTrialStartedAt)
    }

    @Test
    fun startsWithoutAKey() {
        assertEquals(ApiKeyState.Missing, newViewModel().uiState.value.keyState)
    }

    @Test
    fun savingAndRemovingTheKey_updatesTheState_withoutExposingIt() {
        val viewModel = newViewModel()

        viewModel.saveKey(key)
        assertEquals(ApiKeyState.Stored("sk-…6789"), viewModel.uiState.value.keyState)
        // The UI state only ever carries the mask.
        assertFalse(
            viewModel.uiState.value
                .toString()
                .contains(key),
        )

        viewModel.removeKey()
        assertEquals(ApiKeyState.Missing, viewModel.uiState.value.keyState)
    }

    @Test
    fun invalidKey_showsTheReason_andKeepsThePreviousKey() {
        val viewModel = newViewModel()
        viewModel.saveKey(key)

        viewModel.saveKey("sk-short")

        assertEquals(ApiKeyInputError.TOO_SHORT, viewModel.uiState.value.keyError)
        assertEquals(ApiKeyState.Stored("sk-…6789"), viewModel.uiState.value.keyState)
    }

    @Test
    fun modelChoices_arePersisted() {
        val viewModel = newViewModel()

        viewModel.selectPrimaryModel(AiModel.GPT_5_6_LUNA)
        viewModel.selectAdvancedModel(AiModel.GPT_6_ASTRA)

        assertEquals(AiModel.GPT_5_6_LUNA, store.saved.primaryModel)
        assertEquals(
            AiModel.GPT_6_ASTRA,
            newViewModel()
                .uiState.value.settings.advancedModel,
        )
    }
}
