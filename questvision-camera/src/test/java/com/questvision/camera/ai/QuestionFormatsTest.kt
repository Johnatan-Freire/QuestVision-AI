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
import com.questvision.camera.recognition.LineBox
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.recognition.RecognizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation round of phase 3.4: the statement phrasings and layouts seen in exams, and the crop in
 * realistic screen layouts. Line height is 0.03 of the photo; "rows" are 0.04 apart (a 0.01 gap), so a
 * blank row between paragraphs is a gap of about 1.7 line heights and a separate message 3 or more.
 */
class QuestionFormatsTest {
    private val previous = "Quando o QuestVision AI capturar a questão, diga próxima."
    private val context = "O texto discute o uso de redes de computadores em escolas públicas brasileiras"
    private val alternatives = listOf("A) um", "B) dois", "C) três", "D) quatro")

    private fun stored(vararg lines: String) = RecognizedText(lines.map { OcrScreens.words(it) })

    /** Lines of the main column at x = 0.30 from [top], one per row; null rows are blank (a paragraph gap). */
    private fun column(
        lines: List<String?>,
        top: Float = 0.10f,
        left: Float = 0.30f,
        extra: List<Pair<String, LineBox>> = emptyList(),
    ): RecognizedText {
        val placed =
            lines.mapIndexedNotNull { row, text ->
                text?.let { it to LineBox(left, top + row * 0.04f, left + 0.5f, top + row * 0.04f + 0.03f) }
            }
        val all = extra.filter { it.second.top < top } + placed + extra.filter { it.second.top >= top }
        return RecognizedText(all.map { OcrScreens.words(it.first) }, all.map { it.second })
    }

    private val sidebar =
        listOf(
            "Lembrança De Notificação Extrajudicial" to LineBox(0.01f, 0.30f, 0.20f, 0.33f),
            "SISTEMA ESCOLAR" to LineBox(0.01f, 0.50f, 0.12f, 0.53f),
        )

    // 3. Phrasings: questions, commands and declarations, with and without a title.
    @Test
    fun examPhrasings_areRecognized_withAndWithoutATitle() {
        val asks =
            listOf(
                "Qual das alternativas está correta?",
                "Assinale a alternativa correta.",
                "Analise as afirmativas e marque a opção adequada.",
                "Considerando o texto apresentado, é correto afirmar que",
                "Considerando o texto apresentado, é correto afirmar que...",
                "Indique a opção que completa a frase:",
            )
        for (ask in asks) {
            val titled = assessQuestion(stored(previous, "Questão 03 — Geral", context, ask, *alternatives.toTypedArray(), "Pergunte"))
            val untitled = assessQuestion(stored(previous, context, ask, *alternatives.toTypedArray(), "Pergunte"))

            assertTrue("titled: $ask ${titled.issues}", titled.usable)
            assertTrue("untitled: $ask ${untitled.issues}", untitled.usable)
        }
    }

    @Test
    fun aStatementThatDoesNotAsk_isNotAQuestionStart() {
        // An ordinary sentence right above a lettered list (e.g. a numbered summary in a chat).
        val listOfTopics = stored(previous, context, "Os tópicos foram discutidos em sala.", *alternatives.toTypedArray(), "Pergunte")

        assertTrue(AssessmentIssue.NO_QUESTION_START in assessQuestion(listOfTopics).issues)
    }

    @Test
    fun judgmentAndTrueFalse_areRecognized() {
        val judge =
            assessQuestion(
                stored(
                    previous,
                    "Julgue o item a seguir.",
                    "A internet é uma rede de redes interligadas mundialmente.",
                    "( ) Certo",
                    "( ) Errado",
                    "Pergunte",
                ),
            )
        val trueFalse =
            assessQuestion(
                stored(previous, "Questão 09", "O protocolo HTTP é stateless por definição.", "( ) Verdadeiro", "( ) Falso", "Pergunte"),
            )

        assertTrue(judge.issues.toString(), judge.usable)
        assertEquals(QuestionFormat.JUDGMENT, judge.format)
        assertTrue(trueFalse.issues.toString(), trueFalse.usable)
        assertEquals(QuestionFormat.JUDGMENT, trueFalse.format)
    }

    @Test
    fun fourOrFiveLetters_andNumbers_areRecognized() {
        val ask = "Qual das alternativas está correta?"
        val aToD = assessQuestion(stored(previous, context, ask, *alternatives.toTypedArray(), "Pergunte"))
        val aToE = assessQuestion(stored(previous, context, ask, *alternatives.toTypedArray(), "E) cinco", "Pergunte"))
        val numbers = assessQuestion(stored(previous, context, ask, "1) um", "2) dois", "3) três", "Pergunte"))

        assertEquals(4, aToD.alternatives.size)
        assertEquals(5, aToE.alternatives.size)
        assertEquals(QuestionFormat.NUMBERED, numbers.format)
        assertTrue(aToD.usable && aToE.usable && numbers.usable)
    }

