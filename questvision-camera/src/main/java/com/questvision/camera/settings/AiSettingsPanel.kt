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

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.camera.coreui.overlay.SettingsDropdown
import com.android.camera.coreui.overlay.SettingsHeader
import com.android.camera.coreui.overlay.SettingsOverlay
import com.android.camera.coreui.overlay.SettingsRow
import com.questvision.camera.R
import java.util.Locale

/**
 * QuestVision settings: the OpenAI section (encrypted API key, security notice, spend-limit advice)
 * and the model choices. Screenshots are blocked while it is open.
 */
@Composable
fun AiSettingsPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AiSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(onBack = onDismiss)
    SecureWindow()

    SettingsOverlay(visible = true, onDismiss = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsHeader(text = stringResource(R.string.questvision_settings_openai))
            ApiKeySection(state = state, viewModel = viewModel)
            Notice(text = stringResource(R.string.questvision_settings_key_warning))
            Notice(text = stringResource(R.string.questvision_settings_spend_limit, state.settings.monthlyBudgetUsd))

            SettingsHeader(text = stringResource(R.string.questvision_settings_models))
            SettingsDropdown(
                label = stringResource(R.string.questvision_settings_primary_model),
                options = AiModel.primaryOptions,
                selected = state.settings.primaryModel,
                onSelected = viewModel::selectPrimaryModel,
                optionLabel = { it.id },
            )
            SettingsDropdown(
                label = stringResource(R.string.questvision_settings_advanced_model),
                options = AiModel.advancedOptions,
                selected = state.settings.advancedModel,
                onSelected = viewModel::selectAdvancedModel,
                optionLabel = { it.id },
            )
            SettingsRow(label = stringResource(R.string.questvision_settings_budget)) {
                Text(
                    text = String.format(Locale.US, "US$ %.2f", state.settings.monthlyBudgetUsd),
                    color = Color.White,
                )
            }

            SettingsHeader(text = stringResource(R.string.questvision_settings_limits))
            SettingsDropdown(
                label = stringResource(R.string.questvision_settings_requests_per_day),
                options = AiSettings.requestsPerDayOptions,
                selected = state.settings.requestsPerDay,
                onSelected = viewModel::selectRequestsPerDay,
                optionLabel = { it.toString() },
            )
            SettingsDropdown(
                label = stringResource(R.string.questvision_settings_max_output_tokens),
                options = AiSettings.maxOutputTokensOptions,
                selected = state.settings.maxOutputTokens,
                onSelected = viewModel::selectMaxOutputTokens,
                optionLabel = { it.toString() },
            )
            SettingsDropdown(
                label = stringResource(R.string.questvision_settings_image_max_dimension),
                options = AiSettings.imageMaxDimensionOptions,
                selected = state.settings.imageMaxDimension,
                onSelected = viewModel::selectImageMaxDimension,
                optionLabel = { it.toString() },
            )
            Notice(text = stringResource(R.string.questvision_settings_not_sending_yet))
        }
    }
}

@Composable
private fun ApiKeySection(
    state: AiSettingsUiState,
    viewModel: AiSettingsViewModel,
) {
    // Plain remember, not rememberSaveable: the typed key must never be written into the saved
    // instance state that Android may persist to disk.
    var input by remember { mutableStateOf("") }
    var confirmRemoval by remember { mutableStateOf(false) }

    SettingsRow(label = stringResource(R.string.questvision_settings_api_key)) {
        Text(
            text =
                when (val keyState = state.keyState) {
                    ApiKeyState.Checking -> stringResource(R.string.questvision_settings_key_checking)
                    ApiKeyState.Missing -> stringResource(R.string.questvision_settings_key_missing)
                    ApiKeyState.Unreadable -> stringResource(R.string.questvision_settings_key_unreadable)
                    is ApiKeyState.Stored -> keyState.masked
                },
            color = if (state.keyState == ApiKeyState.Unreadable) MaterialTheme.colorScheme.error else Color.White,
        )
    }

    OutlinedTextField(
        value = input,
        onValueChange = {
            input = it
            if (state.keyError != null || state.keySaveFailed) viewModel.dismissKeyError()
        },
        label = {
            Text(
                stringResource(
                    if (state.keyState is ApiKeyState.Stored) {
                        R.string.questvision_settings_key_replace_hint
                    } else {
                        R.string.questvision_settings_key_hint
                    },
                ),
            )
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
        keyboardActions =
            KeyboardActions(onDone = {
                viewModel.saveKey(input)
                input = ""
            }),
        isError = state.keyError != null || state.keySaveFailed,
        supportingText =
            keyErrorText(state)?.let { message ->
                { Text(text = message, color = MaterialTheme.colorScheme.error) }
            },
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                viewModel.saveKey(input)
                // Clear the field right away; the key now lives only in the encrypted file.
                input = ""
                confirmRemoval = false
            },
            enabled = !state.busy && input.isNotBlank(),
        ) {
            Text(
                stringResource(
                    if (state.keyState is ApiKeyState.Stored) {
                        R.string.questvision_settings_key_replace
                    } else {
                        R.string.questvision_settings_key_save
                    },
                ),
            )
        }
        if (state.keyState is ApiKeyState.Stored || state.keyState == ApiKeyState.Unreadable) {
            OutlinedButton(
                onClick = {
                    if (confirmRemoval) {
                        viewModel.removeKey()
                        confirmRemoval = false
                    } else {
                        confirmRemoval = true
                    }
                },
                enabled = !state.busy,
            ) {
                Text(
                    stringResource(
                        if (confirmRemoval) {
                            R.string.questvision_settings_key_remove_confirm
                        } else {
                            R.string.questvision_settings_key_remove
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun keyErrorText(state: AiSettingsUiState): String? =
    when {
        state.keySaveFailed -> {
            stringResource(R.string.questvision_settings_key_save_failed)
        }

        else -> {
            when (state.keyError) {
                null -> null
                ApiKeyInputError.EMPTY -> stringResource(R.string.questvision_settings_key_error_empty)
                ApiKeyInputError.WRONG_PREFIX -> stringResource(R.string.questvision_settings_key_error_prefix)
                ApiKeyInputError.TOO_SHORT -> stringResource(R.string.questvision_settings_key_error_short)
                ApiKeyInputError.INVALID_CHARACTERS -> stringResource(R.string.questvision_settings_key_error_chars)
            }
        }
    }

@Composable
private fun Notice(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.65f),
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/** Blocks screenshots, screen recording and the recents thumbnail while composed. */
@Composable
private fun SecureWindow() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
