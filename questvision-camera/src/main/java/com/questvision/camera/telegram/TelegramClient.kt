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

import com.questvision.camera.ai.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The Bot API (HTTPS). The token is part of the path: no URL built here is ever logged. */
internal const val TELEGRAM_API = "https://api.telegram.org"

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 20_000

/** Why a Bot API call failed. Codes only: never the token, the chat or the text. */
enum class TelegramError {
    /** 401: the token is wrong or was revoked. */
    UNAUTHORIZED,

    /** 400 "chat not found": wrong chat, or the user never wrote to the bot. */
    CHAT_NOT_FOUND,

    /** 403: the user blocked the bot or left the chat. */
    FORBIDDEN,
    RATE_LIMIT,
    BAD_REQUEST,
    SERVER,

    /** No reply (connection lost, timeout): the message may still have arrived. */
    NETWORK,
    INVALID_RESPONSE,
}

sealed interface TelegramSendResult {
    data class Sent(
        val messageId: Long?,
    ) : TelegramSendResult

    data class Failed(
        val error: TelegramError,
        val httpStatus: Int? = null,
    ) : TelegramSendResult
}

sealed interface TelegramChatResult {
    /** The private chat of the newest message the bot received, and the sender's first name. */
    data class Found(
        val chatId: Long,
        val firstName: String?,
    ) : TelegramChatResult

    /** The bot has no recent message: the user should send it one (e.g. /start) first. */
    data object NoMessages : TelegramChatResult

    data class Failed(
        val error: TelegramError,
        val httpStatus: Int? = null,
    ) : TelegramChatResult
}

/** The two Bot API calls the app makes. One HTTP request per call: never retries. */
interface TelegramApi {
    suspend fun sendMessage(
        token: TelegramToken,
        chatId: Long,
        text: String,
    ): TelegramSendResult

    suspend fun latestPrivateChat(token: TelegramToken): TelegramChatResult
}

private val json = Json { ignoreUnknownKeys = true }

@Singleton
class TelegramClient
    @Inject
    constructor(
        private val transport: HttpTransport,
    ) : TelegramApi {
        override suspend fun sendMessage(
            token: TelegramToken,
            chatId: Long,
            text: String,
        ): TelegramSendResult {
            // Plain text (no parse_mode): nothing in an answer can be read as markup.
            val body =
                buildJsonObject {
                    put("chat_id", chatId)
                    put("text", text)
                }
            return when (val reply = call(token, "sendMessage", body)) {
                is Reply.Ok -> TelegramSendResult.Sent((reply.result as? JsonObject)?.long("message_id"))
                is Reply.Error -> TelegramSendResult.Failed(reply.error, reply.httpStatus)
            }
        }

        override suspend fun latestPrivateChat(token: TelegramToken): TelegramChatResult {
            // Reading updates doesn't confirm them (no offset), so it changes nothing on Telegram's side.
            val body =
                buildJsonObject {
                    put("limit", 20)
                    put("timeout", 0)
                    putJsonArray("allowed_updates") { add(JsonPrimitive("message")) }
                }
            return when (val reply = call(token, "getUpdates", body)) {
                is Reply.Error -> {
                    TelegramChatResult.Failed(reply.error, reply.httpStatus)
                }

                is Reply.Ok -> {
                    val chat =
                        (reply.result as? JsonArray)
                            .orEmpty()
                            .mapNotNull { ((it as? JsonObject)?.get("message") as? JsonObject)?.get("chat") as? JsonObject }
                            .lastOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "private" }
                    val id = chat?.long("id")
                    if (chat == null || id == null) {
                        TelegramChatResult.NoMessages
                    } else {
                        TelegramChatResult.Found(id, (chat["first_name"] as? JsonPrimitive)?.contentOrNull)
                    }
                }
            }
        }

        private sealed interface Reply {
            data class Ok(
                val result: Any?,
            ) : Reply

            data class Error(
                val error: TelegramError,
                val httpStatus: Int?,
            ) : Reply
        }

        private suspend fun call(
            token: TelegramToken,
            method: String,
            body: JsonObject,
        ): Reply {
            val response =
                try {
                    transport.post(
                        url = "$TELEGRAM_API/bot${token.value}/$method",
                        headers = mapOf("Content-Type" to "application/json"),
                        body = body.toString().toByteArray(Charsets.UTF_8),
                        connectTimeoutMs = CONNECT_TIMEOUT_MS,
                        readTimeoutMs = READ_TIMEOUT_MS,
                    )
                } catch (exc: CancellationException) {
                    throw exc
                } catch (exc: IOException) {
                    // The exception may name the URL, so it is dropped, not logged.
                    return Reply.Error(TelegramError.NETWORK, null)
                }
            val root = runCatching { json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
            val ok = (root?.get("ok") as? JsonPrimitive)?.booleanOrNull
            if (response.status == 200 && ok == true) return Reply.Ok(root?.get("result"))
            val description = (root?.get("description") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val error =
                when {
                    response.status == 200 -> TelegramError.INVALID_RESPONSE
                    response.status == 401 -> TelegramError.UNAUTHORIZED
                    response.status == 403 -> TelegramError.FORBIDDEN
                    response.status == 429 -> TelegramError.RATE_LIMIT
                    response.status == 400 && description.contains("chat not found", ignoreCase = true) -> TelegramError.CHAT_NOT_FOUND
                    response.status in 400..499 -> TelegramError.BAD_REQUEST
                    else -> TelegramError.SERVER
                }
            return Reply.Error(error, response.status)
        }
    }

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
