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
import com.questvision.camera.data.SentImage
import com.questvision.camera.data.TokenUsage
import com.questvision.camera.settings.ApiKey
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/** The only remote endpoint the app talks to (HTTPS). */
internal const val OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 90_000

/** Flex requests may wait in a queue before running; OpenAI's Flex guide uses 15 minutes. */
private const val FLEX_READ_TIMEOUT_MS = 900_000

/** Whole-call limit on top of the socket timeouts. */
private const val CALL_MARGIN_MS = 15_000L

/** Billing/limit codes OpenAI returns with HTTP 429 (versus plain rate limiting). */
private val QUOTA_CODES =
    setOf(
        "insufficient_quota",
        "credit_balance_exhausted",
        "project_spend_limit_exceeded",
        "organization_spend_limit_exceeded",
        "organization_usage_limit_exceeded",
    )

/** What is sent: [text] always, plus [imageDataUrl] (a `data:image/jpeg;base64,…` URL) in image mode. */
class AiInput(
    val mode: AnalysisMode,
    val text: String,
    val imageDataUrl: String? = null,
    /** The size of the image in [imageDataUrl] (never its content), for the request log. */
    val image: SentImage? = null,
) {
    // Never print the question or the image (logs, crash reports).
    override fun toString() = "AiInput(mode=$mode, textChars=${text.length}, image=$image)"
}

data class AiRequestOptions(
    val model: String,
    /** Upper bound for visible output plus reasoning tokens. */
    val maxOutputTokens: Int,
    /**
     * `service_tier: flex` (half price, slower, may be unavailable) instead of `default`. Always sent:
     * left out, it is `auto`, which follows the project setting and could pick a dearer tier.
     */
    val flex: Boolean = false,
    /** `low` keeps cost and latency down; the questions are short. */
    val reasoningEffort: String = "low",
    /** `high` bounds the image's token cost while keeping screen text legible. */
    val imageDetail: String = "high",
)

sealed interface AiCallResult {
    /** A completed response; [outputText] is the structured JSON, still to be validated. */
    data class Completed(
        val outputText: String,
        val responseId: String?,
        val httpStatus: Int,
        val usage: TokenUsage?,
        /** The tier that served the request, as reported: it sets the price. */
        val serviceTier: String? = null,
    ) : AiCallResult

    data class Failed(
        val error: AiError,
        val httpStatus: Int? = null,
        val responseId: String? = null,
        /** Tokens already billed for a response that came back unusable (incomplete, refused, invalid). */
        val usage: TokenUsage? = null,
        val retryAfterSeconds: Long? = null,
        val serviceTier: String? = null,
    ) : AiCallResult
}

/** Sends one question to the AI. Exactly one HTTP request per call: never retries. */
interface AiClient {
    suspend fun send(
        key: ApiKey,
        input: AiInput,
        options: AiRequestOptions,
    ): AiCallResult
}

/**
 * The OpenAI Responses API (`POST /v1/responses`) with Structured Outputs. `store: false`, so OpenAI
 * keeps no retrievable copy of the request or the response. Nothing here logs: the request holds the
 * key, the question and possibly the photo.
 */
@Singleton
class OpenAiClient
    @Inject
    constructor(
        private val transport: HttpTransport,
    ) : AiClient {
        override suspend fun send(
            key: ApiKey,
            input: AiInput,
            options: AiRequestOptions,
        ): AiCallResult {
            val body = requestBody(input, options).toString().toByteArray(Charsets.UTF_8)
            val headers =
                mapOf(
                    "Authorization" to "Bearer ${key.value}",
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                )
            val readTimeout = if (options.flex) FLEX_READ_TIMEOUT_MS else READ_TIMEOUT_MS
            val response =
                try {
                    withTimeoutOrNull(CONNECT_TIMEOUT_MS + readTimeout + CALL_MARGIN_MS) {
                        transport.post(OPENAI_RESPONSES_URL, headers, body, CONNECT_TIMEOUT_MS, readTimeout)
                    } ?: return AiCallResult.Failed(AiError.TIMEOUT)
                } catch (exc: SocketTimeoutException) {
                    return AiCallResult.Failed(AiError.TIMEOUT)
                } catch (exc: IOException) {
                    return AiCallResult.Failed(AiError.NETWORK)
                }
            return parseResponse(response)
        }
    }

