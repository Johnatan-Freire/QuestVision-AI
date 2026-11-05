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
package com.questvision.camera.telegram

import com.questvision.camera.ai.HttpResponse
import com.questvision.camera.ai.HttpTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TelegramClientTest {
    private class FakeTransport(
        var status: Int = 200,
        var body: String = """{"ok":true,"result":{"message_id":77}}""",
        var failure: IOException? = null,
    ) : HttpTransport {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): HttpResponse {
            urls += url
            this.headers += headers
            bodies += String(body, Charsets.UTF_8)
            failure?.let { throw it }
            return HttpResponse(status, this.body, null)
        }
    }

    @Test
    fun sendMessage_postsPlainTextToTheChat_overHttps() =
        runTest {
            val transport = FakeTransport()
            val result = TelegramClient(transport).sendMessage(TEST_TOKEN, 42L, "Questão 2: C) DNS")

            assertEquals(TelegramSendResult.Sent(77L), result)
            assertEquals("https://api.telegram.org/bot${TEST_TOKEN.value}/sendMessage", transport.urls.single())
            val body = Json.parseToJsonElement(transport.bodies.single()).jsonObject
            assertEquals("42", body["chat_id"]!!.jsonPrimitive.content)
            assertEquals("Questão 2: C) DNS", body["text"]!!.jsonPrimitive.content)
            assertFalse("no markup", body.containsKey("parse_mode"))
            // The token travels only in the URL path, never in the body or the headers.
            assertFalse(transport.bodies.single().contains(TEST_TOKEN.value))
            assertFalse(
                transport.headers
                    .single()
                    .values
                    .any { it.contains(TEST_TOKEN.value) },
            )
        }

    @Test
    fun errors_mapToCodes() =
        runTest {
            fun case(
                status: Int,
                description: String,
            ) = FakeTransport(status, """{"ok":false,"error_code":$status,"description":"$description"}""")
            val expected =
                listOf(
                    case(401, "Unauthorized") to TelegramError.UNAUTHORIZED,
                    case(400, "Bad Request: chat not found") to TelegramError.CHAT_NOT_FOUND,
                    case(400, "Bad Request: message text is empty") to TelegramError.BAD_REQUEST,
                    case(403, "Forbidden: bot was blocked by the user") to TelegramError.FORBIDDEN,
                    case(429, "Too Many Requests: retry after 5") to TelegramError.RATE_LIMIT,
                    case(502, "Bad Gateway") to TelegramError.SERVER,
                    FakeTransport(200, """{"ok":false}""") to TelegramError.INVALID_RESPONSE,
                    FakeTransport(200, "not json") to TelegramError.INVALID_RESPONSE,
                    FakeTransport(failure = IOException("https://api.telegram.org/bot…")) to TelegramError.NETWORK,
                )
            for ((transport, error) in expected) {
                val result = TelegramClient(transport).sendMessage(TEST_TOKEN, 42L, "x")
                assertEquals(error.name, error, (result as TelegramSendResult.Failed).error)
            }
        }

    @Test
    fun latestPrivateChat_takesTheNewestPrivateChat() =
        runTest {
            val transport =
                FakeTransport(
                    body =
                        """
                        {"ok":true,"result":[
                          {"update_id":1,"message":{"chat":{"id":111,"type":"private","first_name":"Ana"},"text":"/start"}},
                          {"update_id":2,"message":{"chat":{"id":-500,"type":"group","title":"x"},"text":"hi"}},
                          {"update_id":3,"message":{"chat":{"id":222,"type":"private","first_name":"Bia"},"text":"/start"}},
                          {"update_id":4,"edited_message":{"chat":{"id":333,"type":"private"}}}
                        ]}
                        """.trimIndent(),
                )
            val result = TelegramClient(transport).latestPrivateChat(TEST_TOKEN)

            assertEquals(TelegramChatResult.Found(222L, "Bia"), result)
            assertTrue(transport.urls.single().endsWith("/getUpdates"))
            // No offset: reading the updates confirms (deletes) none of them.
            assertFalse(transport.bodies.single().contains("offset"))
        }

    @Test
    fun latestPrivateChat_withoutMessages_saysSo() =
        runTest {
            val result = TelegramClient(FakeTransport(body = """{"ok":true,"result":[]}""")).latestPrivateChat(TEST_TOKEN)
            assertEquals(TelegramChatResult.NoMessages, result)
        }

    @Test
    fun theToken_isNeverPrinted() {
        assertFalse(TEST_TOKEN.toString().contains(TEST_TOKEN.value))
        assertEquals("…fXYZ", TEST_TOKEN.masked)
    }

    @Test
    fun tokenShape_isCheckedLocally() {
        assertTrue(parseTelegramToken("  ${TEST_TOKEN.value}\n").isSuccess)
        for ((raw, reason) in listOf(
            "" to TelegramTokenError.EMPTY,
            "sk-proj-abc" to TelegramTokenError.INVALID_FORMAT,
            "123:short" to TelegramTokenError.INVALID_FORMAT,
        )) {
            val error = parseTelegramToken(raw).exceptionOrNull() as InvalidTelegramTokenException
            assertEquals(reason, error.reason)
            assertFalse(error.message!!.contains(raw.ifEmpty { "\u0000" }))
        }
    }
}