    // Long and multi-paragraph statements.
    @Test
    fun aLongTitledStatement_hasNoLineLimit() {
        val longStatement = List(12) { "Linha $it do enunciado longo sobre arquitetura de computadores modernos" }
        val assessment =
            assessQuestion(
                stored(
                    previous,
                    "Questão 11",
                    *longStatement.toTypedArray(),
                    "Qual alternativa está correta?",
                    *alternatives.toTypedArray(),
                    "Pergunte",
                ),
            )

        assertTrue(assessment.issues.toString(), assessment.usable)
    }

    @Test
    fun aLongUntitledStatement_needsTheLayout() {
        val longStatement = List(12) { "Linha $it do enunciado longo sobre arquitetura de computadores modernos" }
        val withLayout =
            column(
                listOf(previous, null, null) + longStatement + "Qual alternativa está correta?" + alternatives + listOf(null, "Pergunte"),
            )
        val storedOnly =
            stored(previous, *longStatement.toTypedArray(), "Qual alternativa está correta?", *alternatives.toTypedArray(), "Pergunte")

        assertTrue(assessQuestion(withLayout).issues.toString(), assessQuestion(withLayout).usable)
        // Without positions, more than 8 lines can't be told from earlier text: it waits (documented limit).
        assertTrue(AssessmentIssue.NO_QUESTION_START in assessQuestion(storedOnly).issues)
    }

    @Test
    fun paragraphsJoin_butASeparateMessageDoesNot() {
        val screen =
            column(
                listOf(
                    previous,
                    null,
                    null,
                    "Leia o texto a seguir sobre redes.",
                    "As redes locais conectam computadores próximos.",
                    null,
                    "Com base no texto, é correto afirmar que",
                ) + alternatives + listOf(null, "Pergunte"),
            )

        val region = questionRegion(screen)!!

        assertTrue(assessQuestion(screen).usable)
        // The two paragraphs are inside (row 3 starts at 0.22); the earlier message (row 0, 0.10) is not.
        assertTrue("top ${region.top}", region.top < 0.22f && region.top > 0.13f)
    }

    // 4. Crops in realistic layouts: nothing but the question may be inside.
    @Test
    fun crop_centeredQuestion_withoutAnythingElse() {
        val screen =
            column(listOf(null, "Questão 01", context, "Qual das alternativas está correta?") + alternatives + listOf(null, "Pergunte"))

        assertNotNull(questionRegion(screen))
    }

    @Test
    fun crop_leavesTheSidebarAndTheBrowserBarOut() {
        val browser =
            listOf(
                "chatgpt.com/c/68f3a1 Gmail Banco Itaú" to LineBox(0.0f, 0.0f, 0.9f, 0.03f),
                "Nova guia" to LineBox(0.0f, 0.04f, 0.2f, 0.07f),
            )
        val screen =
            column(
                listOf("Questão 02", context, "Qual das alternativas está correta?") + alternatives + listOf(null, "Pergunte"),
                top = 0.20f,
                extra =
                    browser + sidebar,
            )

        val region = questionRegion(screen)!!

        assertTrue("left ${region.left}", region.left > 0.20f)
        assertTrue("top ${region.top}", region.top > 0.07f)
    }

    @Test
    fun crop_keepsATableBetweenTheStatementAndTheAlternatives() {
        val table =
            listOf("Observe a tabela de vendas:", "Mês Vendas", "Janeiro 120", "Fevereiro 95", "Qual mês teve a maior venda?") +
                alternatives +
                listOf(null, "Pergunte")
        val screen = column(listOf("Questão 04") + table, top = 0.20f, extra = sidebar)

        val assessment = assessQuestion(screen)
        val region = questionRegion(screen)!!

        assertTrue(assessment.visualContent)
        assertEquals(AnalysisMode.IMAGE, assessment.mode)
        assertTrue(region.top < 0.20f && region.bottom > 0.20f + 9 * 0.04f)
    }

    @Test
    fun crop_chartOrFormula_widensWhenNothingIsBeside() {
        val chart =
            column(
                listOf("Questão 05", "Observe o gráfico abaixo.", null, null, null, "Qual o valor máximo?") + alternatives +
                    listOf(null, "Pergunte"),
            )
        val formula =
            column(
                listOf("Questão 06", "Se f(x) = x² + 2x + 1 e g(x) = √x, quanto vale f(g(4))?") + alternatives + listOf(null, "Pergunte"),
            )

        val chartRegion = questionRegion(chart)!!
        val formulaRegion = questionRegion(formula)!!

        assertTrue("left ${chartRegion.left}", chartRegion.left < 0.25f)
        assertTrue(assessQuestion(formula).visualContent)
        assertTrue("left ${formulaRegion.left}", formulaRegion.left < 0.25f)
    }