private val json = Json { ignoreUnknownKeys = true }

/** The request JSON (exposed for tests). */
internal fun requestBody(
    input: AiInput,
    options: AiRequestOptions,
): JsonObject =
    buildJsonObject {
        put("model", options.model)
        put("instructions", AI_INSTRUCTIONS)
        putJsonArray("input") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "input_text")
                        put("text", input.text)
                    }
                    input.imageDataUrl?.let { url ->
                        addJsonObject {
                            put("type", "input_image")
                            put("image_url", url)
                            put("detail", options.imageDetail)
                        }
                    }
                }
            }
        }
        putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("name", ANSWER_SCHEMA_NAME)
                put("strict", true)
                put("schema", ANSWER_SCHEMA)
            }
        }
        putJsonObject("reasoning") { put("effort", options.reasoningEffort) }
        put("max_output_tokens", options.maxOutputTokens)
        put("store", false)
        put("service_tier", if (options.flex) "flex" else "default")
    }

/** Maps an HTTP response to a result (exposed for tests). */
internal fun parseResponse(response: HttpResponse): AiCallResult {
    val root = runCatching { json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
    if (response.status !in 200..299) {
        val error = root?.get("error") as? JsonObject
        val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull
        val type = (error?.get("type") as? JsonPrimitive)?.contentOrNull
        val mapped =
            when (response.status) {
                401 -> AiError.AUTH
                403 -> AiError.FORBIDDEN
                429 -> if (code in QUOTA_CODES || type in QUOTA_CODES) AiError.QUOTA else AiError.RATE_LIMIT
                in 400..499 -> AiError.BAD_REQUEST
                else -> AiError.SERVER
            }
        return AiCallResult.Failed(mapped, httpStatus = response.status, retryAfterSeconds = response.retryAfterSeconds)
    }
    if (root == null) return AiCallResult.Failed(AiError.INVALID_RESPONSE, httpStatus = response.status)
    val responseId = (root["id"] as? JsonPrimitive)?.contentOrNull
    val usage = (root["usage"] as? JsonObject)?.let(::tokenUsage)
    val serviceTier = (root["service_tier"] as? JsonPrimitive)?.contentOrNull

    fun failed(error: AiError) = AiCallResult.Failed(error, response.status, responseId, usage, serviceTier = serviceTier)

    val status = (root["status"] as? JsonPrimitive)?.contentOrNull
    if (status == "incomplete") {
        val reason = ((root["incomplete_details"] as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
        return failed(if (reason == "content_filter") AiError.REFUSAL else AiError.INCOMPLETE_OUTPUT)
    }
    if (status == "failed" || status == "cancelled") return failed(AiError.SERVER)
    if (status != "completed") return failed(AiError.INVALID_RESPONSE)
    val parts =
        (root["output"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "message" }
            .flatMap { (it["content"] as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
    if (parts.any { (it["type"] as? JsonPrimitive)?.contentOrNull == "refusal" }) return failed(AiError.REFUSAL)
    val text =
        parts
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "output_text" }
            .mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }
            .joinToString("")
    if (text.isBlank()) return failed(AiError.INVALID_RESPONSE)
    return AiCallResult.Completed(text, responseId, response.status, usage, serviceTier)
}

private fun tokenUsage(usage: JsonObject): TokenUsage {
    fun JsonObject.int(name: String) = (this[name] as? JsonPrimitive)?.intOrNull
    val inputDetails = usage["input_tokens_details"] as? JsonObject
    return TokenUsage(
        inputTokens = usage.int("input_tokens"),
        cachedTokens = inputDetails?.int("cached_tokens"),
        cacheWriteTokens = inputDetails?.int("cache_write_tokens"),
        // Writes by lifetime (GPT-6: 30 min by default, 12 h on request); they sum to cache_write_tokens.
        cacheWrite12hTokens =
            ((inputDetails?.get("cache_write_tokens_details") ?: usage["cache_write_tokens_details"]) as? JsonObject)
                ?.int("ttl_12h_tokens"),
        outputTokens = usage.int("output_tokens"),
        reasoningTokens = (usage["output_tokens_details"] as? JsonObject)?.int("reasoning_tokens"),
    )
}
