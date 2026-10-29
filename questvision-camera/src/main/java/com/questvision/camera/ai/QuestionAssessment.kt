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
import com.questvision.camera.data.QUESTION_NUMBER
import com.questvision.camera.recognition.LineBox
import com.questvision.camera.recognition.QuestionSignature
import com.questvision.camera.recognition.QuestionTextMatcher
import com.questvision.camera.recognition.RecognizedText
import com.questvision.camera.recognition.RecognizedWord
import com.questvision.camera.recognition.TextFingerprint
import com.questvision.camera.recognition.TextVerdict
import java.text.Normalizer

/**
 * "A) …", "(b) …", "C. …", "D - …", also with the radio button some pages draw before it, which the OCR
 * reads as a letter-like glyph glued to the label ("OC) …", "© A) …").
 */
private val LETTER_ALTERNATIVE = Regex("""^(?:[oO0©○◯●•◦]\s*)?\(?([A-Ea-e])\s*[).:\-–]\s*\S""")

/** "1) …", "(2) …", "3 - …" (not "1." : numbered statements and items use it). */
private val NUMBER_ALTERNATIVE = Regex("""^\(?([1-5])\s*[)\-–]\s*\S""")

/** A judgment item: "( ) Certo", "Errado", "( ) Verdadeiro ( ) Falso". */
private val JUDGMENT = Regex("""^(\(\s*\)\s*)?(certo|errado|verdadeiro|falso)\b""", RegexOption.IGNORE_CASE)

/** The label at the start of an alternative line (and a radio-button glyph before it), to strip it. */
private val LABEL_PREFIX = Regex("""^(?:[oO0©○◯●•◦]\s*)?\(?\w\s*[).:\-–]\s*""")

/** Words pointing at something shown ("observe o gráfico abaixo"); a visual word needs one of them. */
private val POINTING_WORDS =
    setOf(
        "abaixo",
        "acima",
        "seguinte",
        "seguir",
        "observe",
        "analise",
        "conforme",
        "apresentado",
        "apresentada",
        "mostrado",
        "mostrada",
        "ilustrado",
        "ilustrada",
    )

/** Words announcing content the OCR text cannot carry (normalized: lowercase, no accents). */
private val VISUAL_WORDS =
    setOf("figura", "grafico", "tabela", "imagem", "diagrama", "esquema", "mapa", "charge", "tirinha", "ilustracao", "circuito")

/** Characters of formulas; many of them mean the OCR line is probably not faithful. */
private val MATH_CHARS = setOf('=', '+', '×', '÷', '√', '^', '²', '³', '∑', '∫', 'π', '≤', '≥', '≠', '∞', '∆', 'Δ')

/** Thresholds of the local validation. Conservative on purpose: when in doubt, nothing is sent. */
data class AssessmentConfig(
    /** Words below this ML Kit confidence count as doubtful (ML Kit documents only the 0–1 range). */
    val minWordConfidence: Float = 0.5f,
    /** More doubtful words than this share of the question: blurred, glare or partly hidden. */
    val maxDoubtfulShare: Float = 0.15f,
    /** This many formula characters in the question: the image is sent instead of the OCR text. */
    val mathCharsForImage: Int = 6,
    /** Most lines between two alternatives (a wrapped alternative). */
    val maxAlternativeGap: Int = 4,
    /** Most statement lines collected above the alternatives without a title, in stored text (no layout). */
    val maxUntitledStatementLines: Int = 8,
    /** The same with line boxes, where paragraphs and columns are known: long statements fit. */
    val maxUntitledStatementLinesWithLayout: Int = 30,
    /** Fewest words of a statement. */
    val minStatementWords: Int = 6,
    /** Statement lines must start within this fraction of the width from the question line (no title). */
    val columnTolerance: Float = 0.06f,
    /** …or be indented by at most this much (table cells); a line starting further right is another element. */
    val maxIndent: Float = 0.15f,
    /**
     * Without a title, a gap above this many line heights ends the question (a separate message);
     * smaller gaps are paragraphs of the same statement.
     */
    val maxQuestionGap: Float = 2.0f,
    /** Within this fraction of the top or bottom edge, the question may continue off-screen. */
    val edgeMargin: Float = 0.03f,
)

