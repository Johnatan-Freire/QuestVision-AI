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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.camera.coretheme.monoFontFamily
import com.questvision.camera.R
import java.util.Locale

/**
 * What the automatic analysis is doing during a monitoring session: its phase or why it paused, the
 * last event, the requests and observed cost so far, and the last answer. Shown only while a session
 * runs or an answer of it is on screen.
 */
@Composable
fun AutoAnalysisStatus(
    state: AutoAnalysisState,
    modifier: Modifier = Modifier,
) {
    if (state.phase == AutoPhase.IDLE && state.lastAnswer == null) return
    val small = TextStyle(fontFamily = monoFontFamily, fontSize = 11.sp)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = phaseLabel(state),
            style = small,
            color = if (state.phase == AutoPhase.PAUSED) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
        state.lastEvent?.takeIf { it !is AutoEvent.Answered }?.let { event ->
            Text(text = eventLabel(event), style = small, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
        }
        Text(
            text =
                stringResource(
                    R.string.questvision_auto_usage,
                    state.requests,
                    state.sessionLimit,
                    String.format(Locale.US, "%.6f", state.observedCostUsd),
                ),
            style = small,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        state.lastAnswer?.let { answer ->
            Text(
                text = answerLabel(answer),
                style = MaterialTheme.typography.bodyMedium,
                color = if (answer.needsReview) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun phaseLabel(state: AutoAnalysisState): String =
    when {
        state.phase == AutoPhase.PAUSED -> {
            stringResource(
                when (state.pauseReason) {
                    AutoPauseReason.SESSION_LIMIT -> R.string.questvision_auto_paused_session_limit
                    AutoPauseReason.DAILY_LIMIT -> R.string.questvision_auto_paused_daily_limit
                    AutoPauseReason.NO_KEY -> R.string.questvision_auto_paused_no_key
                    AutoPauseReason.CONNECTION -> R.string.questvision_auto_paused_connection
                    AutoPauseReason.API_ERROR -> R.string.questvision_auto_paused_api_error
                    AutoPauseReason.USER, null -> R.string.questvision_auto_paused
                },
            )
        }

        !state.realRequestsAllowed -> {
            stringResource(R.string.questvision_auto_disabled, state.awaitingAuthorization)
        }

        state.phase == AutoPhase.ANALYZING -> {
            stringResource(R.string.questvision_auto_analyzing, state.queued)
        }

        state.phase == AutoPhase.WAITING -> {
            stringResource(R.string.questvision_auto_waiting)
        }

        else -> {
            stringResource(R.string.questvision_auto_idle)
        }
    }

@Composable
private fun eventLabel(event: AutoEvent): String {
    val number = event.questionNumber?.toString() ?: "?"
    return when (event) {
        is AutoEvent.Queued -> stringResource(R.string.questvision_auto_event_queued, number)
        is AutoEvent.Incomplete -> stringResource(R.string.questvision_auto_event_incomplete, number, issueLabel(event.issue))
        is AutoEvent.AlreadyAnalyzed -> stringResource(R.string.questvision_auto_event_known, number)
        is AutoEvent.AwaitingAuthorization -> stringResource(R.string.questvision_auto_event_ready, number)
        is AutoEvent.Failed -> stringResource(R.string.questvision_auto_event_failed, number, errorLabel(event.error))
        is AutoEvent.Skipped -> stringResource(R.string.questvision_auto_event_skipped, number)
        is AutoEvent.Answered -> answerLabel(event)
    }
}

@Composable
private fun issueLabel(issue: AssessmentIssue): String =
    stringResource(
        when (issue) {
            AssessmentIssue.NO_QUESTION_START -> R.string.questvision_issue_no_start
            AssessmentIssue.NO_STATEMENT -> R.string.questvision_issue_no_statement
            AssessmentIssue.NO_ALTERNATIVES -> R.string.questvision_issue_no_alternatives
            AssessmentIssue.ALTERNATIVES_OUT_OF_SEQUENCE -> R.string.questvision_issue_sequence
            AssessmentIssue.POSSIBLY_CUT -> R.string.questvision_issue_cut
            AssessmentIssue.LOW_CONFIDENCE -> R.string.questvision_issue_low_confidence
        },
    )

@Composable
private fun answerLabel(answer: AutoEvent.Answered): String {
    val number = answer.questionNumber?.toString() ?: "?"
    val letters = answer.answer.joinToString(", ").ifEmpty { "—" }
    val text = answer.alternativeText?.let { " — $it" }.orEmpty()
    val review = if (answer.needsReview) stringResource(R.string.questvision_auto_review_suffix) else ""
    return stringResource(R.string.questvision_auto_answer, number, letters + text) + review
}
