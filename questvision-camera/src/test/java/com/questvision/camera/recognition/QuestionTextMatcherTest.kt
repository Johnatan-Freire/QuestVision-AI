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

import com.questvision.camera.recognition.OcrScreens.fingerprint
import com.questvision.camera.recognition.OcrScreens.questions
import com.questvision.camera.recognition.OcrScreens.screen
import com.questvision.camera.recognition.OcrScreens.words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionTextMatcherTest {
    private val matcher = QuestionTextMatcher()

    private fun verdict(
        candidate: RecognizedText,
        vararg history: RecognizedText,
    ): TextComparison = matcher.compare(fingerprint(candidate), history.map(::fingerprint))

    @Test
    fun normalization_foldsCaseAccentsUnicodeAndPunctuation() {
        assertEquals("questao", normalizeToken("Questão"))
        assertEquals("permissoes", normalizeToken("PERMISSÕES,"))
        // Full-width letters and ligatures (NFKD).
        assertEquals("file", normalizeToken("ﬁle"))
        assertEquals("abc", normalizeToken("ＡＢＣ"))
        // Volatile and meaningless tokens are dropped.
        assertNull(normalizeToken("14:12:24"))
        assertNull(normalizeToken("22/10/2026"))
        assertNull(normalizeToken("A)"))
        assertNull(normalizeToken("—"))
    }

    @Test
    fun firstReadableCapture_isNew() {
        assertEquals(TextVerdict.NEW_QUESTION, verdict(screen(1)).verdict)
    }

    @Test
    fun sameQuestion_differentBrightness_isDuplicate() {
        // Brightness changes the OCR confidence and clock, not the words.
        val dimmer = screen(questions.getValue(1), clock = "14:12:19", confidence = 0.7f)

        assertEquals(TextVerdict.DUPLICATE, verdict(dimmer, screen(1)).verdict)
    }

    @Test
    fun sameQuestion_differentFraming_isDuplicate() {
        // Framed lower: the top chrome lines are cut off, a fragment of the next line is revealed.
        val reframed = screen(questions.getValue(1), dropLines = 3, extraLines = listOf("Inicie uma"))

        assertEquals(TextVerdict.DUPLICATE, verdict(reframed, screen(1)).verdict)
    }

    @Test
    fun sameQuestion_withSmallOcrDifferences_isDuplicate() {
        val q = questions.getValue(2)
        val misread =
            screen(
                q.copy(
                    // Lost accent, a typo in a long word, a dropped word, punctuation noise.
                    statement = "Qual servico de rede e responsavel por traduzlr nomes de dominio como www.google.com, em IP?",
                    alternatives = listOf("A) DHCP", "B) FTP.", "C) DNS", "D) SSH", "E) SMTP"),
                ),
            )
        // Plus a low-confidence misread of a short word.
        val withGarbage = RecognizedText(misread.lines + listOf(listOf(RecognizedWord("ssn", 0.2f))))

        assertEquals(TextVerdict.DUPLICATE, verdict(withGarbage, screen(2)).verdict)
    }

    @Test
    fun differentQuestions_withIdenticalVisualStructure_areNew() {
        // Same layout: title, statement, five alternatives; only the words differ.
        val result = verdict(screen(2), screen(1))

        assertEquals(TextVerdict.NEW_QUESTION, result.verdict)
        assertTrue("novel=${result.novelTokens}", result.novelTokens >= 10)
    }

    @Test
    fun changingOneAlternative_isNew() {
        val q = questions.getValue(3)
        val edited =
            q.copy(
                alternatives =
                    q.alternatives.toMutableList().apply {
                        this[2] = "C) Utilizar biometria facial junto com token físico."
                    },
            )

        assertEquals(TextVerdict.NEW_QUESTION, verdict(screen(edited), screen(q)).verdict)
    }

    @Test
    fun singleShortWordChange_isNotDistinguished_knownLimitation() {
        // "FTP" → "SFTP": one short word. Indistinguishable from an OCR misread of a short word, so
        // it is treated as the same question (documented limitation).
        val q = questions.getValue(2)
        val edited = q.copy(alternatives = q.alternatives.map { if (it == "B) FTP") "B) SFTP" else it })

        assertEquals(TextVerdict.DUPLICATE, verdict(screen(edited), screen(q)).verdict)
    }

    @Test
    fun emptyOrIllegibleText_isUnreadable() {
        assertEquals(TextVerdict.UNREADABLE, verdict(RecognizedText(emptyList())).verdict)
        // Plenty of words, but all low-confidence (glare, blur).
        assertEquals(TextVerdict.UNREADABLE, verdict(screen(questions.getValue(1), confidence = 0.3f)).verdict)
        // Only fragments.
        assertEquals(TextVerdict.UNREADABLE, verdict(RecognizedText(listOf(words("Pergunte ao")))).verdict)
    }

    @Test
    fun clockOnlyChange_isDuplicate() {
        assertEquals(TextVerdict.DUPLICATE, verdict(screen(questions.getValue(1), clock = "14:13:59"), screen(1)).verdict)
    }

    @Test
    fun returningToAnEarlierQuestion_isDuplicateOfIt() {
        val result = verdict(screen(1), screen(1), screen(2), screen(3))

        assertEquals(TextVerdict.DUPLICATE, result.verdict)
        assertEquals(0, result.closestIndex)
    }

    @Test
    fun threeDifferentQuestions_areEachNew() {
        val history = mutableListOf<TextFingerprint>()
        val verdicts =
            (1..3).map { n ->
                matcher
                    .compare(fingerprint(screen(n)), history)
                    .also {
                        if (it.verdict == TextVerdict.NEW_QUESTION) history += fingerprint(screen(n))
                    }.verdict
            }

        assertEquals(List(3) { TextVerdict.NEW_QUESTION }, verdicts)
    }

    @Test
    fun editDistance_andSimHash() {
        assertTrue(withinEdits("traduzir", "traduzlr", 1))
        assertFalse(withinEdits("chmod", "mkdir", 2))
        assertEquals(0, allowedEdits(4))
        val a = fingerprint(screen(1))
        val b = fingerprint(screen(questions.getValue(1), clock = "09:00"))
        assertEquals(a.simHash, b.simHash)
    }

    @Test
    fun urlAndIdFragments_areVolatile() {
        // Read from the address bar in the 2026-10-22 field photos.
        assertNull(normalizeToken("chatgpt.com/g/g-p-6acfe2a12e308191b75d7b268bac8b65"))
        assertNull(normalizeToken("57fecd34e125"))
        assertTrue(isHashLike("2e308191b75d7b268bac8b65colinhafc6ad70675275883e9a0de"))
        // Ordinary words and short codes are kept.
        assertEquals("ipv6", normalizeToken("IPv6"))
        assertEquals("sha256", normalizeToken("SHA256"))
        assertEquals("2fa", normalizeToken("(2FA)?"))
    }

    @Test
    fun chromeReadWithDifferentWordSplits_isNotNewText() {
        // Field photos 1, 3, 4 and 5 (same question): the bookmarks bar and URL were split or glued
        // differently each time, which produced false runs of three "new" words.
        val q = questions.getValue(1)
        val reference =
            screen(
                q,
                extraLines =
                    listOf(
                        "chatgpt.com/g/g-p-6acfe2a12e308191b75d7b268bac8b65-colinha/c/6ad70675-2758-83e9-a0de-57fecd34e125",
                        "centralit g4f brbengenhariadarry ring nt api whatsapp cloudhosting",
                    ),
            )
        val resplit =
            screen(
                q,
                extraLines =
                    listOf(
                        "chatqpt.com/g/g-p-6acfe2a1 2e308191b75d7b268bac8b65-colinhafc/6ad70675-2758-83e9-a0de 57fecd34e125",
                        "central it g4f brb engenharia darry ring nat api",
                    ),
            )

        assertEquals(TextVerdict.DUPLICATE, verdict(resplit, reference).verdict)
        assertEquals(TextVerdict.DUPLICATE, verdict(reference, resplit).verdict)
    }
}