/** How a question offers its answers. */
enum class QuestionFormat {
    /** A), B), C)… (two to five; never assumed to be five). */
    LETTERED,

    /** 1), 2), 3)… */
    NUMBERED,

    /** Certo / Errado, Verdadeiro / Falso. */
    JUDGMENT,
}

/** Why a capture is not sent automatically. Each one means: wait for a better capture. */
enum class AssessmentIssue {
    /**
     * Without a "Questão N" title, the statement's first line could not be told from the rest of the
     * screen: the line above the alternatives doesn't ask anything (ends in "?" or ":"), or the
     * statement doesn't end at a clear boundary.
     */
    NO_QUESTION_START,

    /** Nothing (or almost nothing) between the start and the first alternative. */
    NO_STATEMENT,

    /** Fewer than two alternatives, and no Certo/Errado judgment. */
    NO_ALTERNATIVES,

    /** Labels skip or repeat (A, C…), or don't start at A / 1: some are missing or misread. */
    ALTERNATIVES_OUT_OF_SEQUENCE,

    /** The question touches the edge of the photo or the alternatives end the screen: more may be off-screen. */
    POSSIBLY_CUT,

    /** Too many words with low ML Kit confidence (blur, glare, movement). A heuristic, not an official score. */
    LOW_CONFIDENCE,
}

/**
 * The local verdict on one photographed screen, before anything is sent: whether the newest question
 * on it is complete and legible ([usable]), and in which [mode] it should go.
 */
data class QuestionAssessment(
    val questionNumber: Int?,
    val format: QuestionFormat?,
    /** Alternatives as read, label → text ("B" → "chmod"); empty for judgments. */
    val alternatives: Map<String, String>,
    val issues: Set<AssessmentIssue>,
    /** Figures, tables or formulas are likely: the OCR text alone would not carry the question. */
    val visualContent: Boolean,
    /** Whether a "Questão N" title starts the question. */
    val titled: Boolean = questionNumber != null,
    /** What tells it apart from near-identical questions; null when no question was found. */
    val signature: QuestionSignature? = null,
) {
    val usable: Boolean get() = issues.isEmpty()

    /**
     * Text only when the stored OCR text carries the whole question: a title marks its start (see
     * [extractQuestionText]), the alternatives are lettered, and there is nothing visual. Otherwise the
     * photo, cut to the question (see [questionRegion]).
     */
    val mode: AnalysisMode
        get() = if (titled && format == QuestionFormat.LETTERED && !visualContent) AnalysisMode.TEXT else AnalysisMode.IMAGE

    override fun toString() =
        "QuestionAssessment(n=$questionNumber, $format, ${alternatives.size} alternatives, $issues, visual=$visualContent)"
}

/** Where the newest question is among a screen's non-empty lines. */
internal class QuestionLocation(
    /** First line: the title, or the statement's first line. */
    val start: Int,
    /** First and last answer lines (alternatives or judgment). */
    val answersStart: Int,
    val end: Int,
    val titled: Boolean,
    val number: Int?,
    val format: QuestionFormat,
    val labels: List<Pair<Int, String>>,
    val issues: Set<AssessmentIssue>,
)

private class Line(
    val text: String,
    val words: List<RecognizedWord>,
    val box: LineBox?,
)

private enum class Kind { TITLE, LETTER, NUMBER, JUDGMENT, OTHER }

