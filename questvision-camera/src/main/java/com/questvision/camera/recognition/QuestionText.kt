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
import kotlin.math.abs
import kotlin.math.min

/** One recognized word and the OCR engine's confidence in it (0–1). */
data class RecognizedWord(
    val text: String,
    val confidence: Float,
)

/** OCR output: lines in reading order, each a list of words. */
data class RecognizedText(
    val lines: List<List<RecognizedWord>>,
)

/** Thresholds of the text comparison. Starting points, to be tuned from the `QuestVisionOcr` logs. */
data class TextMatchConfig(
    /** Words below this confidence never count as new text (likely misreads). */
    val minConfidence: Float = 0.5f,
    /** Fewer confident words than this means the capture is unreadable. */
    val minReadableTokens: Int = 8,
    /** New text if at least this many confident words are new… */
    val minNovelTokens: Int = 4,
    /** …and they are at least this share of the capture's words… */
    val minNovelRatio: Float = 0.08f,
    /** …or this many new words appear in a row on one line (e.g. a rewritten alternative). */
    val minNovelRun: Int = 3,
)

/**
 * The normalized text of one capture: per line, its tokens (lowercase, accent-free, alphanumeric),
 * after removing volatile text such as the taskbar clock. Two readings of the same screen rarely
 * produce identical text, so captures are compared by [QuestionTextMatcher], not by hash; [simHash]
 * is a compact fingerprint for logs and the exact-match shortcut.
 */
class TextFingerprint(
    val lines: List<List<Token>>,
) {
    data class Token(
        val value: String,
        val confident: Boolean,
    )

    val tokens: List<Token> = lines.flatten()
    val distinct: Set<String> = tokens.mapTo(HashSet()) { it.value }

    /** Each line's tokens run together, to recognize words the OCR split or glued differently. */
    val gluedLines: List<String> = lines.map { line -> line.joinToString("") { it.value } }
    val confidentCount: Int = tokens.count { it.confident }
    val simHash: Long = simHash(distinct)

    /** The first words, for logs. */
    fun preview(maxTokens: Int = 12): String = tokens.take(maxTokens).joinToString(" ") { it.value }

    companion object {
        fun of(
            text: RecognizedText,
            config: TextMatchConfig = TextMatchConfig(),
        ): TextFingerprint =
            TextFingerprint(
                text.lines
                    .map { line ->
                        line.mapNotNull { word ->
                            normalizeToken(word.text)?.let { Token(it, word.confidence >= config.minConfidence) }
                        }
                    }.filter { it.isNotEmpty() },
            )
    }
}

enum class TextVerdict {
    /** Enough new text: a question not captured before in this session. */
    NEW_QUESTION,

    /** The same text as an earlier capture, up to OCR noise. */
    DUPLICATE,

    /** Too little confident text to decide (blurred, glare, blank screen). */
    UNREADABLE,
}

/** How a capture's text compares with the closest earlier capture. */
data class TextComparison(
    val verdict: TextVerdict,
    /** Confident words of the capture absent (even approximately) from the closest earlier one. */
    val novelTokens: Int,
    val novelRatio: Float,
    /** Longest run of consecutive new words on one line. */
    val longestNovelRun: Int,
    /** Index in the history of the closest earlier capture, or -1. */
    val closestIndex: Int,
    val confidentTokens: Int,
    /** The new words (up to a few), for logs. */
    val novelWords: List<String> = emptyList(),
    /** The words of [longestNovelRun], for logs. */
    val longestRunWords: List<String> = emptyList(),
)

