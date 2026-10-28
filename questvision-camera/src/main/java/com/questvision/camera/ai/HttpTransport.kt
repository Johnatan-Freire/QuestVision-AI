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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Largest response body read; OpenAI's answers here are a few KB. */
private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

class HttpResponse(
    val status: Int,
    val body: String,
    /** `Retry-After` in seconds, when the server sent one. */
    val retryAfterSeconds: Long?,
)

/** One HTTP POST; a seam so the OpenAI client can be tested without a network. */
interface HttpTransport {
    /**
     * Sends [body] and returns the response, whatever its status. Throws [IOException] when no
     * response arrives (`java.net.SocketTimeoutException` for timeouts) and cancels promptly with the
     * calling coroutine.
     */
    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpResponse
}

/**
 * [HttpTransport] over the platform's HttpURLConnection: no extra library. Never logs anything (the
 * headers carry the API key). Cancelling the coroutine disconnects the socket, so a blocked read ends
 * right away instead of after its timeout.
 */
@Singleton
class UrlConnectionTransport
    @Inject
    constructor() : HttpTransport {
        /** Where the blocking I/O runs, outside the caller's job (see [post]). */
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): HttpResponse {
            val connection = withContext(Dispatchers.IO) { URL(url).openConnection() as HttpURLConnection }
            val cancelled = AtomicBoolean(false)
            // The blocking I/O runs detached from the caller: on cancellation (the user, or the
            // caller's timeout) the caller returns at once, the socket is closed, and the worker stops
            // at its next step or, at worst, at its own connect/read timeout. It returns its failure
            // instead of throwing, so nothing escapes it.
            val call =
                ioScope.async {
                    runCatching { execute(connection, headers, body, connectTimeoutMs, readTimeoutMs, cancelled) }
                }
            val result =
                try {
                    call.await()
                } catch (exc: CancellationException) {
                    cancelled.set(true)
                    connection.disconnect()
                    throw exc
                }
            return result.getOrThrow()
        }

        private fun execute(
            connection: HttpURLConnection,
            headers: Map<String, String>,
            body: ByteArray,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            cancelled: AtomicBoolean,
        ): HttpResponse {
            fun checkCancelled() {
                if (cancelled.get()) throw IOException("Cancelled")
            }
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.doOutput = true
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.setFixedLengthStreamingMode(body.size)
                checkCancelled()
                connection.outputStream.use { it.write(body) }
                checkCancelled()
                val status = connection.responseCode
                val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
                return HttpResponse(
                    status = status,
                    body = stream?.use(::readLimited).orEmpty(),
                    retryAfterSeconds = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull(),
                )
            } finally {
                connection.disconnect()
            }
        }

        private fun readLimited(stream: InputStream): String {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_RESPONSE_BYTES) throw IOException("Response too large")
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