    @Test
    fun crop_aQuestionPartlyOffScreen_isNeverSentAutomatically() {
        // The alternatives run into the bottom edge.
        val screen = column(listOf("Questão 07", context, "Qual das alternativas está correta?") + alternatives, top = 0.80f)

        assertTrue(AssessmentIssue.POSSIBLY_CUT in assessQuestion(screen).issues)
        assertFalse(assessQuestion(screen).usable)
    }

    @Test
    fun crop_isRefused_whenASidebarTitleReachesTheQuestion() {
        val wideSidebar = listOf("Lembrança De Notificação Extrajudicial e outros" to LineBox(0.01f, 0.26f, 0.31f, 0.29f))
        val screen =
            column(
                listOf("Questão 08", context, "Qual das alternativas está correta?") + alternatives + listOf(null, "Pergunte"),
                top = 0.20f,
                extra = wideSidebar,
            )

        assertNull(questionRegion(screen))
    }

    // Field session 2026-10-29: ML Kit read the title as "Questāo3-".
    @Test
    fun ocrVariantsOfTheTitle_areRecognized() {
        for (title in listOf("Questāo3- Engenharia de Software", "Questao 3 - Engenharia", "QUESTÃO Nº 3", "Questáo 3")) {
            val assessment =
                assessQuestion(
                    stored(previous, title, context, "Assinale a alternativa correta.", *alternatives.toTypedArray(), "Pergunte"),
                )

            assertEquals(title, 3, assessment.questionNumber)
            assertTrue(title, assessment.usable)
        }
    }

    // Field photo #1: the radio button swallowed "C)" ("OO Scrum…"); the list is otherwise intact.
    @Test
    fun oneLabelLostInsideTheList_isInferred_butNeverTwo() {
        val one =
            assessQuestion(
                stored(
                    previous,
                    "Qual das alternativas representa corretamente o Scrum?",
                    "A) O Scrum exige etapas sequenciais.",
                    "B) O Scrum utiliza ciclos iterativos.",
                    "OO Scrum não permite mudanças nos requisitos.",
                    "D) O Scrum dispensa a comunicação.",
                    "E)0 Scrum exige entrega apenas no final.",
                    "Responder",
                ),
            )
        val two =
            assessQuestion(
                stored(
                    previous,
                    "Qual das alternativas está correta?",
                    "A) um",
                    "OO dois itens aqui",
                    "OO três itens aqui",
                    "D) quatro",
                    "Responder",
                ),
            )

        assertTrue(one.issues.toString(), one.usable)
        assertEquals(listOf("A", "B", "C", "D", "E"), one.alternatives.keys.toList())
        assertTrue(AssessmentIssue.ALTERNATIVES_OUT_OF_SEQUENCE in two.issues)
    }

    @Test
    fun aRadioButtonGluedToTheLabel_isTolerated() {
        val assessment =
            assessQuestion(
                stored(
                    previous,
                    "Questão 02",
                    context,
                    "Qual das alternativas está correta?",
                    "OA) um",
                    "© B) dois",
                    "OC) três",
                    "Pergunte",
                ),
            )

        assertTrue(assessment.issues.toString(), assessment.usable)
        assertEquals("um", assessment.alternatives["A"])
    }

    // Field photo #10: "Diagrama de Casos de Uso" is a topic, not a figure to look at.
    @Test
    fun aVisualWordAsTheTopic_isNotVisualContent() {
        val topic =
            assessQuestion(
                stored(
                    previous,
                    "Questão 05",
                    "Em relação ao Diagrama de Casos de Uso, assinale a alternativa CORRETA.",
                    *alternatives.toTypedArray(),
                    "Pergunte",
                ),
            )
        val shown =
            assessQuestion(
                stored(
                    previous,
                    "Questão 05",
                    "Observe o diagrama abaixo e assinale a alternativa CORRETA.",
                    *alternatives.toTypedArray(),
                    "Pergunte",
                ),
            )

        assertFalse(topic.visualContent)
        assertEquals(AnalysisMode.TEXT, topic.mode)
        assertTrue(shown.visualContent)
    }