private fun kindOf(text: String): Pair<Kind, String> {
    QUESTION_NUMBER.find(text)?.let { return Kind.TITLE to it.groupValues[1] }
    LETTER_ALTERNATIVE.find(text)?.let { return Kind.LETTER to it.groupValues[1].uppercase() }
    NUMBER_ALTERNATIVE.find(text)?.let { return Kind.NUMBER to it.groupValues[1] }
    if (JUDGMENT.containsMatchIn(text)) return Kind.JUDGMENT to ""
    return Kind.OTHER to ""
}

private fun linesOf(text: RecognizedText): List<Line> =
    text.lines.indices
        .map { i -> Line(text.lines[i].joinToString(" ") { it.text }.trim(), text.lines[i], text.boxOf(i)) }
        .filter { it.text.isNotEmpty() }

/**
 * The newest question on screen, found by its content: the last complete run of answer lines (A, B,
 * C… / 1, 2, 3… / Certo, Errado), then its start above them: a "Questão N" title when there is one;
 * otherwise the line asking the question ("…?" or "…:") and the statement lines right above it, in
 * the same column and paragraph (with line boxes), or up to the previous sentence or a short label
 * line (stored text, no boxes). Null when there are no answer lines at all.
 */
private fun locate(
    lines: List<Line>,
    config: AssessmentConfig,
): QuestionLocation? {
    val kinds = lines.map { kindOf(it.text) }
    val group =
        answerGroups(kinds, lines, config).maxWithOrNull(compareBy({ it.second.last().first }, { -it.first.ordinal })) ?: return null
    val (format, labels, outOfSequence) = Triple(group.first, group.second, group.third)
    val issues = HashSet<AssessmentIssue>()
    if (outOfSequence) issues += AssessmentIssue.ALTERNATIVES_OUT_OF_SEQUENCE
    val answersStart = labels.first().first
    val end = lastContinuation(lines, kinds, labels.last().first)
    val inGroup = labels.map { it.first }.toSet()
    // The previous question's answers bound this one from above.
    val previous = kinds.indices.lastOrNull { it < answersStart && it !in inGroup && kinds[it].first in ANSWER_KINDS } ?: -1
    val title = (previous + 1 until answersStart).lastOrNull { kinds[it].first == Kind.TITLE }

    val start: Int
    if (title != null) {
        start = title
        val titleLine = lines[title].text
        val titleHasStatement = QUESTION_NUMBER.find(titleLine)?.let { titleLine.length - it.range.last > 15 } == true
        val statementWords = (title + 1 until answersStart).sumOf { wordCount(lines[it].text) }
        if (statementWords == 0 && !titleHasStatement) issues += AssessmentIssue.NO_STATEMENT
    } else {
        start = untitledStart(lines, kinds, previous, answersStart, format, config) ?: answersStart
        if (start == answersStart) {
            issues += AssessmentIssue.NO_QUESTION_START
        } else if ((start until answersStart).sumOf { wordCount(lines[it].text) } < config.minStatementWords) {
            issues += AssessmentIssue.NO_STATEMENT
        }
    }

    // Something must follow the last answer (the next message, an input bar, the taskbar), and the
    // question must not touch the photo's edges; otherwise it may continue off-screen.
    val endBox = lines[end].box
    val startBox = lines[start].box
    if (end == lines.lastIndex || (endBox != null && endBox.bottom > 1f - config.edgeMargin)) issues += AssessmentIssue.POSSIBLY_CUT
    if (title == null && startBox != null && startBox.top < config.edgeMargin) issues += AssessmentIssue.POSSIBLY_CUT

    val number =
        title?.let {
            QUESTION_NUMBER
                .find(lines[it].text)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        }
    return QuestionLocation(start, answersStart, end, title != null, number, format, labels, issues)
}

private val ANSWER_KINDS = setOf(Kind.LETTER, Kind.NUMBER, Kind.JUDGMENT)

/**
 * The last line of the alternative starting at [labelLine]: a wrapped alternative continues on the
 * next lines (with boxes: right below, no wider gap than within a paragraph, aligned with its text;
 * stored text: starting in lowercase after a line that doesn't end a sentence), so the question
 * doesn't end mid-sentence.
 */
