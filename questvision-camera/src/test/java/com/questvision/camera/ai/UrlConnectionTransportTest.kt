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

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The real HttpURLConnection transport against a minimal local HTTP server (no internet, nothing sent
 * to OpenAI).
 */
class UrlConnectionTransportTest {
    private class Reply(
        val status: Int,
        val body: String,
        val retryAfter: String? = null,
        val delayMs: Long = 0,
    )

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val base = "http://127.0.0.1:${server.localPort}"
    private val transport = UrlConnectionTransport()
    private val release = CountDownLatch(1)

    @Volatile private var reply = Reply(200, "{}")

    @Volatile var receivedHeaders: List<String> = emptyList()

    @Volatile var receivedBody: String = ""

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val headers = generateSequence { input.readLine() }.takeWhile { line -> line.isNotEmpty() }.toList()
            val length =
                headers
                    .firstOrNull { h -> h.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.toInt() ?: 0
            val body =
                CharArray(length).also { chars ->
                    var read = 0
                    while (read <
                        length
                    ) {
                        read += input.read(chars, read, length - read)
                    }
                }
            receivedHeaders = headers
            receivedBody = String(body)
            val r = reply
            if (r.delayMs > 0) release.await(r.delayMs, TimeUnit.MILLISECONDS)
            val bytes = r.body.toByteArray()
            val head =
                buildString {
                    append("HTTP/1.1 ${r.status} X\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                    r.retryAfter?.let { value -> append("Retry-After: $value\r\n") }
                    append("Connection: close\r\n\r\n")
                }
            runCatching {
                it.getOutputStream().apply {
                    write(head.toByteArray(Charsets.ISO_8859_1))
                    write(bytes)
                    flush()
                }
            }
        }
    }

    @After
    fun tearDown() {
        release.countDown()
        server.close()
    }

    private suspend fun post(readTimeoutMs: Int = 5_000) =
        transport.post("$base/v1/responses", mapOf("Authorization" to "Bearer test"), "{\"q\":1}".toByteArray(), 2_000, readTimeoutMs)

    @Test
    fun success_returnsBodyAndSendsHeaders() =
        runBlocking {
            reply = Reply(200, "{\"status\":\"completed\"}")

            val response = post()

            assertEquals(200, response.status)
            assertEquals("{\"status\":\"completed\"}", response.body)
            assertTrue(receivedHeaders.first().startsWith("POST /v1/responses"))
            assertTrue(receivedHeaders.any { it.equals("Authorization: Bearer test", ignoreCase = true) })
            assertEquals("{\"q\":1}", receivedBody)
        }

    @Test
    fun errorStatus_returnsTheErrorBody_andRetryAfter() =
        runBlocking {
            reply = Reply(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}", retryAfter = "12")

            val response = post()

            assertEquals(429, response.status)
            assertTrue(response.body.contains("rate_limit_exceeded"))
            assertEquals(12L, response.retryAfterSeconds)
        }

    @Test
    fun slowServer_timesOut() =
        runBlocking {
            reply = Reply(200, "{}", delayMs = 10_000)

            try {
                post(readTimeoutMs = 300)
                fail("Expected a timeout")
            } catch (expected: SocketTimeoutException) {
            }
        }

    @Test
    fun noServer_isAConnectionError() =
        runBlocking {
            val closedPort = ServerSocket(0).use { it.localPort }
            try {
                transport.post("http://127.0.0.1:$closedPort/", emptyMap(), ByteArray(0), 2_000, 2_000)
                fail("Expected a connection error")
            } catch (expected: IOException) {
            }
        }

    @Test
    fun cancelling_endsABlockedRequestPromptly() =
        runBlocking {
            reply = Reply(200, "{}", delayMs = 30_000)
            val started = System.nanoTime()

            val call = async { post(readTimeoutMs = 30_000) }
            delay(300)
            call.cancelAndJoin()

            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $elapsedMs ms", elapsedMs < 5_000)
            assertTrue(call.isCancelled)
        }

    @Test
    fun cancellingRightAway_evenBeforeConnecting_returnsPromptly() =
        runBlocking {
            reply = Reply(200, "{}", delayMs = 30_000)
            repeat(20) { attempt ->
                val started = System.nanoTime()

                val call = async { post(readTimeoutMs = 30_000) }
                delay(attempt % 5L)
                call.cancelAndJoin()

                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue("attempt $attempt took $elapsedMs ms", elapsedMs < 2_000)
            }
        }
}
