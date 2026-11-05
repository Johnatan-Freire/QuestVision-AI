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

import android.content.Context
import com.questvision.camera.R
import com.questvision.camera.ai.AiAnswer
import com.questvision.camera.ai.AnswerType
import com.questvision.camera.ai.FramingHint
import com.questvision.camera.ai.alternativeText
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The words of the Telegram message, from the app's string resources (a seam for tests). */
interface TelegramTexts {
    fun numbered(
        number: Int,
        body: String,
    ): String

    fun unnumbered(body: String): String

    fun needsReview(message: String): String

    val noneCorrect: String
    val cannotDetermine: String
    val test: String

    /** "Enquadramento" on the first line, the short tip on the second. */
    fun framing(hint: FramingHint): String
}

class ResourceTelegramTexts
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : TelegramTexts {
        override fun numbered(
            number: Int,
            body: String,
        ) = context.getString(R.string.questvision_telegram_numbered, number, body)

        override fun unnumbered(body: String) = context.getString(R.string.questvision_telegram_unnumbered, body)

        override fun needsReview(message: String) = context.getString(R.string.questvision_telegram_needs_review, message)

        override val noneCorrect: String get() = context.getString(R.string.questvision_telegram_none_correct)
        override val cannotDetermine: String get() = context.getString(R.string.questvision_telegram_cannot_determine)
        override val test: String get() = context.getString(R.string.questvision_telegram_test)

        override fun framing(hint: FramingHint): String {
            val tip =
                when (hint) {
                    FramingHint.LEFT -> R.string.questvision_framing_left
                    FramingHint.RIGHT -> R.string.questvision_framing_right
                    FramingHint.UP -> R.string.questvision_framing_up
                    FramingHint.DOWN -> R.string.questvision_framing_down
                    FramingHint.FARTHER -> R.string.questvision_framing_farther
                    FramingHint.CLOSER -> R.string.questvision_framing_closer
                    FramingHint.NO_QUESTION -> R.string.questvision_framing_no_question
                    FramingHint.BLURRY -> R.string.questvision_framing_blurry
                    FramingHint.UNSTABLE -> R.string.questvision_framing_unstable
                }
            return context.getString(R.string.questvision_framing_title) + "\n" + context.getString(tip)
        }
    }

/**
 * The message for [answer]: the question number, the letter and the alternative's text as read from
 * the screen (e.g. "Questão 2: D) GROUP BY"). Never the statement or the explanation.
 */
fun telegramText(
    answer: AiAnswer,
    questionNumber: Int?,
    ocrText: String?,
    texts: TelegramTexts,
): String {
    val body =
        when (answer.type) {
            AnswerType.NONE_CORRECT -> {
                texts.noneCorrect
            }

            AnswerType.CANNOT_DETERMINE -> {
                texts.cannotDetermine
            }

            AnswerType.SINGLE, AnswerType.MULTIPLE -> {
                answer.answer.joinToString("; ") { letter ->
                    alternativeText(ocrText, letter)?.let { "$letter) $it" } ?: letter
                }
            }
        }.ifBlank { texts.cannotDetermine }
    val number = answer.questionNumber ?: questionNumber
    val message = if (number != null) texts.numbered(number, body) else texts.unnumbered(body)
    return if (answer.needsReview) texts.needsReview(message) else message
}