private fun lastContinuation(
    lines: List<Line>,
    kinds: List<Pair<Kind, String>>,
    labelLine: Int,
): Int {
    var last = labelLine
    val labelBox = lines[labelLine].box
    while (last + 1 < lines.size && kinds[last + 1].first == Kind.OTHER) {
        val next = lines[last + 1]
        val above = lines[last].box
        val continues =
            if (labelBox != null && above != null && next.box != null) {
                next.box.top >= above.top &&
                    next.box.top - above.bottom <= above.height * 0.8f &&
                    next.box.left >= labelBox.left - 0.01f &&
                    next.box.left <= labelBox.left + 0.06f
            } else {
                val previous = lines[last].text.trimEnd()
                next.text.first().isLowerCase() &&
                    !(previous.endsWith(".") || previous.endsWith("?") || previous.endsWith("!") || previous.endsWith(";"))
            }
        if (!continues) break
        last++
    }
    return last
}

/** Whether [middle] lies vertically between [above] and [below] (true when any box is unknown). */
private fun between(
    above: LineBox?,
    middle: LineBox?,
    below: LineBox?,
): Boolean = above == null || middle == null || below == null || (middle.top >= above.top && middle.bottom <= below.bottom)

/**
 * Candidate answer runs, one per format: from the format's last answer line back through labels in
 * order (B before C…), each within [AssessmentConfig.maxAlternativeGap] lines. Flags a run that
 * doesn't start at A / 1, or that stops while earlier labels of its format are near (a gap).
 */
private fun answerGroups(
    kinds: List<Pair<Kind, String>>,
    lines: List<Line>,
    config: AssessmentConfig,
): List<Triple<QuestionFormat, List<Pair<Int, String>>, Boolean>> {
    val groups = ArrayList<Triple<QuestionFormat, List<Pair<Int, String>>, Boolean>>()
    for ((kind, format, first) in listOf(
        Triple(Kind.LETTER, QuestionFormat.LETTERED, "A"),
        Triple(Kind.NUMBER, QuestionFormat.NUMBERED, "1"),
    )) {
        val labelled = kinds.indices.filter { kinds[it].first == kind }.map { it to kinds[it].second }
        if (labelled.size < 2) continue
        val run = ArrayDeque(listOf(labelled.last()))
        var stoppedEarly = false
        var inferred = false
        for (candidate in labelled.dropLast(1).asReversed()) {
            // Complete from A / 1: what is above belongs to the previous question.
            if (run.first().second == first) break
            val expected = run.first().second[0] - 1
            val near = run.first().first - candidate.first <= config.maxAlternativeGap
            // One label lost to the OCR inside the list (B, "OO Scrum…", D): the line between the
            // neighbors that starts a sentence is the missing alternative. Once per list.
            val missing =
                if (!inferred && !stoppedEarly && candidate.second[0] == expected - 1 &&
                    run.first().first - candidate.first <= config.maxAlternativeGap * 2
                ) {
                    (candidate.first + 1 until run.first().first).firstOrNull { i ->
                        kinds[i].first == Kind.OTHER && wordCount(lines[i].text) >= 3 && !lines[i].text.first().isLowerCase() &&
                            between(lines[candidate.first].box, lines[i].box, lines[run.first().first].box)
                    }
                } else {
                    null
                }
            if (missing != null) {
                inferred = true
                run.addFirst(missing to expected.toString())
                run.addFirst(candidate)
            } else if (candidate.second[0] == expected && near && !stoppedEarly) {
                run.addFirst(candidate)
            } else if (near) {
                // A label out of order right above (A, C, D): part of this broken list, not another question.
                stoppedEarly = true
                run.addFirst(candidate)
            } else {
                break
            }
        }
        val outOfSequence = run.first().second != first || stoppedEarly || run.size < 2
        groups += Triple(format, run.toList(), outOfSequence)
    }
    val judgments = kinds.indices.filter { kinds[it].first == Kind.JUDGMENT }
    if (judgments.isNotEmpty()) {
        val run = ArrayDeque(listOf(judgments.last()))
        for (candidate in judgments.dropLast(1).asReversed()) {
            if (run.first() - candidate == 1) run.addFirst(candidate) else break
        }
        groups += Triple(QuestionFormat.JUDGMENT, run.map { it to "" }, false)
    }
    return groups
}

