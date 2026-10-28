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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.camera.coretheme.monoFontFamily
import com.android.camera.coreui.overlay.SettingsDropdown
import com.android.camera.coreui.overlay.SettingsHeader
import com.android.camera.coreui.overlay.SettingsOverlay
import com.android.camera.coreui.overlay.SettingsRow
import com.questvision.camera.R
import com.questvision.camera.data.AiStatus
import com.questvision.camera.data.AnalysisMode
import java.io.File
import java.util.Locale

private val ReviewColor = Color(0xFFFFC857)

/**
 * The AI analysis of the photo under review: mode (text by default, or image), the model, a manual
 * Analyze / Analyze again button, progress with Cancel, and the stored answer with its explanation.
 */
@Composable
fun AiAnalysisPanel(
    file: File,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AiAnalysisViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(file) { viewModel.select(file) }
    BackHandler(onBack = onDismiss)

    SettingsOverlay(visible = true, onDismiss = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsHeader(text = stringResource(R.string.questvision_ai_title))
            when {
                state.loading -> {
                    Note(text = stringResource(R.string.questvision_ai_loading))
                }

                !state.recorded -> {
                    Note(text = stringResource(R.string.questvision_ai_not_recorded), color = MaterialTheme.colorScheme.error)
                }

                else -> {
                    AnalysisContent(state = state, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun AnalysisContent(
    state: AiAnalysisUiState,
    viewModel: AiAnalysisViewModel,
) {
    SettingsRow(label = stringResource(R.string.questvision_ai_model)) {
        Text(text = state.model.id, color = Color.White, style = TextStyle(fontFamily = monoFontFamily, fontSize = 12.sp))
    }
    val modeLabels = AnalysisMode.entries.associateWith { modeLabel(it) }
    SettingsDropdown(
        label = stringResource(R.string.questvision_ai_mode),
        options = AnalysisMode.entries,
        selected = state.mode,
        onSelected = viewModel::selectMode,
        optionLabel = { modeLabels.getValue(it) },
    )
    if (state.mode == AnalysisMode.TEXT && !state.textAvailable) {
        Note(text = stringResource(R.string.questvision_ai_text_unavailable))
    }
    SettingsRow(label = stringResource(R.string.questvision_ai_status)) {
        Text(
            text = statusLabel(if (state.running) AiStatus.PROCESSING else state.status),
            color = statusColor(if (state.running) AiStatus.PROCESSING else state.status),
        )
    }
    if (!state.running && state.status == AiStatus.FAILED) {
        state.lastError?.let { Note(text = errorLabel(it), color = MaterialTheme.colorScheme.error) }
    }
    state.rejection?.let { Note(text = rejectionLabel(it), color = MaterialTheme.colorScheme.error) }
    if (state.busyElsewhere) Note(text = stringResource(R.string.questvision_ai_busy_elsewhere))

    state.answer?.let { AnswerCard(answer = it, model = state.answeredWith) }

    state.lastRequest?.let { request ->
        Note(
            text =
                stringResource(
                    R.string.questvision_ai_last_request,
                    modeLabel(request.mode),
                    request.serviceTier ?: "–",
                    request.inputTokens?.toString() ?: "–",
                    request.outputTokens?.toString() ?: "–",
                ),
        )
        Note(
            text =
                state.lastRequestCostUsd?.let { stringResource(R.string.questvision_ai_observed_cost, formatUsd(it)) }
                    ?: stringResource(R.string.questvision_ai_observed_cost_unavailable),
        )
    }
    Note(text = stringResource(R.string.questvision_ai_requests_window, state.requestsInWindow, state.requestLimit))
    state.estimate?.let { estimate ->
        Note(text = stringResource(R.string.questvision_ai_estimate, formatUsd(estimate.totalUsd)))
        if (estimate.excludesImage) Note(text = stringResource(R.string.questvision_ai_estimate_excludes_image), color = ReviewColor)
    }

    val confirming = state.confirming
    if (confirming != null) {
        ConfirmationBox(confirmation = confirming, state = state, viewModel = viewModel)
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.running) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                OutlinedButton(onClick = viewModel::cancel) { Text(stringResource(R.string.questvision_ai_cancel)) }
            } else {
                Button(onClick = viewModel::analyze, enabled = state.canAnalyze) {
                    Text(
                        stringResource(
                            if (state.attempts > 0) R.string.questvision_ai_analyze_again else R.string.questvision_ai_analyze,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AnswerCard(
    answer: AiAnswer,
    model: String?,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(Color.White.copy(alpha = 0.04f), shape)
                .border(1.dp, if (answer.needsReview) ReviewColor else MaterialTheme.colorScheme.primary, shape)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.questvision_ai_answer_label),
            style = TextStyle(fontFamily = monoFontFamily, fontSize = 10.sp),
            color = Color.White.copy(alpha = 0.6f),
        )
        Text(
            text =
                when (answer.type) {
                    AnswerType.NONE_CORRECT -> stringResource(R.string.questvision_ai_answer_none)
                    AnswerType.CANNOT_DETERMINE -> stringResource(R.string.questvision_ai_answer_unknown)
                    else -> answer.answer.joinToString(", ")
                },
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        answer.questionNumber?.let {
            Text(text = stringResource(R.string.questvision_ai_question_number, it), color = Color.White.copy(alpha = 0.7f))
        }
        Text(text = answer.explanation, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Text(
            text = stringResource(R.string.questvision_ai_confidence, confidenceLabel(answer.confidence)),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.65f),
        )
        if (answer.needsReview) {
            Text(
                text = stringResource(R.string.questvision_ai_needs_review),
                style = MaterialTheme.typography.bodySmall,
                color = ReviewColor,
            )
        }
        Text(
            text =
                listOfNotNull(answer.mode?.let { modeLabel(it) }, model).joinToString(" · "),
            style = TextStyle(fontFamily = monoFontFamily, fontSize = 10.sp),
            color = Color.White.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun ConfirmationBox(
    confirmation: Confirmation,
    state: AiAnalysisUiState,
    viewModel: AiAnalysisViewModel,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .border(1.dp, ReviewColor, RoundedCornerShape(12.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text =
                when (confirmation) {
                    Confirmation.FIRST_CALL -> {
                        stringResource(
                            R.string.questvision_ai_confirm_first,
                            state.model.id,
                            modeLabel(state.mode),
                            formatUsd(state.estimate?.totalUsd ?: 0.0),
                        )
                    }

                    Confirmation.RESEND_UNKNOWN_OUTCOME -> {
                        stringResource(R.string.questvision_ai_confirm_resend_unknown)
                    }
                },
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::confirm) { Text(stringResource(R.string.questvision_ai_confirm_send)) }
            OutlinedButton(onClick = viewModel::dismissConfirmation) { Text(stringResource(R.string.questvision_ai_confirm_not_now)) }
        }
    }
}

@Composable
private fun Note(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.65f),
) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier.padding(vertical = 4.dp))
}

private fun formatUsd(value: Double): String = String.format(Locale.US, "%.4f", value)

@Composable
private fun modeLabel(mode: AnalysisMode): String =
    stringResource(
        when (mode) {
            AnalysisMode.TEXT -> R.string.questvision_ai_mode_text
            AnalysisMode.IMAGE -> R.string.questvision_ai_mode_image
        },
    )

@Composable
private fun statusLabel(status: AiStatus?): String =
    stringResource(
        when (status) {
            null, AiStatus.NOT_REQUESTED -> R.string.questvision_ai_status_not_requested
            AiStatus.PENDING -> R.string.questvision_ai_status_pending
            AiStatus.PROCESSING -> R.string.questvision_ai_status_processing
            AiStatus.ANSWERED -> R.string.questvision_ai_status_answered
            AiStatus.NEEDS_REVIEW -> R.string.questvision_ai_status_needs_review
            AiStatus.FAILED -> R.string.questvision_ai_status_failed
        },
    )

@Composable
private fun statusColor(status: AiStatus?): Color =
    when (status) {
        AiStatus.ANSWERED -> MaterialTheme.colorScheme.primary
        AiStatus.NEEDS_REVIEW -> ReviewColor
        AiStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> Color.White
    }

@Composable
private fun confidenceLabel(confidence: AnswerConfidence): String =
    stringResource(
        when (confidence) {
            AnswerConfidence.HIGH -> R.string.questvision_ai_confidence_high
            AnswerConfidence.MEDIUM -> R.string.questvision_ai_confidence_medium
            AnswerConfidence.LOW -> R.string.questvision_ai_confidence_low
        },
    )

@Composable
private fun errorLabel(error: AiError): String =
    stringResource(
        when (error) {
            AiError.AUTH -> R.string.questvision_ai_error_auth
            AiError.FORBIDDEN -> R.string.questvision_ai_error_forbidden
            AiError.RATE_LIMIT -> R.string.questvision_ai_error_rate_limit
            AiError.QUOTA -> R.string.questvision_ai_error_quota
            AiError.BAD_REQUEST -> R.string.questvision_ai_error_bad_request
            AiError.SERVER -> R.string.questvision_ai_error_server
            AiError.NETWORK -> R.string.questvision_ai_error_network
            AiError.TIMEOUT -> R.string.questvision_ai_error_timeout
            AiError.CANCELLED -> R.string.questvision_ai_error_cancelled
            AiError.INVALID_RESPONSE -> R.string.questvision_ai_error_invalid
            AiError.INCOMPLETE_OUTPUT -> R.string.questvision_ai_error_incomplete
            AiError.REFUSAL -> R.string.questvision_ai_error_refusal
            AiError.INTERRUPTED -> R.string.questvision_ai_error_interrupted
            AiError.UNEXPECTED -> R.string.questvision_ai_error_unexpected
        },
    )

@Composable
private fun rejectionLabel(reason: AnalysisRejection): String =
    stringResource(
        when (reason) {
            AnalysisRejection.NO_KEY -> R.string.questvision_ai_reject_no_key
            AnalysisRejection.NOT_RECORDED -> R.string.questvision_ai_not_recorded
            AnalysisRejection.ALREADY_PROCESSING -> R.string.questvision_ai_reject_processing
            AnalysisRejection.REQUEST_LIMIT -> R.string.questvision_ai_reject_request_limit
            AnalysisRejection.INSUFFICIENT_TEXT -> R.string.questvision_ai_text_unavailable
            AnalysisRejection.IMAGE_UNAVAILABLE -> R.string.questvision_ai_reject_image
        },
    )
