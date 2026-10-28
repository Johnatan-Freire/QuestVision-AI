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

import com.questvision.camera.data.AnalysisMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Longest explanation kept; the prompt asks for three sentences. */
private const val MAX_EXPLANATION_CHARS = 2_000

/** Longest single answer entry kept (a letter, or a short answer for questions without alternatives). */
private const val MAX_ANSWER_CHARS = 200

enum class AnswerType(
    val wire: String,
) {
    SINGLE("single"),
    MULTIPLE("multiple"),
    NONE_CORRECT("none_correct"),
    CANNOT_DETERMINE("cannot_determine"),
    ;

    companion object {
        fun fromWire(value: String?): AnswerType? = entries.firstOrNull { it.wire == value }
    }
}

/** The model's own, qualitative certainty. Not a calibrated probability of being right. */
enum class AnswerConfidence(
    val wire: String,
) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    ;

    companion object {
        fun fromWire(value: String?): AnswerConfidence? = entries.firstOrNull { it.wire == value }
    }
}

/** A validated answer, as stored in `questions.ai_response`. */
data class AiAnswer(
    val questionNumber: Int?,
    val type: AnswerType,
    /** Letters of the correct alternatives (or a short answer); empty for none/undetermined. */
    val answer: List<String>,
    val explanation: String,
    val confidence: AnswerConfidence,
    /** Set by the model, and forced by the app whenever the answer is uncertain or inconsistent. */
    val needsReview: Boolean,
    /** What was sent: text or image. Null only for answers stored without it. */
    val mode: AnalysisMode?,
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parses the model's structured output (or a stored answer). Returns null when a required field is
 * missing or has the wrong type. Consistency is checked here rather than trusted: an answer whose
 * type and letters disagree, a "none"/"undetermined" verdict or a low confidence always needs review.
 */
fun parseAiAnswer(
    text: String,
    mode: AnalysisMode? = null,
): AiAnswer? {
    val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    val type = AnswerType.fromWire((root["answer_type"] as? JsonPrimitive)?.content) ?: return null
    val confidence = AnswerConfidence.fromWire((root["confidence"] as? JsonPrimitive)?.content) ?: return null
    val explanation = (root["explanation"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val modelReview = (root["needs_review"] as? JsonPrimitive)?.booleanOrNull ?: return null
    val answers =
        (root["answer"] as? JsonArray)
            ?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
            ?: return null
    val number =
        when (val raw = root["question_number"]) {
            null, JsonNull -> null
            else -> (raw as? JsonPrimitive)?.intOrNull
        }
    val letters = answers.map { normalizeAnswer(it) }.filter { it.isNotEmpty() }.distinct()
    val consistent =
        when (type) {
            AnswerType.SINGLE -> letters.size == 1
            AnswerType.MULTIPLE -> letters.size >= 2
            AnswerType.NONE_CORRECT, AnswerType.CANNOT_DETERMINE -> letters.isEmpty()
        }
    val needsReview =
        modelReview ||
            !consistent ||
            type == AnswerType.NONE_CORRECT ||
            type == AnswerType.CANNOT_DETERMINE ||
            confidence == AnswerConfidence.LOW ||
            explanation.isBlank()
    return AiAnswer(
        questionNumber = number?.takeIf { it in 0..9_999 },
        type = type,
        answer = if (type == AnswerType.NONE_CORRECT || type == AnswerType.CANNOT_DETERMINE) emptyList() else letters,
        explanation = explanation.trim().take(MAX_EXPLANATION_CHARS),
        confidence = confidence,
        needsReview = needsReview,
        mode = mode ?: (root["input_mode"] as? JsonPrimitive)?.content?.let { m -> AnalysisMode.entries.firstOrNull { it.name == m } },
    )
}

/** "b)" / " B " / "Alternativa B" → "B"; anything else is kept as a short free answer. */
private fun normalizeAnswer(raw: String): String {
    val trimmed = raw.trim().take(MAX_ANSWER_CHARS)
    val letter = Regex("""^(?:alternativa\s+)?\(?([A-Ea-e])\)?[.)]?$""", RegexOption.IGNORE_CASE).matchEntire(trimmed)
    return letter?.groupValues?.get(1)?.uppercase() ?: trimmed
}

/** The canonical JSON stored in `questions.ai_response`. */
fun AiAnswer.toStoredJson(): String =
    buildJsonObject {
        put("question_number", questionNumber?.let(::JsonPrimitive) ?: JsonNull)
        put("answer_type", type.wire)
        putJsonArray("answer") { answer.forEach { add(it) } }
        put("explanation", explanation)
        put("confidence", confidence.wire)
        put("needs_review", needsReview)
        mode?.let { put("input_mode", it.name) }
    }.toString()

/** Reads `questions.ai_response`; null when there is none or it is unreadable. */
fun storedAiAnswer(stored: String?): AiAnswer? = stored?.let { parseAiAnswer(it) }