/** Exam commands that ask for the answer ("Assinale a alternativa correta."), accent-free. */
private val INSTRUCTION =
    Regex("""\b(assinale|marque|indique|escolha|selecione|julgue|identifique|aponte)\b|\bafirmar\s+que\b""")

/** Whether [line], right above the answers, asks for them: "…?", "…:", "…" or an exam command. */
private fun asks(line: String): Boolean {
    val text = line.trimEnd()
    if (text.endsWith("?") || text.endsWith(":") || text.endsWith("…") || text.endsWith("...")) return true
    return INSTRUCTION.containsMatchIn(stripAccents(text.lowercase()))
}

/**
 * The first statement line of an untitled question ending at [answersStart], or null when it can't be
 * told safely. The line right above the answers must ask for them ([asks]; a judgment's assertion may
 * be any sentence). Lines above it join while they belong to the question:
 * - with line boxes: in its column (the same left edge or indented, like table cells; the sidebar,
 *   browser bars and other columns start further left) and separated by less than
 *   [AssessmentConfig.maxQuestionGap] line heights, so several paragraphs join but a separate message
 *   above does not;
 * - stored text (no boxes): up to the previous finished sentence or a short label line.
 * Null as well when the statement runs into the top of the screen or past the line limit.
 */
private fun untitledStart(
    lines: List<Line>,
    kinds: List<Pair<Kind, String>>,
    previous: Int,
    answersStart: Int,
    format: QuestionFormat,
    config: AssessmentConfig,
): Int? {
    val ask = answersStart - 1
    if (ask <= previous || kinds[ask].first != Kind.OTHER) return null
    if (format != QuestionFormat.JUDGMENT && !asks(lines[ask].text)) return null
    val askBox = lines[ask].box
    val lineHeight = askBox?.height ?: 0f
    val geometric = askBox != null && lineHeight > 0f
    val maxLines = if (geometric) config.maxUntitledStatementLinesWithLayout else config.maxUntitledStatementLines
    var start = ask
    for (i in ask - 1 downTo previous + 1) {
        if (kinds[i].first != Kind.OTHER) break
        val box = lines[i].box
        val joins =
            if (geometric && box != null) {
                val upper = lines[start].box ?: break
                box.left >= askBox!!.left - config.columnTolerance &&
                    // Indented (table cells) but not far right (a right-aligned chat bubble).
                    box.left <= askBox.left + config.maxIndent &&
                    box.bottom <= upper.top + lineHeight / 2 &&
                    upper.top - box.bottom <= lineHeight * config.maxQuestionGap
            } else if (!geometric) {
                // Stored text has no boxes: a finished sentence or a short label line is not this question.
                val text = lines[i].text.trimEnd()
                !(text.endsWith(".") || text.endsWith("!") || text.endsWith("?")) && wordCount(text) >= 4
            } else {
                false
            }
        if (!joins) return start
        start = i
        if (ask - start + 1 > maxLines) return null
    }
    // Reached the top of the screen or the previous answers without a clear boundary.
    return if (previous >= 0) start else null
}

private fun wordCount(text: String) = text.split(Regex("""\s+""")).count { word -> word.any { it.isLetterOrDigit() } }

/**
 * Checks the newest question on a screen: start (title or statement), alternatives in sequence,
 * nothing cut at the edges, legible words, and signs of visual content. It never guesses: anything
 * uncertain is an [AssessmentIssue], and the capture waits for a better one.
 */
