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
import com.questvision.camera.recognition.OcrScreens
import com.questvision.camera.recognition.RecognizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractedQuestionTest {
    private fun ocr(vararg lines: String) = plainText(RecognizedText(lines.map { OcrScreens.words(it) }))

    @Test
    fun onlyTheQuestion_isKept_withoutTheScreenAround_it() {
        val extracted = extractQuestionText(plainText(OcrScreens.screen(2)))!!

        assertEquals(2, extracted.number)
        assertEquals(5, extracted.alternatives)
        assertTrue(extracted.text.startsWith("Questão 02 — Redes de Computadores"))
        assertTrue(extracted.text.endsWith("E) SMTP"))
        // Browser/app chrome and the clock line are left out.
        assertFalse(extracted.text.contains("ChatGPT"))
        assertFalse(extracted.text.contains("14:12:04"))
    }

    @Test
    fun withTwoQuestionsOnScreen_theLastOneIsSent() {
        val text =
            ocr(
                "Questão 05 — Programação Orientada a Objetos",
                "Na programação orientada a objetos, o encapsulamento tem como finalidade:",
                "D) Transformar código-fonte diretamente em linguagem de máquina.",
                "Questão 06 — Segurança da Informação",
                "Em segurança da informação, qual é a finalidade de uma função de hash como o SHA-256?",
                "A) Criptografar arquivos de maneira reversível.",
                "B) Gerar uma representação de tamanho fixo dos dados.",
                "C) Comprimir arquivos.",
                "D) Impedir acessos não autorizados.",
                "Quando o QuestVision AI capturar a questão, diga próxima",
            )

        val extracted = extractQuestionText(text)!!

        assertEquals(6, extracted.number)
        assertTrue(extracted.text.startsWith("Questão 06"))
        assertTrue(extracted.text.endsWith("D) Impedir acessos não autorizados."))
    }

    @Test
    fun aQuestionStillScrollingIn_isNotReplacedByTheEarlierOne() {
        val text =
            ocr(
                "Questão 05 — Programação",
                "Qual palavra-chave declara uma constante em Kotlin?",
                "A) var",
                "B) val",
                "C) let",
                "Questão 06 — Segurança da Informação",
                "Em segurança da informação, qual é a finalidade de uma função de hash?",
                "A) Cript",
            )

        // The captured question is 06; sending 05 instead would answer the wrong question.
        assertNull(extractQuestionText(text))
    }

    @Test
    fun incompleteText_isNotSent() {
        assertNull(extractQuestionText(null))
        assertNull(extractQuestionText(""))
        // No title.
        assertNull(extractQuestionText(ocr("Qual comando altera permissões?", "A) chmod", "B) mkdir")))
        // Title without alternatives (statement only).
        assertNull(extractQuestionText(ocr("Questão 03 — Segurança", "Qual das alternativas apresenta 2FA?")))
        // Title straight into alternatives: no statement.
        assertNull(extractQuestionText(ocr("Questão 03", "A) Senha", "B) Token")))
    }
}