/** Decides whether recognized text is a new question, given the captures accepted so far. */
class QuestionTextMatcher(
    private val config: TextMatchConfig = TextMatchConfig(),
) {
    fun compare(
        candidate: TextFingerprint,
        history: List<TextFingerprint>,
    ): TextComparison {
        if (candidate.confidentCount < config.minReadableTokens) {
            return TextComparison(TextVerdict.UNREADABLE, 0, 0f, 0, -1, candidate.confidentCount)
        }
        // The closest earlier capture is the one leaving the fewest new words.
        val closest =
            history
                .withIndex()
                .map { (index, reference) -> index to novelty(candidate, reference) }
                .minByOrNull { (_, novelty) -> novelty.count }
        val novelty =
            closest?.second
                ?: Novelty(candidate.confidentCount, candidate.lines.maxOfOrNull { it.size } ?: 0, emptyList(), emptyList())
        val ratio = novelty.count.toFloat() / candidate.confidentCount
        val isNew =
            novelty.longestRun >= config.minNovelRun ||
                (novelty.count >= config.minNovelTokens && ratio >= config.minNovelRatio)
        return TextComparison(
            verdict = if (isNew) TextVerdict.NEW_QUESTION else TextVerdict.DUPLICATE,
            novelTokens = novelty.count,
            novelRatio = ratio,
            longestNovelRun = novelty.longestRun,
            closestIndex = closest?.first ?: -1,
            confidentTokens = candidate.confidentCount,
            novelWords = novelty.words.take(MAX_LOGGED_WORDS),
            longestRunWords = novelty.runWords,
        )
    }

    private data class Novelty(
        val count: Int,
        val longestRun: Int,
        val words: List<String>,
        val runWords: List<String>,
    )

    /**
     * Confident words of [candidate] that [reference] does not contain. Short words must match
     * exactly; longer ones may differ by one or two characters (OCR misreads); a word also counts as
     * present when it appears inside a reference line read without spaces (the OCR split or glued
     * words differently, e.g. "brb engenharia" vs "brbengenharia"). Low-confidence words never count
     * as new, and neither break nor extend a run.
     */
    private fun novelty(
        candidate: TextFingerprint,
        reference: TextFingerprint,
    ): Novelty {
        if (candidate.simHash == reference.simHash && candidate.distinct == reference.distinct) {
            return Novelty(0, 0, emptyList(), emptyList())
        }
        val novel = LinkedHashSet<String>()
        var longestRun = emptyList<String>()
        for (line in candidate.lines) {
            val run = ArrayList<String>()
            for (token in line) {
                if (!token.confident) continue
                if (isKnown(token.value, reference)) {
                    run.clear()
                } else {
                    novel += token.value
                    run += token.value
                    if (run.size > longestRun.size) longestRun = run.toList()
                }
            }
        }
        return Novelty(novel.size, longestRun.size, novel.toList(), longestRun)
    }

    private fun isKnown(
        token: String,
        reference: TextFingerprint,
    ): Boolean {
        if (token in reference.distinct) return true
        if (reference.gluedLines.any { token in it }) return true
        val allowed = allowedEdits(token.length)
        if (allowed == 0) return false
        return reference.distinct.any { abs(it.length - token.length) <= allowed && withinEdits(token, it, allowed) }
    }
}

private const val MAX_LOGGED_WORDS = 15

/** Typos tolerated in a word of [length] letters: none up to 4, one up to 7, two beyond. */
internal fun allowedEdits(length: Int): Int =
    when {
        length <= 4 -> 0
        length <= 7 -> 1
        else -> 2
    }

private val VOLATILE =
    listOf(
        // Clock times (taskbar, chat timestamps): 14:12, 14:12:24, 2:30pm.
        Regex("""^\d{1,2}[:h.]\d{2}([:.]\d{2})?(am|pm)?$""", RegexOption.IGNORE_CASE),
        // Dates: 22/10/2026, 22-10, 2026-10-22.
        Regex("""^\d{1,4}[/-]\d{1,2}([/-]\d{1,4})?$"""),
    )

private val COMBINING_MARKS = Regex("""\p{Mn}+""")
private val DIGIT = Regex("""\d""")
private val NON_ALPHANUMERIC = Regex("""[^a-z0-9]""")

/**
 * Lowercase, accent-free, alphanumeric form of an OCR word (Unicode-normalized, so ligatures and
 * full-width characters fold too). Returns null for volatile text (times, dates, URL and hash
 * fragments, which the OCR reads differently every time), punctuation, and single characters such
 * as alternative letters.
 */
internal fun normalizeToken(raw: String): String? {
    val trimmed = raw.trim()
    if (VOLATILE.any { it.matches(trimmed) }) return null
    val folded =
        Normalizer
            .normalize(trimmed, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
            .replace(NON_ALPHANUMERIC, "")
    return folded.takeIf { it.length >= 2 && !isHashLike(it) }
}

/** Long letter-and-digit runs such as URL paths and ids (`6acfe2a12e308191…`), not words. */
internal fun isHashLike(token: String): Boolean {
    if (token.length < 10) return false
    val digits = DIGIT.findAll(token).count()
    return digits >= 3 && token.length - digits >= 3
}

/** Whether [a] and [b] are within [maxEdits] insertions, deletions or substitutions of each other. */
internal fun withinEdits(
    a: String,
    b: String,
    maxEdits: Int,
): Boolean {
    if (abs(a.length - b.length) > maxEdits) return false
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        var rowMin = current[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            rowMin = min(rowMin, current[j])
        }
        if (rowMin > maxEdits) return false
        previous = current
    }
    return previous[b.length] <= maxEdits
}

/** 64-bit SimHash of a token set (FNV-1a per token): near-identical texts get near-identical hashes. */
internal fun simHash(tokens: Set<String>): Long {
    val weights = IntArray(64)
    for (token in tokens) {
        var hash = -0x340d631b7bdddcdbL
        for (char in token) {
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        for (bit in 0 until 64) {
            weights[bit] += if (hash ushr bit and 1L == 1L) 1 else -1
        }
    }
    var result = 0L
    for (bit in 0 until 64) if (weights[bit] > 0) result = result or (1L shl bit)
    return result
}