fun assessQuestion(
    text: RecognizedText,
    config: AssessmentConfig = AssessmentConfig(),
): QuestionAssessment {
    val lines = linesOf(text)
    val location =
        locate(lines, config)
            ?: return QuestionAssessment(null, null, emptyMap(), setOf(AssessmentIssue.NO_ALTERNATIVES), false)
    val issues = HashSet(location.issues)

    val words = (location.start..location.end).flatMap { lines[it].words }
    val doubtful = words.count { it.confidence < config.minWordConfidence }
    if (words.isEmpty() || doubtful > words.size * config.maxDoubtfulShare) issues += AssessmentIssue.LOW_CONFIDENCE

    val questionText = (location.start..location.end).map { lines[it].text }
    val normalizedWords =
        questionText
            .flatMap { it.split(Regex("""[^\p{L}\p{N}]+""")) }
            .map { stripAccents(it.lowercase()) }
            .toSet()
    val mathChars = questionText.sumOf { line -> line.count { it in MATH_CHARS } }
    // "Diagrama de Casos de Uso" names a topic; "observe o diagrama abaixo" shows one.
    val pointsAtVisual =
        questionText.any { line ->
            val words = line.split(Regex("""[^\p{L}\p{N}]+""")).map { stripAccents(it.lowercase()) }
            words.any { it in VISUAL_WORDS } && words.any { it in POINTING_WORDS }
        }
    val visual = pointsAtVisual || mathChars >= config.mathCharsForImage

    val alternatives = alternativesOf(lines, location)
    return QuestionAssessment(
        questionNumber = location.number,
        format = location.format,
        alternatives = alternatives,
        issues = issues,
        visualContent = visual,
        titled = location.titled,
        signature = signatureOf(lines, location, alternatives),
    )
}

private fun alternativesOf(
    lines: List<Line>,
    location: QuestionLocation,
): Map<String, String> =
    if (location.format == QuestionFormat.JUDGMENT) {
        emptyMap()
    } else {
        location.labels.associate { (i, label) -> label to lines[i].text.replaceFirst(LABEL_PREFIX, "").trim() }
    }

private fun signatureOf(
    lines: List<Line>,
    location: QuestionLocation,
    alternatives: Map<String, String>,
): QuestionSignature {
    // The title is left out: the same question may carry another number elsewhere.
    val statementStart = if (location.titled) location.start + 1 else location.start
    val statement =
        (statementStart until location.answersStart).map { lines[it].text } +
            if (location.format == QuestionFormat.JUDGMENT) (location.answersStart..location.end).map { lines[it].text } else emptyList()
    return QuestionSignature.of(statement, alternatives)
}

private fun storedLines(ocrText: String?): List<Line>? =
    ocrText
        ?.lines()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.map { line -> Line(line, line.split(" ").filter { it.isNotBlank() }.map { RecognizedWord(it, 1f) }, null) }

/** The text of alternative [label] ("C" → "ps aux") in stored OCR text, for showing the answer. */
fun alternativeText(
    ocrText: String?,
    label: String,
): String? {
    val lines = storedLines(ocrText) ?: return null
    val location = locate(lines, AssessmentConfig()) ?: return null
    return alternativesOf(lines, location)[label.uppercase()]
}

/** The newest question in stored OCR text, from its start to its last answer line; null when none is found. */
fun questionBlock(ocrText: String?): List<String>? {
    val lines = storedLines(ocrText) ?: return null
    val location = locate(lines, AssessmentConfig()) ?: return null
    if (AssessmentIssue.NO_QUESTION_START in location.issues) return null
    return (location.start..location.end).map { lines[it].text }
}

/**
 * Whether two stored OCR texts hold the same question: the session's text matcher on the question
 * blocks only (the rest of the screen changes between captures), and nothing that changes the answer
 * may differ (negations, numbers, operators, alternatives; see [QuestionSignature]). When in doubt they
 * are different, so a question is never skipped as "already analyzed" by mistake.
 */
