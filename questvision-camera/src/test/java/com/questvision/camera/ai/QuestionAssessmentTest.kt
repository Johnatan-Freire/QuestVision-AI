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
import com.questvision.camera.data.plainText
import com.questvision.camera.recognition.LineBox
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.recognition.RecognizedText
import com.questvision.camera.recognition.RecognizedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionAssessmentTest {
    private fun screen(
        vararg lines: String,
        confidence: Float = 0.9f,
    ) = RecognizedText(lines.map { OcrScreens.words(it, confidence) })

    // 1. A new, complete question.
    @Test
    fun aCompleteLetteredQuestion_isUsable_inTextMode() {
        val assessment = assessQuestion(OcrScreens.screen(1))

        assertTrue(assessment.issues.toString(), assessment.usable)
        assertEquals(1, assessment.questionNumber)
        assertEquals(QuestionFormat.LETTERED, assessment.format)
        assertEquals(listOf("A", "B", "C", "D", "E"), assessment.alternatives.keys.toList())
        assertEquals("chmod", assessment.alternatives["B"])
        assertEquals(AnalysisMode.TEXT, assessment.mode)
    }

    // 3. Incomplete: the list runs into the bottom of the screen.
    @Test
    fun alternativesEndingTheScreen_mayBeCut_andWait() {
        val q = OcrScreens.questions.getValue(1)
        val assessment = assessQuestion(screen(q.title, q.statement, "A) mkdir", "B) chmod", "C) grep"))

        assertEquals(setOf(AssessmentIssue.POSSIBLY_CUT), assessment.issues)
        assertFalse(assessment.usable)
    }

    // 5. Alternatives missing: letters alone don't make a complete question.
    @Test
    fun missingOrSkippedAlternatives_areNotComplete() {
        val q = OcrScreens.questions.getValue(2)
        val skipped = assessQuestion(screen(q.title, q.statement, "A) DHCP", "C) DNS", "D) SSH", "Pergunte ao ChatGPT"))
        val notFromA = assessQuestion(screen(q.title, q.statement, "C) DNS", "D) SSH", "E) SMTP", "Pergunte ao ChatGPT"))
        val none = assessQuestion(screen(q.title, q.statement, "Pergunte ao ChatGPT"))
        val noStatement = assessQuestion(screen("Questão 02", "A) DHCP", "B) DNS", "Pergunte ao ChatGPT"))

        assertEquals(setOf(AssessmentIssue.ALTERNATIVES_OUT_OF_SEQUENCE), skipped.issues)
        assertEquals(setOf(AssessmentIssue.ALTERNATIVES_OUT_OF_SEQUENCE), notFromA.issues)
        assertEquals(setOf(AssessmentIssue.NO_ALTERNATIVES), none.issues)
        assertEquals(setOf(AssessmentIssue.NO_STATEMENT), noStatement.issues)
    }

    // 4. Blurred: the words come out with low confidence.
    @Test
    fun aBlurredCapture_isNotLegibleEnough() {
        val blurred = OcrScreens.screen(OcrScreens.questions.getValue(3), confidence = 0.3f)

        assertTrue(AssessmentIssue.LOW_CONFIDENCE in assessQuestion(blurred).issues)
    }

    @Test
    fun anUntitledQuestion_atTheTopOfTheScreen_mayBeCut_soItWaits() {
        // Nothing above the statement: it may continue above the photo.
        assertEquals(
            setOf(AssessmentIssue.NO_QUESTION_START, AssessmentIssue.NO_STATEMENT).intersect(
                assessQuestion(screen("Qual comando lista arquivos?", "A) ls", "B) cd", "fim")).issues,
            ),
            setOf(AssessmentIssue.NO_QUESTION_START),
        )
    }

    /** One line at [top] in the column starting at [left], one text line high. */
    private class Placed(
        val text: String,
        val left: Float,
        val top: Float,
        val width: Float = 0.55f,
    )

    private fun placed(vararg lines: Placed) =
        RecognizedText(
            lines.map { OcrScreens.words(it.text) },
            lines.map { LineBox(it.left, it.top, it.left + it.width, it.top + 0.03f) },
        )

    /** A ChatGPT-like screen: sidebar with conversation titles, a previous message, then the question. */
    private fun untitledScreen(
        ask: String = "Wi-Fi. Qual protocolo atribui endereços IP automaticamente?",
        sidebarWidth: Float = 0.15f,
        statementTop: Float = 0.30f,
    ) = placed(
        Placed("Planejar aplicativo Android", 0.02f, 0.10f, sidebarWidth),
        Placed("Lembrança De Notificação Extrajudicial", 0.02f, 0.14f, sidebarWidth),
        Placed("SISTEMA ESCOLAR", 0.02f, 0.40f, sidebarWidth),
        Placed("Quando o QuestVision AI capturar a questão, diga próxima.", 0.30f, 0.20f),
        Placed("Considere uma rede doméstica com vários dispositivos conectados ao roteador", 0.30f, statementTop),
        Placed(ask, 0.30f, statementTop + 0.035f),
        Placed("A) DNS", 0.30f, 0.40f, 0.10f),
        Placed("B) DHCP", 0.30f, 0.44f, 0.10f),
        Placed("C) FTP", 0.30f, 0.48f, 0.10f),
        Placed("D) SSH", 0.30f, 0.52f, 0.10f),
        Placed("Pergunte ao ChatGPT", 0.30f, 0.90f),
    )

    // Questions without "Questão N": found by their content and layout.
    @Test
    fun anUntitledQuestion_isFoundByItsContent_andGoesAsTheCroppedImage() {
        val assessment = assessQuestion(untitledScreen())

        assertTrue(assessment.issues.toString(), assessment.usable)
        assertNull(assessment.questionNumber)
        assertFalse(assessment.titled)
        assertEquals(listOf("A", "B", "C", "D"), assessment.alternatives.keys.toList())
        // The stored text can't mark where an untitled statement starts: the cropped photo goes.
        assertEquals(AnalysisMode.IMAGE, assessment.mode)
    }

    @Test
    fun anUntitledQuestion_needsALineThatAsks() {
        val statement = untitledScreen(ask = "Wi-Fi e protocolos de endereçamento automático na rede")

        assertTrue(AssessmentIssue.NO_QUESTION_START in assessQuestion(statement).issues)
    }

    @Test
    fun anUntitledQuestion_inStoredText_startsAfterThePreviousSentence() {
        val stored =
            "Planejar aplicativo Android\nQuando o QuestVision AI capturar a questão, diga próxima.\n" +
                "Considere uma rede doméstica com vários dispositivos conectados ao roteador\n" +
                "Wi-Fi. Qual protocolo atribui endereços IP automaticamente?\nA) DNS\nB) DHCP\nC) FTP\nD) SSH\nPergunte ao ChatGPT"

        assertEquals(
            listOf(
                "Considere uma rede doméstica com vários dispositivos conectados ao roteador",
                "Wi-Fi. Qual protocolo atribui endereços IP automaticamente?",
                "A) DNS",
                "B) DHCP",
                "C) FTP",
                "D) SSH",
            ),
            questionBlock(stored),
        )
        assertEquals("DHCP", alternativeText(stored, "B"))
    }

    @Test
    fun anUntitledJudgment_isRecognized() {
        val stored =
            "Responda certo ou errado.\n" +
                "O habeas corpus protege a liberdade de locomoção do cidadão brasileiro.\n( ) Certo\n( ) Errado\nPergunte"

        val assessment = assessQuestion(RecognizedText(stored.lines().map { OcrScreens.words(it) }))

        assertTrue(assessment.issues.toString(), assessment.usable)
        assertEquals(QuestionFormat.JUDGMENT, assessment.format)
    }

    @Test
    fun aQuestionTouchingTheTopEdge_mayBeCut() {
        assertTrue(AssessmentIssue.POSSIBLY_CUT in assessQuestion(untitledScreen(statementTop = 0.01f)).issues)
    }

    @Test
    fun fourAlternatives_areEnough_andNeverAssumedToBeFive() {
        val q = OcrScreens.questions.getValue(1)
        val four = assessQuestion(screen(q.title, q.statement, "A) mkdir", "B) chmod", "C) grep", "D) pwd", "Pergunte"))

        assertTrue(four.usable)
        assertEquals(4, four.alternatives.size)
    }

    // Privacy: the region of the question, without the sidebar or anything else.
    @Test
    fun theQuestionRegion_leavesTheSidebarAndOtherMessagesOut() {
        val region = questionRegion(untitledScreen())!!

        assertTrue("left ${region.left}", region.left > 0.17f)
        assertTrue("top ${region.top}", region.top > 0.23f && region.top < 0.30f)
        assertTrue("bottom ${region.bottom}", region.bottom >= 0.55f && region.bottom < 0.90f)
    }

    @Test
    fun noRegion_whenOtherTextWouldBeInside_orBoxesAreUnknown() {
        // A sidebar title reaching into the question's column at the same height.
        val crowded = untitledScreen(sidebarWidth = 0.33f)

        assertNull(questionRegion(crowded))
        assertNull(questionRegion(OcrScreens.screen(1)))
    }

    @Test
    fun visualContent_widensTheRegion_whenNothingElseIsThere() {
        val table = untitledScreen(ask = "Wi-Fi. Observe a tabela abaixo: qual protocolo atribui endereços?")

        val wide = questionRegion(table)!!

        assertTrue("left ${wide.left}", wide.left < 0.22f && wide.left > 0.17f)
    }

    // 2 / audit: what must never be taken for the same question.
    @Test
    fun negationsNumbersOperatorsAndAlternatives_makeQuestionsDifferent() {
        fun stored(
            statement: String,
            vararg alternatives: String,
        ) = "Questão 05 — Teste\n$statement\n" + alternatives.joinToString("\n") + "\nPergunte ao ChatGPT"
        val base =
            stored("Sobre o protocolo DNS, qual alternativa está correta?", "A) Usa a porta 53", "B) Usa a porta 80", "C) Usa a porta 22")

        assertFalse(
            sameQuestion(
                stored(
                    "Sobre o protocolo DNS, qual alternativa está incorreta?",
                    "A) Usa a porta 53",
                    "B) Usa a porta 80",
                    "C) Usa a porta 22",
                ),
                base,
            ),
        )
        assertFalse(
            sameQuestion(
                stored(
                    "Sobre o protocolo DNS, qual alternativa NÃO está correta?",
                    "A) Usa a porta 53",
                    "B) Usa a porta 80",
                    "C) Usa a porta 22",
                ),
                base,
            ),
        )
        assertFalse(
            sameQuestion(
                stored(
                    "Sobre o protocolo DNS, qual alternativa está correta?",
                    "A) Usa a porta 53",
                    "B) Usa a porta 80",
                    "C) Usa a porta 23",
                ),
                base,
            ),
        )
        assertFalse(
            sameQuestion(
                stored("Quanto vale 2 + 3 na expressão dada?", "A) 5", "B) 6", "C) 7"),
                stored("Quanto vale 2 × 3 na expressão dada?", "A) 5", "B) 6", "C) 7"),
            ),
        )
        assertFalse(
            sameQuestion(
                stored(
                    "Sobre o protocolo DNS, qual alternativa está correta?",
                    "A) Usa a porta 53",
                    "B) Usa a porta 80",
                    "C) Usa UDP apenas",
                ),
                base,
            ),
        )
        // The same question, renumbered elsewhere and read with OCR noise, is still the same.
        assertTrue(sameQuestion(base.replace("Questão 05", "Questão 12").replace("alternativa", "altemativa"), base))
    }

    // Formats other than A–E.
    @Test
    fun numberedAndJudgmentQuestions_areRecognized_andGoAsImages() {
        val numbered =
            assessQuestion(
                screen(
                    "Questão 07 — Lógica",
                    "Qual valor completa a sequência 2, 4, 8, ...?",
                    "1) 10",
                    "2) 12",
                    "3) 16",
                    "4) 32",
                    "Pergunte",
                ),
            )
        val judgment =
            assessQuestion(
                screen("Questão 08 — Direito", "O habeas corpus protege a liberdade de locomoção.", "( ) Certo", "( ) Errado", "Pergunte"),
            )

        assertTrue(numbered.usable)
        assertEquals(QuestionFormat.NUMBERED, numbered.format)
        assertEquals(AnalysisMode.IMAGE, numbered.mode)
        assertTrue(judgment.usable)
        assertEquals(QuestionFormat.JUDGMENT, judgment.format)
        assertEquals(AnalysisMode.IMAGE, judgment.mode)
    }

    // 6. A chart, table or formula: the OCR text can't carry it, so the photo goes.
    @Test
    fun visualContent_switchesToTheImage() {
        val chart =
            assessQuestion(
                screen(
                    "Questão 09 — Estatística",
                    "Observe o gráfico abaixo e indique o mês de maior venda.",
                    "A) Jan",
                    "B) Fev",
                    "C) Mar",
                    "D) Abr",
                    "Pergunte ao ChatGPT",
                ),
            )
        val formula =
            assessQuestion(
                screen(
                    "Questão 10 — Cálculo",
                    "Se f(x) = x² + 2x + 1 e g(x) = √x, quanto vale f(g(4))?",
                    "A) 9",
                    "B) 16",
                    "C) 25",
                    "D) 4",
                    "Pergunte ao ChatGPT",
                ),
            )

        assertTrue(chart.usable && chart.visualContent)
        assertEquals(AnalysisMode.IMAGE, chart.mode)
        assertTrue(formula.usable && formula.visualContent)
        assertEquals(AnalysisMode.IMAGE, formula.mode)
    }

    @Test
    fun theNewestQuestionOnScreen_isTheOneAssessed() {
        val previous = OcrScreens.questions.getValue(1)
        val current = OcrScreens.questions.getValue(2)
        val assessment =
            assessQuestion(
                screen(
                    previous.title,
                    previous.statement,
                    *previous.alternatives.toTypedArray(),
                    current.title,
                    current.statement,
                    "A) DHCP",
                ),
            )

        assertEquals(2, assessment.questionNumber)
        assertFalse(assessment.usable)
    }

    @Test
    fun theAnswerText_isReadBackFromTheStoredOcr() {
        val stored = plainText(OcrScreens.screen(2))

        assertEquals("DNS", alternativeText(stored, "C"))
        assertNull(alternativeText(stored, "Z"))
    }

    // 2. A repeated question across sessions: same block, different screen around it.
    @Test
    fun sameQuestion_ignoresTheRestOfTheScreen_andOcrNoise() {
        val first = plainText(OcrScreens.screen(OcrScreens.questions.getValue(2), clock = "10:00:00"))
        val noisy =
            plainText(
                OcrScreens.screen(OcrScreens.questions.getValue(2), clock = "18:42:11", extraLines = listOf("Nova conversa")),
            ).replace("DHCP", "DHCF")
        val other = plainText(OcrScreens.screen(3))

        assertTrue(sameQuestion(noisy, first))
        assertFalse(sameQuestion(other, first))
        assertFalse(sameQuestion(null, first))
    }

    @Test
    fun wordConfidence_isPerWord() {
        val mixed =
            RecognizedText(
                OcrScreens.screen(1).lines.mapIndexed { i, line ->
                    if (i == 7) line.map { RecognizedWord(it.text, 0.2f) } else line
                },
            )

        // One doubtful alternative ("A) mkdir") among many confident words is tolerated.
        assertTrue(assessQuestion(mixed).usable)
    }
}