    // Field photos #5–#12: the margin cut into the line just above, and radio buttons sat inside.
    @Test
    fun theMarginStopsAtNeighboringLines_andTinyControlsInsideAreTolerated() {
        val closeAbove = listOf("Mensagem anterior que termina logo acima da questão" to LineBox(0.30f, 0.165f, 0.80f, 0.195f))
        val radios = listOf("O" to LineBox(0.31f, 0.33f, 0.325f, 0.345f), "outra" to LineBox(0.70f, 0.25f, 0.76f, 0.27f))
        val screen =
            column(
                listOf("Questão 04", context, "Qual das alternativas está correta?") + alternatives + listOf(null, "Pergunte"),
                top = 0.20f,
                extra =
                    closeAbove + radios,
            )

        val region = questionRegion(screen)!!

        assertTrue("top ${region.top}", region.top >= 0.195f)
        // A short sidebar title at the left edge is never a "tiny control".
        val sidebarWord = listOf("Meduza" to LineBox(0.20f, 0.30f, 0.29f, 0.33f))
        assertNull(
            questionRegion(
                column(
                    listOf("Questão 04", context, "Qual das alternativas está correta?") + alternatives + listOf(null, "Pergunte"),
                    top = 0.20f,
                    extra = sidebarWord,
                ),
            ),
        )
    }

    // Field photo #1: ML Kit listed the user's chat bubble (top right) between alternatives B and C.
    @Test
    fun aLineOutOfPlaceInReadingOrder_staysOutOfTheCrop() {
        val lines =
            listOf(
                "Mesa de Estudos" to LineBox(0.30f, 0.00f, 0.40f, 0.02f),
                "Pergunta de teste - Engenharia de Software" to LineBox(0.23f, 0.17f, 0.46f, 0.20f),
                "Qual das alternativas representa corretamente o modelo ágil" to LineBox(0.24f, 0.20f, 0.75f, 0.23f),
                "Scrum?" to LineBox(0.24f, 0.24f, 0.27f, 0.25f),
                "A) O Scrum exige etapas sequenciais." to LineBox(0.26f, 0.26f, 0.67f, 0.29f),
                "B) O Scrum utiliza ciclos iterativos." to LineBox(0.26f, 0.30f, 0.59f, 0.32f),
                "pergunta para teste" to LineBox(0.63f, 0.08f, 0.74f, 0.11f),
                "OO Scrum não permite mudanças." to LineBox(0.27f, 0.33f, 0.64f, 0.36f),
                "D) O Scrum dispensa a comunicação." to LineBox(0.27f, 0.36f, 0.58f, 0.39f),
                "E) O Scrum exige entrega no final." to LineBox(0.27f, 0.39f, 0.65f, 0.42f),
                "Responder" to LineBox(0.45f, 0.45f, 0.52f, 0.47f),
            )
        val screen = RecognizedText(lines.map { OcrScreens.words(it.first) }, lines.map { it.second })

        val region = questionRegion(screen)!!

        assertTrue(assessQuestion(screen).usable)
        assertTrue("top ${region.top}", region.top > 0.11f)
    }

    // Field photos #10 and #13: the last alternative wraps; its second line belongs to the question.
    @Test
    fun aWrappedLastAlternative_isPartOfTheQuestion() {
        val lines =
            listOf(
                "Mensagem anterior." to LineBox(0.25f, 0.02f, 0.60f, 0.05f),
                "Questão 05 - Engenharia de Software" to LineBox(0.25f, 0.20f, 0.50f, 0.23f),
                "Em relação ao Diagrama de Casos de Uso, assinale a alternativa CORRETA." to LineBox(0.25f, 0.26f, 0.80f, 0.29f),
                "A) Representa a estrutura interna das classes." to LineBox(0.27f, 0.31f, 0.70f, 0.34f),
                "B) Descreve a sequência temporal de mensagens." to LineBox(0.27f, 0.35f, 0.70f, 0.38f),
                "C) Representa as funcionalidades oferecidas." to LineBox(0.27f, 0.39f, 0.70f, 0.42f),
                "D) Demonstra a estrutura física do banco." to LineBox(0.27f, 0.43f, 0.70f, 0.46f),
                "E) É utilizado para representar o fluxo de execução de um programa em linguagem" to LineBox(0.27f, 0.47f, 0.86f, 0.50f),
                "de máquina." to LineBox(0.28f, 0.505f, 0.36f, 0.535f),
                "Verificar resposta" to LineBox(0.45f, 0.60f, 0.55f, 0.62f),
            )
        val screen = RecognizedText(lines.map { OcrScreens.words(it.first) }, lines.map { it.second })

        val region = questionRegion(screen)!!

        assertTrue(assessQuestion(screen).usable)
        assertTrue("bottom ${region.bottom}", region.bottom >= 0.535f && region.bottom < 0.60f)
        // Stored text (no boxes): a continuation starts in lowercase.
        assertEquals(
            "de máquina.",
            questionBlock(
                "Mensagem anterior.\nQuestão 05\nAssinale a alternativa CORRETA sobre UML.\nA) um\nB) dois\nC) três quatro\nde máquina.\nVerificar resposta",
            )!!.last(),
        )
    }
}