fun sameQuestion(
    ocrText: String?,
    otherOcrText: String?,
    matcher: QuestionTextMatcher = QuestionTextMatcher(),
): Boolean {
    fun fingerprint(ocr: String?): TextFingerprint? {
        val lines = storedLines(ocr) ?: return null
        val location = locate(lines, AssessmentConfig()) ?: return null
        if (AssessmentIssue.NO_QUESTION_START in location.issues) return null
        val block = (location.start..location.end).map { lines[it] }
        return TextFingerprint.of(
            RecognizedText(block.map { it.words }),
            signature = signatureOf(lines, location, alternativesOf(lines, location)),
        )
    }
    val candidate = fingerprint(ocrText) ?: return false
    val reference = fingerprint(otherOcrText) ?: return false
    return matcher.compare(candidate, listOf(reference)).verdict == TextVerdict.DUPLICATE
}

/** A rectangle as fractions of the image (upright). */
data class CropRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * The part of the photo holding the newest question, for the image mode: the union of its lines (start
 * to last answer, so figures and tables between them are inside), with a margin. Null, so nothing is
 * cut and nothing is sent automatically, when it can't be found safely: no complete question, a line
 * without a box, a region too small, or any other text (sidebar, conversation titles, browser) inside
 * it. A wider margin is tried first when visual content is likely, then a tight one.
 */
