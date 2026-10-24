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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun newViewModel() =
        AiSettingsViewModel(
            OpenAiKeyRepository(File(folder.root, "secure"), FakeSecretCipher(), dispatcher),
            store,
        )

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
