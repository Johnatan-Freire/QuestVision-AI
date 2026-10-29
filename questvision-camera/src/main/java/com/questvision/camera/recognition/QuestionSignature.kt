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
package com.questvision.camera.recognition

import java.text.Normalizer

/** Words that flip what is asked (accent-free, lowercase). */
private val NEGATIONS =
    setOf(
        "nao",
        "nunca",
        "nenhum",
        "nenhuma",
        "jamais",
        "exceto",
        "salvo",
        "sem",
        "menos",
        "incorreta",
        "incorreto",
        "incorretas",
        "incorretos",
        "falsa",
        "falso",
        "falsas",
        "falsos",
        "errada",
        "errado",
        "erradas",
        "errados",
        "invalida",
        "invalido",
        "inadequada",
        "inadequado",
        "not",
        "except",
        "incorrect",
        "false",
        "never",
        "none",
        "wrong",
    )

/** Numbers as written: 10, 3,5, 1.024, 2026. */
private val NUMBER = Regex("""\d+(?:[.,]\d+)*""")

/** Operators that change a value; "-" and "/" only between digits (else they are dashes and "e/ou"). */
private val OPERATOR = Regex("""[+×÷=<>≤≥≠%^√*∑∫−]|(?<=\d)\s*[-/]\s*(?=\d)""")

/**
 * What distinguishes a question from a near-identical one, taken from the question's own lines (title
 * excluded: the same question may be numbered differently elsewhere). OCR noise tolerance applies to
 * ordinary words only; these parts must match exactly, so "Qual está correta?" and "Qual está
 * incorreta?", or alternatives 10 and 12, are never the same question.
 */
data class QuestionSignature(
    val negations: Map<String, Int>,
    val numbers: Map<String, Int>,
    val operators: Map<String, Int>,
    /** Label → alternative text, normalized ("B" → "dns"). */
    val alternatives: Map<String, String>,
) {
    companion object {
        /**
         * [statement] lines (without the title) and [alternatives] (label → text as read). Labels are
         * stripped by the caller, so "A)" never counts as an operator or a number.
         */
        fun of(
            statement: List<String>,
            alternatives: Map<String, String>,
        ): QuestionSignature {
            val body = statement + alternatives.values
            val words = body.flatMap { line -> normalize(line).split(Regex("""[^a-z0-9]+""")) }
            return QuestionSignature(
                negations = words.filter { it in NEGATIONS }.groupingBy { it }.eachCount(),
                numbers = body.flatMap { line -> NUMBER.findAll(line).map { it.value.replace(',', '.') } }.groupingBy { it }.eachCount(),
                operators = body.flatMap { line -> OPERATOR.findAll(line).map { it.value.trim() } }.groupingBy { it }.eachCount(),
                alternatives = alternatives.mapValues { (_, text) -> normalize(text).replace(Regex("""[^a-z0-9]+"""), " ").trim() },
            )
        }

        private fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
    }
}

/**
 * Whether this and [other] are certainly different questions. Unknown signatures (null) never
 * differ: the word comparison alone decides, as before.
 */
fun QuestionSignature?.differsFrom(other: QuestionSignature?): Boolean {
    if (this == null || other == null) return false
    if (negations != other.negations || numbers != other.numbers || operators != other.operators) return true
    if (alternatives.keys != other.alternatives.keys) return alternatives.isNotEmpty() && other.alternatives.isNotEmpty()
    // One misread character per eight is OCR noise; more is another alternative.
    return alternatives.any { (label, text) ->
        val otherText = other.alternatives.getValue(label)
        editDistance(text, otherText) > maxOf(1, maxOf(text.length, otherText.length) / 8)
    }
}

private fun editDistance(
    a: String,
    b: String,
): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        previous = current
    }
    return previous[b.length]
}
