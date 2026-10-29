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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class OpenAiClientTest {
    private class RecordingTransport(
        private val reply: suspend () -> HttpResponse,
    ) : HttpTransport {
        var url: String? = null
        var headers: Map<String, String> = emptyMap()
        var body: String? = null
        var calls = 0

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): HttpResponse {
            calls++
            this.url = url
            this.headers = headers
            this.body = String(body, Charsets.UTF_8)
            return reply()
        }
    }

    private val options = AiRequestOptions(model = "gpt-6-luna", maxOutputTokens = 1_500)
    private val textInput = AiInput(AnalysisMode.TEXT, textPrompt("Questão 02 — Redes\nQual serviço…\nA) DHCP\nB) DNS"))

    private suspend fun call(
        input: AiInput = textInput,
        options: AiRequestOptions = this.options,
        reply: suspend () -> HttpResponse,
    ): Pair<AiCallResult, RecordingTransport> {
        val transport = RecordingTransport(reply)
        return OpenAiClient(transport).send(TEST_KEY, input, options) to transport
    }

    @Test
    fun textRequest_followsTheResponsesApi_withStructuredOutputs() =
        runTest {
            val (result, transport) = call { HttpResponse(200, responseBody(), null) }

            assertEquals("https://api.openai.com/v1/responses", transport.url)
            assertEquals("Bearer ${TEST_KEY.value}", transport.headers["Authorization"])
            val body = Json.parseToJsonElement(transport.body!!).jsonObject
            assertEquals("gpt-6-luna", body["model"]!!.jsonPrimitive.content)
            assertFalse(body["store"]!!.jsonPrimitive.boolean)
            assertEquals(1_500, body["max_output_tokens"]!!.jsonPrimitive.int)
            assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            // Always explicit: left out it would be `auto`, i.e. whatever the project is set to.
            assertEquals("default", body["service_tier"]!!.jsonPrimitive.content)
            val format = body["text"]!!.jsonObject["format"]!!.jsonObject
            assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
            assertTrue(format["strict"]!!.jsonPrimitive.boolean)
            val schema = format["schema"]!!.jsonObject
            assertFalse(schema["additionalProperties"]!!.jsonPrimitive.boolean)
            assertEquals(schema["properties"]!!.jsonObject.keys, schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
            val content =
                body["input"]!!
                    .jsonArray
                    .single()
                    .jsonObject["content"]!!
                    .jsonArray
            assertEquals(listOf("input_text"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
            // The key travels only in the header, never in the body.
            assertFalse(transport.body!!.contains(TEST_KEY.value))

            result as AiCallResult.Completed
            assertEquals("resp_abc123", result.responseId)
            assertEquals(SAMPLE_USAGE, result.usage)
            assertEquals("default", result.serviceTier)
            assertEquals(AnswerType.SINGLE, parseAiAnswer(result.outputText)!!.type)
        }

    @Test
    fun imageRequest_carriesThePhotoAsADataUrl_withHighDetail() =
        runTest {
            val image = AiInput(AnalysisMode.IMAGE, IMAGE_PROMPT, "data:image/jpeg;base64,/9j/AAAA")

            val (result, transport) = call(input = image) { HttpResponse(200, responseBody(), null) }

            val content =
                Json
                    .parseToJsonElement(transport.body!!)
                    .jsonObject["input"]!!
                    .jsonArray[0]
                    .jsonObject["content"] as JsonArray
            val part = content.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "input_image" }
            assertEquals("data:image/jpeg;base64,/9j/AAAA", part["image_url"]!!.jsonPrimitive.content)
            assertEquals("high", part["detail"]!!.jsonPrimitive.content)
            assertTrue(result is AiCallResult.Completed)
        }

    @Test
    fun flex_isRequestedOnlyWhenEnabled() =
        runTest {
            val (_, transport) = call(options = options.copy(flex = true)) { HttpResponse(200, responseBody(), null) }

            assertEquals("flex", (Json.parseToJsonElement(transport.body!!) as JsonObject)["service_tier"]!!.jsonPrimitive.content)
        }

    @Test
    fun httpErrors_areMapped_andNeverRetried() =
        runTest {
            val cases =
                listOf(
                    HttpResponse(401, errorBody("invalid_request_error", "invalid_api_key"), null) to AiError.AUTH,
                    HttpResponse(
                        403,
                        errorBody("invalid_request_error", "unsupported_country_region_territory"),
                        null,
                    ) to AiError.FORBIDDEN,
                    HttpResponse(429, errorBody("requests", "rate_limit_exceeded"), 20) to AiError.RATE_LIMIT,
                    HttpResponse(429, errorBody("rate_limit_error", "slow_down"), null) to AiError.RATE_LIMIT,
                    HttpResponse(429, errorBody("insufficient_quota", "credit_balance_exhausted"), null) to AiError.QUOTA,
                    HttpResponse(429, errorBody("insufficient_quota", "project_spend_limit_exceeded"), null) to AiError.QUOTA,
                    HttpResponse(429, errorBody("insufficient_quota", null), null) to AiError.QUOTA,
                    HttpResponse(400, errorBody("invalid_request_error", "invalid_value"), null) to AiError.BAD_REQUEST,
                    HttpResponse(404, errorBody("invalid_request_error", "model_not_found"), null) to AiError.BAD_REQUEST,
                    HttpResponse(500, errorBody("server_error", null), null) to AiError.SERVER,
                    HttpResponse(503, errorBody("service_unavailable_error", "server_is_overloaded"), 5) to AiError.SERVER,
                    HttpResponse(502, "<html>Bad gateway</html>", null) to AiError.SERVER,
                )
            for ((response, expected) in cases) {
                val (result, transport) = call { response }

                result as AiCallResult.Failed
                assertEquals("${response.status} ${response.body}", expected, result.error)
                assertEquals(response.status, result.httpStatus)
                assertEquals(1, transport.calls)
            }
            val (rateLimited, _) = call { HttpResponse(429, errorBody("requests", "rate_limit_exceeded"), 20) }
            assertEquals(20L, (rateLimited as AiCallResult.Failed).retryAfterSeconds)
        }

    @Test
    fun unusableResponses_keepTheBilledUsage() =
        runTest {
            val cases =
                listOf(
                    responseBody(status = "incomplete", outputText = "{\"answer", incompleteReason = "max_output_tokens") to
                        AiError.INCOMPLETE_OUTPUT,
                    responseBody(status = "incomplete", outputText = null, incompleteReason = "content_filter") to AiError.REFUSAL,
                    responseBody(outputText = null, refusal = "I can't help with that.") to AiError.REFUSAL,
                    responseBody(outputText = "") to AiError.INVALID_RESPONSE,
                    responseBody(status = "failed", outputText = null) to AiError.SERVER,
                    responseBody(status = "queued", outputText = null) to AiError.INVALID_RESPONSE,
                )
            for ((body, expected) in cases) {
                val (result, _) = call { HttpResponse(200, body, null) }

                result as AiCallResult.Failed
                assertEquals(expected, result.error)
                assertEquals("resp_abc123", result.responseId)
                assertEquals(SAMPLE_USAGE, result.usage)
            }
            val (garbage, _) = call { HttpResponse(200, "not json", null) }
            assertEquals(AiError.INVALID_RESPONSE, (garbage as AiCallResult.Failed).error)
        }

    @Test
    fun connectionErrors_andTimeouts_areReported() =
        runTest {
            assertEquals(AiError.NETWORK, (call { throw UnknownHostException("api.openai.com") }.first as AiCallResult.Failed).error)
            assertEquals(AiError.NETWORK, (call { throw IOException("Connection reset") }.first as AiCallResult.Failed).error)
            assertEquals(AiError.TIMEOUT, (call { throw SocketTimeoutException("Read timed out") }.first as AiCallResult.Failed).error)
            // A server that never answers: the whole-call limit ends it (virtual time).
            assertEquals(AiError.TIMEOUT, (call { awaitCancellation() }.first as AiCallResult.Failed).error)
        }

    @Test
    fun cacheWritesByLifetime_areRead() =
        runTest {
            val body =
                responseBody().replace(
                    "\"cache_write_tokens\":0}",
                    "\"cache_write_tokens\":1483,\"cache_write_tokens_details\":{\"ttl_30m_tokens\":1083,\"ttl_12h_tokens\":400}}",
                )
            val (result, _) = call { HttpResponse(200, body, null) }

            result as AiCallResult.Completed
            assertEquals(1483, result.usage!!.cacheWriteTokens)
            assertEquals(400, result.usage!!.cacheWrite12hTokens)
        }

    @Test
    fun inputs_neverPrintTheirContent() {
        val input = AiInput(AnalysisMode.IMAGE, "Questão secreta", "data:image/jpeg;base64,AAAA")

        assertFalse(input.toString().contains("secreta"))
        assertFalse(input.toString().contains("base64"))
        assertFalse(EncodedImage("data:image/jpeg;base64,AAAA", 1, 1).toString().contains("base64"))
    }
}
