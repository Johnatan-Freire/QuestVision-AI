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

import com.questvision.camera.data.QUESTION_NUMBER

/** An alternative line: "A) …", "(b) …", "C. …", "D - …". */
private val ALTERNATIVE = Regex("""^\(?([A-Ea-e])\s*[).:\-–]\s*\S""")

/** Fewest alternatives for a block to count as an objective question. */
private const val MIN_ALTERNATIVES = 2

/** Shortest block worth sending (a title, a statement and two alternatives are well above it). */
private const val MIN_QUESTION_CHARS = 40

/** The part of a screen's OCR text that is the question itself. */
data class ExtractedQuestion(
    val number: Int?,
    /** From the "Questão N" line to the last alternative, one OCR line per line. */
    val text: String,
    val alternatives: Int,
)

/**
 * Takes the question out of a screen's OCR text, leaving out the browser and app chrome around it, so
 * the text mode sends only the statement and the alternatives. When several questions are visible
 * (a scrolled chat), only the last one — the newest — is considered: if it is still incomplete, the
 * result is null rather than an earlier question, which would answer a different question than the
 * one captured.
 *
 * A question counts as complete with a "Questão N" title, a statement and at least two alternatives;
 * anything else returns null so the user picks the image mode instead of paying for a guess. Lines
 * after the last alternative are dropped (a wrapped last alternative loses its continuation: use the
 * image mode when that matters).
 */
fun extractQuestionText(ocrText: String?): ExtractedQuestion? {
    val lines = ocrText?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
    val start = lines.indexOfLast { QUESTION_NUMBER.containsMatchIn(it) }
    if (start < 0) return null
    val block = lines.subList(start, lines.size)
    val alternatives = block.indices.filter { ALTERNATIVE.containsMatchIn(block[it]) }
    if (alternatives.size < MIN_ALTERNATIVES) return null
    // A statement must sit between the title and the first alternative (or share the title line).
    val title = block.first()
    val titleHasStatement = QUESTION_NUMBER.find(title)?.let { title.length - it.range.last > 15 } == true
    if (alternatives.first() < 2 && !titleHasStatement) return null
    val text = block.subList(0, alternatives.last() + 1).joinToString("\n")
    if (text.length < MIN_QUESTION_CHARS) return null
    val number =
        QUESTION_NUMBER
            .find(title)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    return ExtractedQuestion(number, text, alternatives.size)
}