fun questionRegion(
    text: RecognizedText,
    config: AssessmentConfig = AssessmentConfig(),
): CropRegion? {
    val lines = linesOf(text)
    val location = locate(lines, config) ?: return null
    if (AssessmentIssue.NO_QUESTION_START in location.issues || AssessmentIssue.NO_STATEMENT in location.issues) return null
    if ((location.start..location.end).any { lines[it].box == null }) return null
    // Reading order is not position: ML Kit may list a line from elsewhere on screen (a chat bubble
    // above) between two alternatives. A line belongs to the question only if it also lies between
    // the question's first and last lines; any other is outside and must stay out of the crop.
    val inQuestion = (location.start..location.end).filter { i -> withinQuestion(lines, location, i) }
    val boxes = inQuestion.map { lines[it].box!! }
    val union = CropRegion(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    val outside = lines.indices.filter { it !in inQuestion }.mapNotNull { i -> lines[i].box?.let { it to wordCount(lines[i].text) } }
    val visual = assessQuestion(text, config).visualContent
    // The vertical margin stops short of the neighboring lines above and below (the previous
    // message, a button row) instead of cutting into them.
    val columnNeighbors = outside.map { it.first }.filter { it.right > union.left && it.left < union.right }
    val topLimit = columnNeighbors.filter { it.bottom <= union.top + 0.002f }.maxOfOrNull { it.bottom + 0.002f } ?: 0f
    val bottomLimit = columnNeighbors.filter { it.top >= union.bottom - 0.002f }.minOfOrNull { it.top - 0.002f } ?: 1f
    val margins = if (visual) listOf(0.10f, 0.02f) else listOf(0.02f)
    for (horizontal in margins) {
        val region =
            CropRegion(
                left = (union.left - horizontal).coerceAtLeast(0f),
                top = (union.top - 0.015f).coerceAtLeast(maxOf(0f, minOf(topLimit, union.top))),
                right = (union.right + horizontal).coerceAtMost(1f),
                bottom = (union.bottom + 0.015f).coerceAtMost(minOf(1f, maxOf(bottomLimit, union.bottom))),
            )
        if (region.width < 0.15f || region.height < 0.05f) return null
        // Even a sliver of another line (a conversation title) is a leak: none may reach inside. Only
        // the page's own tiny controls inside the question (radio buttons, a one-word button) are
        // tolerated: one word, narrow, wholly inside the question's lines and away from the left edge.
        val leaks =
            outside.filter { (box, words) ->
                val share = overlapShare(box, region)
                val tinyControl =
                    words <= 1 && box.right - box.left < 0.08f && box.left > union.left + 0.005f &&
                        box.top >= union.top && box.bottom <= union.bottom && box.right <= union.right
                share > 0.05f && !tinyControl
            }
        if (leaks.isEmpty()) return region
    }
    return null
}

/** The question's lines (kind, box, word count), positions only, for on-device diagnostics. */
internal fun questionLinesDiagnostics(text: RecognizedText): String {
    val lines = linesOf(text)
    val location = locate(lines, AssessmentConfig()) ?: return "no-question"
    return (maxOf(0, location.start - 2)..location.end).joinToString(" ") { i ->
        val box = lines[i].box
        val mark = if (i < location.start) "x" else kindOf(lines[i].text).first.name.take(1)
        "%s[%.2f,%.2f-%.2f,%.2f/w%d]".format(
            java.util.Locale.US,
            mark,
            box?.left ?: -1f,
            box?.top ?: -1f,
            box?.right ?: -1f,
            box?.bottom ?: -1f,
            wordCount(lines[i].text),
        )
    }
}

/**
 * Why [questionRegion] found no region, for on-device diagnostics: positions and shares only, never
 * text. Lines are "before"/"after" the question in reading order.
 */
internal fun questionRegionDiagnostics(text: RecognizedText): String {
    val config = AssessmentConfig()
    val lines = linesOf(text)
    val location = locate(lines, config) ?: return "no-question"
    if (AssessmentIssue.NO_QUESTION_START in location.issues || AssessmentIssue.NO_STATEMENT in location.issues) return "no-start"
    val boxes = (location.start..location.end).map { lines[it].box ?: return "no-box" }
    val union = CropRegion(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    val region =
        CropRegion(
            (union.left - 0.02f).coerceAtLeast(0f),
            (union.top - 0.015f).coerceAtLeast(0f),
            (union.right + 0.02f).coerceAtMost(1f),
            (
                union.bottom +
                    0.015f
            ).coerceAtMost(1f),
        )
    val intruders =
        lines.indices
            .filter { it < location.start || it > location.end }
            .mapNotNull { i -> lines[i].box?.let { box -> Triple(i, box, overlapShare(box, region)) } }
            .filter { it.third > 0.05f }
    return "union=%.2f,%.2f-%.2f,%.2f intruders=%s".format(
        java.util.Locale.US,
        union.left,
        union.top,
        union.right,
        union.bottom,
        intruders.joinToString(";") { (i, box, share) ->
            "%s:%.2f,%.2f-%.2f,%.2f/%.2f/w%d".format(
                java.util.Locale.US,
                if (i < location.start) "before" else "after",
                box.left,
                box.top,
                box.right,
                box.bottom,
                share,
                wordCount(lines[i].text),
            )
        },
    )
}

/** Whether line [i] lies vertically between the question's first and last lines (true without boxes). */
private fun withinQuestion(
    lines: List<Line>,
    location: QuestionLocation,
    i: Int,
): Boolean {
    val box = lines[i].box ?: return true
    val first = lines[location.start].box ?: return true
    val last = lines[location.end].box ?: return true
    val slack = first.height / 2
    return box.top >= first.top - slack && box.bottom <= last.bottom + slack
}

/** The share of [box]'s area inside [region]. */
private fun overlapShare(
    box: LineBox,
    region: CropRegion,
): Float {
    val width = (minOf(box.right, region.right) - maxOf(box.left, region.left)).coerceAtLeast(0f)
    val height = (minOf(box.bottom, region.bottom) - maxOf(box.top, region.top)).coerceAtLeast(0f)
    val area = (box.right - box.left) * (box.bottom - box.top)
    return if (area <= 0f) 0f else width * height / area
}

private fun stripAccents(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
