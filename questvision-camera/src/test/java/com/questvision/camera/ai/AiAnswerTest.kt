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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAnswerTest {
    @Test
    fun validStructuredAnswer_isParsed() {
        val answer = parseAiAnswer(answerJson(), AnalysisMode.TEXT)!!

        assertEquals(2, answer.questionNumber)
        assertEquals(AnswerType.SINGLE, answer.type)
        assertEquals(listOf("C"), answer.answer)
        assertEquals(AnswerConfidence.HIGH, answer.confidence)
        assertFalse(answer.needsReview)
        assertEquals(AnalysisMode.TEXT, answer.mode)
    }

    @Test
    fun answersAreNormalized() {
        assertEquals(listOf("B"), parseAiAnswer(answerJson(answer = listOf(" b) ")))!!.answer)
        assertEquals(listOf("A", "C"), parseAiAnswer(answerJson(type = "multiple", answer = listOf("A", "Alternativa C", "a")))!!.answer)
        // A question without alternatives: a short free answer is kept as is.
        assertEquals(listOf("42"), parseAiAnswer(answerJson(answer = listOf("42")))!!.answer)
    }

    @Test
    fun uncertainOrInconsistentAnswers_alwaysNeedReview() {
        assertTrue(parseAiAnswer(answerJson(confidence = "low"))!!.needsReview)
        assertTrue(parseAiAnswer(answerJson(needsReview = true))!!.needsReview)
        // "single" with two letters: not trusted.
        assertTrue(parseAiAnswer(answerJson(answer = listOf("A", "B")))!!.needsReview)
        // "multiple" with one letter.
        assertTrue(parseAiAnswer(answerJson(type = "multiple", answer = listOf("A")))!!.needsReview)
        assertTrue(parseAiAnswer(answerJson(explanation = " "))!!.needsReview)
    }

    @Test
    fun noCorrectAlternative_orNotEnoughInformation_areAccepted_withoutForcingALetter() {
        val none = parseAiAnswer(answerJson(type = "none_correct", answer = emptyList()))!!
        val unknown = parseAiAnswer(answerJson(type = "cannot_determine", answer = listOf("B"), number = null))!!

        assertEquals(AnswerType.NONE_CORRECT, none.type)
        assertTrue(none.answer.isEmpty())
        assertTrue(none.needsReview)
        assertEquals(AnswerType.CANNOT_DETERMINE, unknown.type)
        assertTrue(unknown.answer.isEmpty())
        assertNull(unknown.questionNumber)
        assertTrue(unknown.needsReview)
    }

    @Test
    fun invalidOrIncompleteOutput_isRejected() {
        assertNull(parseAiAnswer("not json"))
        assertNull(parseAiAnswer("""{"answer_type":"single","answer":["A"]}"""))
        assertNull(parseAiAnswer(answerJson(type = "maybe")))
        assertNull(parseAiAnswer(answerJson(confidence = "0.9")))
        assertNull(
            parseAiAnswer(
                """{"question_number":1,"answer_type":"single","answer":"A","explanation":"x","confidence":"high","needs_review":false}""",
            ),
        )
        assertNull(
            parseAiAnswer(
                """{"question_number":1,"answer_type":"single","answer":["A"],"explanation":"x","confidence":"high","needs_review":"no"}""",
            ),
        )
    }

    @Test
    fun storedJson_roundTrips_withTheMode() {
        val answer = parseAiAnswer(answerJson(type = "multiple", answer = listOf("A", "D")), AnalysisMode.IMAGE)!!

        val stored = storedAiAnswer(answer.toStoredJson())

        assertEquals(answer, stored)
        assertNull(storedAiAnswer(null))
    }

    @Test
    fun longExplanations_areCapped() {
        val answer = parseAiAnswer(answerJson(explanation = "x".repeat(10_000)))!!

        assertEquals(2_000, answer.explanation.length)
    }
}
