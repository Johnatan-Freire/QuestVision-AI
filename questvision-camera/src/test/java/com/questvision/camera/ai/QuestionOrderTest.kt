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

import com.questvision.camera.data.plainText
import com.questvision.camera.recognition.LineBox
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.recognition.RecognizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field session of 2026-11-04 (question 2 on a browser that wasn't full screen): ML Kit returned the
 * browser's tabs, bookmarks and side items in the middle of the question, and its statement after the
 * alternatives, so the question was judged illegible and never sent. The lines are as read, in the
 * order read; the boxes are where they were on the (turned) photo.
 */
class QuestionOrderTest {
    private fun read(vararg lines: Pair<String, LineBox>) =
        RecognizedText(lines.map { OcrScreens.words(it.first) }, lines.map { it.second })

    // Page column at x≈0.30; bookmarks bar above it; tabs/side items at the left edge of the browser.
    private fun field(statementRight: Float = 0.90f) =
        read(
            "Gerenciador de E" to LineBox(0.02f, 0.02f, 0.14f, 0.05f),
            "home/johnatan/QuestVision-teste/questoes-teste-real.html" to LineBox(0.20f, 0.06f, 0.70f, 0.09f),
            "Questão 2" to LineBox(0.30f, 0.25f, 0.45f, 0.30f),
            "Darry Ring Nat.. Api Whatsapp Cloud-hosting pr..." to LineBox(0.20f, 0.11f, 0.80f, 0.14f),
            "A) ORDER BY" to LineBox(0.30f, 0.50f, 0.48f, 0.55f),
            "B) WHERE" to LineBox(0.30f, 0.58f, 0.44f, 0.63f),
            "Sistema Escolar" to LineBox(0.02f, 0.60f, 0.15f, 0.63f),
            "C) HAVING" to LineBox(0.30f, 0.66f, 0.46f, 0.71f),
            "(2) Facebook" to LineBox(0.02f, 0.66f, 0.13f, 0.69f),
            "D) GROUP BY" to LineBox(0.30f, 0.74f, 0.49f, 0.79f),
            "Gerenciador de A" to LineBox(0.02f, 0.72f, 0.14f, 0.75f),
            "Area do Candidat.." to LineBox(0.02f, 0.78f, 0.15f, 0.81f),
            "Em SQL, qual cláusula permite agrupar registros para realizar operações" to LineBox(0.30f, 0.33f, statementRight, 0.38f),
            "como COUNT(*) por categoria?" to LineBox(0.30f, 0.40f, 0.62f, 0.45f),
            "PRIVATIZACAO: C" to LineBox(0.02f, 0.84f, 0.15f, 0.87f),
            "Platform Home P" to LineBox(0.02f, 0.88f, 0.15f, 0.91f),
        )

    @Test
    fun theQuestion_readsInOrder_withoutWhatIsBesideIt() {
        val ordered = plainText(questionOrder(field())).lines()
        val start = ordered.indexOf("Questão 2")

        assertEquals(
            listOf(
                "Questão 2",
                "Em SQL, qual cláusula permite agrupar registros para realizar operações",
                "como COUNT(*) por categoria?",
                "A) ORDER BY",
                "B) WHERE",
                "C) HAVING",
                "D) GROUP BY",
            ),
            ordered.subList(start, start + 7),
        )
    }

    @Test
    fun theFieldQuestion_isNowUsable_andItsTextIsClean() {
        val assessment = assessQuestion(field())

        assertTrue(assessment.issues.toString(), assessment.usable)
        assertEquals(2, assessment.questionNumber)
        assertEquals(listOf("A", "B", "C", "D"), assessment.alternatives.keys.toList())
        // What the text mode sends: from the title to the last alternative, nothing from the browser.
        val sent = extractQuestionText(plainText(questionOrder(field())))!!.text
        assertTrue(sent.contains("COUNT(*) por categoria?"))
        for (outside in listOf("Facebook", "Sistema Escolar", "Whatsapp", "Candidat", "PRIVATIZACAO")) {
            assertFalse(outside, sent.contains(outside))
        }
    }

    // The same photo really had the statement running off the right edge ("realizar ope…").
    @Test
    fun aStatementRunningOffTheSide_isNotSent_andTheTipSaysWhere() {
        val screen = field(statementRight = 0.995f)
        val assessment = assessQuestion(screen)

        assertTrue(AssessmentIssue.POSSIBLY_CUT in assessment.issues)
        assertEquals(FramingHint.RIGHT, framingHint(screen, assessment))
    }

    @Test
    fun theOrder_isStable() {
        val once = questionOrder(field())
        assertEquals(plainText(once), plainText(questionOrder(once)))
    }

    // The question already answered in another session is recognized, whatever surrounded it then.
    @Test
    fun theSameQuestionSurroundedByOtherThings_isTheSameQuestion() {
        val earlier = plainText(questionOrder(field()))
        val now =
            plainText(
                questionOrder(
                    read(
                        "Questão 2" to LineBox(0.30f, 0.20f, 0.45f, 0.25f),
                        "Supermetrics Capital Code" to LineBox(0.02f, 0.30f, 0.15f, 0.33f),
                        "Em SQL, qual cláusula permite agrupar registros para realizar operações" to LineBox(0.30f, 0.28f, 0.90f, 0.33f),
                        "como COUNT(*) por categoria?" to LineBox(0.30f, 0.35f, 0.62f, 0.40f),
                        "A) ORDER BY" to LineBox(0.30f, 0.45f, 0.48f, 0.50f),
                        "B) WHERE" to LineBox(0.30f, 0.53f, 0.44f, 0.58f),
                        "C) HAVING" to LineBox(0.30f, 0.61f, 0.46f, 0.66f),
                        "D) GROUP BY" to LineBox(0.30f, 0.69f, 0.49f, 0.74f),
                        "Questões do teste" to LineBox(0.02f, 0.90f, 0.15f, 0.93f),
                    ),
                ),
            )
        assertTrue(sameQuestion(now, earlier))
    }

    // Question 3 of the same session: large text, C and D just below the photo; A and B alone looked whole.
    @Test
    fun largeTextEndingNearTheBottom_mayHideMoreAlternatives() {
        val screen =
            read(
                "Questão 3" to LineBox(0.20f, 0.55f, 0.32f, 0.60f),
                "Considere o código:" to LineBox(0.20f, 0.62f, 0.45f, 0.67f),
                "int numero = 7;" to LineBox(0.22f, 0.69f, 0.40f, 0.73f),
                "System.out.println(numero / 2);" to LineBox(0.22f, 0.74f, 0.58f, 0.78f),
                "Qual será a saída?" to LineBox(0.20f, 0.80f, 0.42f, 0.85f),
                "A) 3" to LineBox(0.20f, 0.86f, 0.27f, 0.905f),
                "B) 3.5" to LineBox(0.20f, 0.915f, 0.29f, 0.96f),
                "Questões do teste" to LineBox(0.02f, 0.97f, 0.15f, 0.99f),
            )
        val assessment = assessQuestion(screen)

        assertTrue(AssessmentIssue.POSSIBLY_CUT in assessment.issues)
        assertEquals(FramingHint.DOWN, framingHint(screen, assessment))
    }

    // Field photo of 2026-11-04 15:07 (line boxes as ML Kit gave them): the whole question with blank
    // page below and nothing legible after it. It used to be judged cut because no text followed.
    @Test
    fun aWholeQuestionWithBlankPageBelow_isUsable() {
        val screen =
            read(
                "Platform Home P..." to LineBox(0.60f, 0.00f, 0.66f, 0.01f),
                "Free Online Linu..." to LineBox(0.69f, 0.00f, 0.75f, 0.01f),
                "Questão 1" to LineBox(0.12f, 0.20f, 0.26f, 0.23f),
                "Qual máscara de sub-rede corresponde ao prefixo IPv4 /24?" to LineBox(0.13f, 0.26f, 0.65f, 0.31f),
                "A) 255.255.0.0" to LineBox(0.12f, 0.31f, 0.25f, 0.34f),
                "B) 255.255.255.0" to LineBox(0.12f, 0.36f, 0.27f, 0.39f),
                "C) 255.255.255.128" to LineBox(0.12f, 0.41f, 0.29f, 0.44f),
                "D) 255.255.255.252" to LineBox(0.12f, 0.45f, 0.29f, 0.48f),
            )
        val assessment = assessQuestion(screen)

        assertTrue(assessment.issues.toString(), assessment.usable)
        assertEquals(null, framingHint(screen, assessment))
    }
}
