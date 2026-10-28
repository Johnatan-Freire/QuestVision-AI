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

import com.questvision.camera.data.TokenUsage
import com.questvision.camera.settings.AiSettings
import com.questvision.camera.settings.AiSettingsStore
import com.questvision.camera.settings.ApiKey
import com.questvision.camera.settings.ApiKeySource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** A syntactically valid, fake key: never a real one. */
internal val TEST_KEY = ApiKey("sk-test-NotARealKey-0123456789abcdef")

internal class FakeKeySource(
    var key: ApiKey? = TEST_KEY,
) : ApiKeySource {
    override suspend fun <T> useKey(block: suspend (ApiKey) -> T): T? = key?.let { block(it) }
}

/** Plays OpenAI: answers each call from [results] (the last one repeats), optionally waiting on [gate]. */
internal class FakeAiClient(
    vararg results: AiCallResult,
) : AiClient {
    private val queue = ArrayDeque(results.toList())
    val inputs = mutableListOf<AiInput>()
    val options = mutableListOf<AiRequestOptions>()
    val keys = mutableListOf<ApiKey>()
    var gate: CompletableDeferred<Unit>? = null
    val calls get() = inputs.size

    /** From now on replies with [results] in order, repeating the last one. */
    fun replyWith(vararg results: AiCallResult) {
        queue.clear()
        queue.addAll(results)
    }

    override suspend fun send(
        key: ApiKey,
        input: AiInput,
        options: AiRequestOptions,
    ): AiCallResult {
        keys += key
        inputs += input
        this.options += options
        gate?.await()
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }
}

internal class FakeImageEncoder(
    var image: EncodedImage? = EncodedImage("data:image/jpeg;base64,/9j/AAAA", 1600, 1200),
) : QuestionImageEncoder {
    val requests = mutableListOf<Pair<File, Int>>()

    override suspend fun encode(
        file: File,
        maxDimension: Int,
    ): EncodedImage? {
        requests += file to maxDimension
        return image
    }
}

internal class InMemoryAiSettingsStore(
    var settings: AiSettings = AiSettings(firstCallConfirmed = true),
) : AiSettingsStore {
    override fun load() = settings

    override fun save(settings: AiSettings) {
        this.settings = settings
    }
}

internal val SAMPLE_USAGE = TokenUsage(inputTokens = 412, cachedTokens = 0, outputTokens = 96, reasoningTokens = 40, cacheWriteTokens = 0)

/** The structured output the model returns. */
internal fun answerJson(
    type: String = "single",
    answer: List<String> = listOf("C"),
    number: Int? = 2,
    confidence: String = "high",
    needsReview: Boolean = false,
    explanation: String = "O DNS traduz nomes de domínio em endereços IP.",
): String =
    buildJsonObject {
        put("question_number", number)
        put("answer_type", type)
        putJsonArray("answer") { answer.forEach { add(it) } }
        put("explanation", explanation)
        put("confidence", confidence)
        put("needs_review", needsReview)
    }.toString()

internal fun completed(
    output: String = answerJson(),
    usage: TokenUsage? = SAMPLE_USAGE,
) = AiCallResult.Completed(output, "resp_test_1", 200, usage, serviceTier = "default")

/** A full Responses API body, as OpenAI documents it. */
internal fun responseBody(
    status: String = "completed",
    outputText: String? = answerJson(),
    refusal: String? = null,
    incompleteReason: String? = null,
): String =
    buildJsonObject {
        put("id", "resp_abc123")
        put("object", "response")
        put("status", status)
        put("service_tier", "default")
        if (incompleteReason != null) putJsonObject("incomplete_details") { put("reason", incompleteReason) }
        putJsonArray("output") {
            addJsonObject {
                put("type", "reasoning")
                put("id", "rs_1")
                putJsonArray("summary") {}
            }
            addJsonObject {
                put("type", "message")
                put("id", "msg_1")
                put("role", "assistant")
                putJsonArray("content") {
                    outputText?.let { text ->
                        addJsonObject {
                            put("type", "output_text")
                            put("text", text)
                            putJsonArray("annotations") {}
                        }
                    }
                    refusal?.let { text ->
                        addJsonObject {
                            put("type", "refusal")
                            put("refusal", text)
                        }
                    }
                }
            }
        }
        putJsonObject("usage") {
            put("input_tokens", 412)
            putJsonObject("input_tokens_details") {
                put("cached_tokens", 0)
                put("cache_write_tokens", 0)
            }
            put("output_tokens", 96)
            putJsonObject("output_tokens_details") { put("reasoning_tokens", 40) }
            put("total_tokens", 508)
        }
    }.toString()

internal fun errorBody(
    type: String,
    code: String?,
): String =
    buildJsonObject {
        putJsonObject("error") {
            put("message", "Test error")
            put("type", type)
            put("code", code)
            put("param", null as String?)
        }
    }.toString()
