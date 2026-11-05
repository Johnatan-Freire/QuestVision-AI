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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.android.camera.coreui.overlay.SettingsHeader
import com.android.camera.coreui.overlay.SettingsRow
import com.android.camera.coreui.overlay.settingsSwitchColors
import com.questvision.camera.R
import com.questvision.camera.data.TelegramStatus
import com.questvision.camera.settings.Notice
import java.text.DateFormat
import java.util.Date

/** Telegram: the encrypted bot token, the destination chat, the sending switch and a test message. */
@Composable
fun TelegramSettingsSection(
    modifier: Modifier = Modifier,
    viewModel: TelegramSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Plain remember: the typed token must never reach the saved instance state.
    var input by remember { mutableStateOf("") }
    var confirmRemoval by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsHeader(text = stringResource(R.string.questvision_telegram_header))
        Notice(text = stringResource(R.string.questvision_telegram_how_to))

        SettingsRow(label = stringResource(R.string.questvision_telegram_token)) {
            Text(
                text =
                    when (val token = state.tokenState) {
                        TelegramTokenState.Checking -> stringResource(R.string.questvision_settings_key_checking)
                        TelegramTokenState.Missing -> stringResource(R.string.questvision_settings_key_missing)
                        TelegramTokenState.Unreadable -> stringResource(R.string.questvision_telegram_token_unreadable)
                        is TelegramTokenState.Stored -> token.masked
                    },
                color = if (state.tokenState == TelegramTokenState.Unreadable) MaterialTheme.colorScheme.error else Color.White,
            )
        }
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                if (state.tokenError != null || state.tokenSaveFailed) viewModel.dismissTokenError()
            },
            label = { Text(stringResource(R.string.questvision_telegram_token_hint)) },
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
                    viewModel.saveToken(input)
                    input = ""
                }),
            isError = state.tokenError != null || state.tokenSaveFailed,
            supportingText =
                tokenErrorText(state)?.let { message ->
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
                    viewModel.saveToken(input)
                    input = ""
                    confirmRemoval = false
                },
                enabled = !state.busy && input.isNotBlank(),
            ) { Text(stringResource(R.string.questvision_settings_key_save)) }
            if (state.tokenStored || state.tokenState == TelegramTokenState.Unreadable) {
                OutlinedButton(
                    onClick = {
                        if (confirmRemoval) viewModel.removeToken()
                        confirmRemoval = !confirmRemoval
                    },
                    enabled = !state.busy,
                ) {
                    val label =
                        if (confirmRemoval) R.string.questvision_settings_key_remove_confirm else R.string.questvision_settings_key_remove
                    Text(stringResource(label))
                }
            }
        }

        SettingsRow(label = stringResource(R.string.questvision_telegram_chat)) {
            Text(
                text = state.chatId?.toString() ?: stringResource(R.string.questvision_telegram_chat_missing),
                color = Color.White,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = viewModel::detectChat, enabled = !state.busy && state.tokenStored) {
                Text(stringResource(R.string.questvision_telegram_detect_chat))
            }
            OutlinedButton(onClick = viewModel::sendTest, enabled = !state.busy && state.canEnable) {
                Text(stringResource(R.string.questvision_telegram_send_test))
            }
        }
        checkText(state.check)?.let { Notice(text = it) }

        SettingsRow(label = stringResource(R.string.questvision_telegram_enabled)) {
            Switch(
                checked = state.enabled,
                onCheckedChange = viewModel::setEnabled,
                enabled = state.enabled || state.canEnable,
                colors = settingsSwitchColors(),
            )
        }
        SettingsRow(label = stringResource(R.string.questvision_telegram_framing_hints)) {
            Switch(
                checked = state.framingHints,
                onCheckedChange = viewModel::setFramingHints,
                enabled = state.framingHints || state.canEnable,
                colors = settingsSwitchColors(),
            )
        }
        Notice(text = stringResource(R.string.questvision_telegram_framing_hints_notice))
        state.lastDelivery?.let { Notice(text = deliveryText(it)) }
        Notice(text = stringResource(R.string.questvision_telegram_privacy))
    }
}

@Composable
private fun tokenErrorText(state: TelegramSettingsUiState): String? =
    when {
        state.tokenSaveFailed -> stringResource(R.string.questvision_settings_key_save_failed)
        state.tokenError == TelegramTokenError.EMPTY -> stringResource(R.string.questvision_settings_key_error_empty)
        state.tokenError == TelegramTokenError.INVALID_FORMAT -> stringResource(R.string.questvision_telegram_token_invalid)
        else -> null
    }

@Composable
private fun checkText(check: TelegramCheck?): String? =
    when (check) {
        null -> {
            null
        }

        is TelegramCheck.ChatFound -> {
            check.firstName?.let { stringResource(R.string.questvision_telegram_chat_found_named, it) }
                ?: stringResource(R.string.questvision_telegram_chat_found)
        }

        TelegramCheck.NoMessages -> {
            stringResource(R.string.questvision_telegram_no_messages)
        }

        TelegramCheck.TestSent -> {
            stringResource(R.string.questvision_telegram_test_sent)
        }

        TelegramCheck.NoToken -> {
            stringResource(R.string.questvision_telegram_token_unreadable)
        }

        is TelegramCheck.Failed -> {
            stringResource(R.string.questvision_telegram_failed, errorText(check.error))
        }
    }

@Composable
private fun errorText(error: TelegramError): String =
    stringResource(
        when (error) {
            TelegramError.UNAUTHORIZED -> R.string.questvision_telegram_error_unauthorized
            TelegramError.CHAT_NOT_FOUND -> R.string.questvision_telegram_error_chat_not_found
            TelegramError.FORBIDDEN -> R.string.questvision_telegram_error_forbidden
            TelegramError.RATE_LIMIT -> R.string.questvision_telegram_error_rate_limit
            TelegramError.NETWORK -> R.string.questvision_telegram_error_network
            TelegramError.BAD_REQUEST, TelegramError.SERVER, TelegramError.INVALID_RESPONSE -> R.string.questvision_telegram_error_other
        },
    )

@Composable
private fun deliveryText(delivery: TelegramDelivery): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(delivery.at))
    val number = delivery.questionNumber?.toString() ?: "?"
    return when (delivery.status) {
        TelegramStatus.SENT -> {
            stringResource(R.string.questvision_telegram_last_sent, number, time)
        }

        TelegramStatus.UNKNOWN -> {
            stringResource(R.string.questvision_telegram_last_unknown, number, time)
        }

        else -> {
            stringResource(
                R.string.questvision_telegram_last_failed,
                number,
                time,
                delivery.error?.let { errorText(it) } ?: stringResource(R.string.questvision_telegram_token_unreadable),
            )
        }
    }
}
