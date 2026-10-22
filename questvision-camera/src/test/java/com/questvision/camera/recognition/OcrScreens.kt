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

/**
 * OCR results shaped like ML Kit's reading of a whole photographed screen, as in the field test:
 * browser and app chrome, the question with its alternatives, and the taskbar clock.
 */
internal object OcrScreens {
    private val chrome =
        listOf(
            "Planejar aplicativo Android",
            "Estimativa de desenvolvimento",
            "Repositório base MLKitShowcase",
            "Criar aplicativo com IA",
            "Pergunte ao ChatGPT",
        )

    val questions =
        mapOf(
            1 to
                Question(
                    "Questão 01 — Sistemas Operacionais",
                    "Qual comando Linux é utilizado para alterar as permissões de arquivos e diretórios?",
                    listOf("A) mkdir", "B) chmod", "C) grep", "D) pwd", "E) touch"),
                ),
            2 to
                Question(
                    "Questão 02 — Redes de Computadores",
                    "Qual serviço de rede é responsável por traduzir nomes de domínio, como www.google.com, em endereços IP?",
                    listOf("A) DHCP", "B) FTP", "C) DNS", "D) SSH", "E) SMTP"),
                ),
            3 to
                Question(
                    "Questão 03 — Segurança da Informação",
                    "Qual das alternativas apresenta um exemplo de autenticação de dois fatores (2FA)?",
                    listOf(
                        "A) Utilizar uma senha de 12 caracteres.",
                        "B) Utilizar duas senhas diferentes para acessar o sistema.",
                        "C) Utilizar uma senha e um código temporário gerado por aplicativo autenticador.",
                        "D) Alterar a senha a cada 30 dias.",
                        "E) Utilizar a mesma senha em dois dispositivos.",
                    ),
                ),
        )

    data class Question(
        val title: String,
        val statement: String,
        val alternatives: List<String>,
    )

    /** The screen showing [question], as lines of words with a uniform [confidence]. */
    fun screen(
        question: Question,
        clock: String = "14:12:04",
        confidence: Float = 0.9f,
        extraLines: List<String> = emptyList(),
        dropLines: Int = 0,
    ): RecognizedText {
        val lines =
            chrome + question.title + question.statement + question.alternatives + extraLines + listOf("$clock 22/10/2026")
        return RecognizedText(lines.drop(dropLines).map { line -> words(line, confidence) })
    }

    fun screen(number: Int) = screen(questions.getValue(number))

    fun words(
        line: String,
        confidence: Float = 0.9f,
    ) = line.split(" ").filter { it.isNotBlank() }.map { RecognizedWord(it, confidence) }

    fun fingerprint(text: RecognizedText) = TextFingerprint.of(text)
}
