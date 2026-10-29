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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Process
import android.util.Base64
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.questvision.camera.CaptureOrigin
import com.questvision.camera.CaptureRepository
import com.questvision.camera.MAX_RECENT_CAPTURES
import com.questvision.camera.data.AI_LIMIT_WINDOW_MS
import com.questvision.camera.data.AiRequestOutcome
import com.questvision.camera.data.AiRequestStart
import com.questvision.camera.data.AiStatus
import com.questvision.camera.data.AnalysisMode
import com.questvision.camera.data.CaptureSessionEntity
import com.questvision.camera.data.QuestVisionDatabase
import com.questvision.camera.data.QuestionEntity
import com.questvision.camera.data.QuestionRepository
import com.questvision.camera.data.TransactionRunner
import com.questvision.camera.recognition.MlKitTextRecognitionEngine
import com.questvision.camera.recognition.RecognizedText
import com.questvision.camera.recognition.RecognizedWord
import com.questvision.camera.settings.AiSettings
import com.questvision.camera.settings.AiSettingsStore
import com.questvision.camera.settings.ApiKey
import com.questvision.camera.settings.ApiKeySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A syntactically valid, fake key: never a real one. */
private val FAKE_KEY = ApiKey("sk-test-NotARealKey-0123456789abcdef")

private const val SESSION = "20261028-100000"

/** Words of the question and of the answer: none may reach the logcat. */
private const val STATEMENT = "Qual protocolo traduz nomes de dominio em enderecos numericos?"
private const val EXPLANATION = "Explicacao sigilosa: o resolvedor converte nomes em enderecos."

/**
 * The analysis flow on a device, end to end except for the network: the real [QuestionAnalyzer],
 * [OpenAiClient] (request body, response parsing, error mapping), repository, Room/SQLite and files,
 * with a scripted [HttpTransport] in place of OpenAI. Nothing leaves the device and no key is used.
 * Works in the test app's own database and cache, never in the app's data.
 */
@RunWith(AndroidJUnit4::class)
class QuestionAnalyzerDeviceTest {
    /** Plays OpenAI: answers each POST with the next scripted reply (the last one repeats). */
    private class ScriptedTransport : HttpTransport {
        private val replies = ArrayDeque<() -> HttpResponse>()
        val bodies = mutableListOf<String>()
        val calls get() = bodies.size

        fun reply(vararg next: () -> HttpResponse) {
            replies.clear()
            replies.addAll(next)
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): HttpResponse {
            check(url == "https://api.openai.com/v1/responses")
            bodies += String(body, Charsets.UTF_8)
            val next = if (replies.size > 1) replies.removeFirst() else replies.first()
            return next()
        }
    }

    private class MemorySettings(
        var settings: AiSettings,
    ) : AiSettingsStore {
        override fun load() = settings

        override fun save(settings: AiSettings) {
            this.settings = settings
        }
    }

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workDir = File(context.cacheDir, "analyzer-device-test")
    private val dbName = "analyzer-device-test.db"
    private val captures = CaptureRepository(File(workDir, "captures"))
    private val transport = ScriptedTransport()
    private val settings = MemorySettings(AiSettings(firstCallConfirmed = true, requestsPerDay = 10))
    private var now = 1_800_000_000_000L
    private lateinit var database: QuestVisionDatabase

    private fun open() = Room.databaseBuilder(context, QuestVisionDatabase::class.java, dbName).build()

    private fun repository() =
        QuestionRepository(
            database.questionDao(),
            database.aiRequestDao(),
            object : TransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction { block() }
            },
            captures,
            Dispatchers.IO,
            clock = { now },
        )

    private fun analyzer(
        repository: QuestionRepository,
        images: QuestionImageEncoder? = null,
    ) = QuestionAnalyzer(
        repository,
        object : ApiKeySource {
            override suspend fun <T> useKey(block: suspend (ApiKey) -> T): T = block(FAKE_KEY)
        },
        OpenAiClient(transport),
        images ?: object : QuestionImageEncoder {
            override suspend fun encode(
                file: File,
                maxDimension: Int,
            ): ImageEncoding = ImageEncoding.Unreadable
        },
        settings,
    )

    /** The app after a process death: a new database connection, repository and analyzer. */
    private suspend fun restart(): Pair<QuestionRepository, QuestionAnalyzer> {
        database.close()
        database = open()
        val repository = repository()
        repository.reconcile()
        return repository to analyzer(repository)
    }

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        workDir.deleteRecursively()
        database = open()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(dbName)
        workDir.deleteRecursively()
    }

    /** A pending automatic capture with its photo on disk and a complete question in its OCR text. */
    private suspend fun storeQuestion(sequence: Int): QuestionEntity {
        val file = captures.newAutomaticCaptureFile(SESSION, sequence, nowMs = now + sequence).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val dao = database.questionDao()
        dao.insertSession(CaptureSessionEntity(SESSION, now))
        val question =
            QuestionEntity(
                id = "q$sequence",
                sessionId = SESSION,
                sequence = sequence,
                questionNumber = sequence,
                origin = CaptureOrigin.AUTOMATIC,
                imagePath = captures.relativePath(file),
                capturedAt = now,
                ocrText = "Questão %02d — Redes\n$STATEMENT\nA) DHCP\nB) DNS\nC) FTP\nD) SMTP".format(sequence),
                dedupKey = null,
                aiStatus = AiStatus.PENDING,
                updatedAt = now,
            )
        dao.insertQuestion(question)
        return question
    }

    private fun ok(): () -> HttpResponse = { HttpResponse(200, responseJson(), null) }

    @Test
    fun anAnswer_isParsed_stored_andSurvivesARestart() =
        runBlocking {
            val question = storeQuestion(1)
            transport.reply(ok())

            val result = analyzer(repository().also { it.reconcile() }).analyze(question.id, AnalysisMode.TEXT)

            result as AnalysisResult.Answered
            assertEquals(listOf("B"), result.answer.answer)
            assertEquals(1, transport.calls)
            // What was sent: the question block, the default tier, nothing stored at OpenAI.
            val sent = transport.bodies.single()
            assertTrue(sent.contains(STATEMENT))
            assertTrue(sent.contains("\"service_tier\":\"default\""))
            assertTrue(sent.contains("\"store\":false"))
            assertFalse(sent.contains(FAKE_KEY.value))

            val (repository, _) = restart()
            val stored = repository.question(question.id)!!
            assertEquals(AiStatus.ANSWERED, stored.aiStatus)
            assertEquals("B", parseAiAnswer(stored.aiResponse!!)!!.answer.single())
            assertEquals(question.ocrText, stored.ocrText)
            assertTrue(repository.fileOf(stored).exists())
            val log = repository.aiRequestsFor(question.id).single()
            assertEquals(AiRequestOutcome.SUCCEEDED, log.outcome)
            assertEquals("resp_device_1", log.responseId)
            assertEquals(412, log.inputTokens)
            assertEquals(96, log.outputTokens)
            assertEquals(40, log.reasoningTokens)
            assertEquals(0, log.cacheWriteTokens)
            assertEquals("default", log.serviceTier)
            assertEquals(1, repository.aiRequestsInLimitWindow())
            // Nothing is sent again on start-up.
            assertEquals(1, transport.calls)
        }

    /** The field failure of 2026-10-28: an old answered question was deleted by the retention cleanup. */
    @Test
    fun anOldAnsweredQuestion_survivesTheCleanup_andARestart() =
        runBlocking {
            val question = storeQuestion(1)
            transport.reply(ok())
            val repository = repository().also { it.reconcile() }
            assertTrue(analyzer(repository).analyze(question.id, AnalysisMode.TEXT) is AnalysisResult.Answered)
            repeat(MAX_RECENT_CAPTURES + 2) { i ->
                val manual = captures.newManualCaptureFile(nowMs = now + 60_000L + i * 1_000L).apply { writeBytes(byteArrayOf(1)) }
                repository
                    .recordCapture(
                        manual,
                        CaptureOrigin.MANUAL,
                        null,
                        null,
                        now + 60_000L + i * 1_000L,
                        null,
                        AiStatus.NOT_REQUESTED,
                    ).getOrThrow()
            }

            assertEquals(2, repository.prune())
            val (restarted, _) = restart()
            restarted.prune()

            val stored = restarted.question(question.id)!!
            assertEquals(AiStatus.ANSWERED, stored.aiStatus)
            assertEquals("B", parseAiAnswer(stored.aiResponse!!)!!.answer.single())
            assertTrue(restarted.fileOf(stored).exists())
        }

    @Test
    fun errors_areRecorded_neverRetried_andKeepThePhotoAndText() =
        runBlocking {
            val cases =
                listOf<Pair<() -> HttpResponse, AiError>>(
                    { HttpResponse(401, errorJson("invalid_request_error", "invalid_api_key"), null) } to AiError.AUTH,
                    { HttpResponse(429, errorJson("insufficient_quota", "credit_balance_exhausted"), null) } to AiError.QUOTA,
                    { HttpResponse(429, errorJson("requests", "rate_limit_exceeded"), 20) } to AiError.RATE_LIMIT,
                    { HttpResponse(500, errorJson("server_error", null), null) } to AiError.SERVER,
                    { HttpResponse(200, responseJson(status = "incomplete"), null) } to AiError.INCOMPLETE_OUTPUT,
                    { HttpResponse(200, "not json", null) } to AiError.INVALID_RESPONSE,
                    { throw UnknownHostException("api.openai.com") } to AiError.NETWORK,
                    { throw IOException("Connection reset") } to AiError.NETWORK,
                    { throw SocketTimeoutException("Read timed out") } to AiError.TIMEOUT,
                )
            val repository = repository().also { it.reconcile() }
            val analyzer = analyzer(repository)
            cases.forEachIndexed { i, (reply, expected) ->
                val question = storeQuestion(i + 1)
                transport.reply(reply)

                val result = analyzer.analyze(question.id, AnalysisMode.TEXT)

                assertEquals(expected.name, AnalysisResult.Failed(expected), result)
                assertEquals("one request per attempt", i + 1, transport.calls)
                val stored = repository.question(question.id)!!
                assertEquals(AiStatus.FAILED, stored.aiStatus)
                assertEquals(expected.name, stored.lastError)
                assertEquals(question.ocrText, stored.ocrText)
                assertTrue(repository.fileOf(stored).exists())
                val log = repository.aiRequestsFor(question.id).single()
                // An error from OpenAI is a known failure; no answer at all is unknown (maybe billed).
                assertEquals(if (expected.outcomeUnknown) AiRequestOutcome.UNKNOWN else AiRequestOutcome.FAILED, log.outcome)
                assertEquals(expected.name, log.errorCode)
            }
            // Billed usage of an unusable response is kept for the accounting.
            assertEquals(412, repository.aiRequestsFor("q5").single().inputTokens)

            val (restarted, _) = restart()
            assertEquals(cases.size, database.questionDao().questionsWithAiStatus(AiStatus.FAILED).size)
            assertEquals(cases.size, restarted.aiRequestsInLimitWindow())
            assertEquals(cases.size, transport.calls)
        }

    @Test
    fun tenRequestsIn24Hours_thenTheLimit_whichSurvivesARestart() =
        runBlocking {
            val questions = (1..11).map { storeQuestion(it) }
            val repository = repository().also { it.reconcile() }
            val analyzer = analyzer(repository)
            // Failures count too: OpenAI may have billed them.
            transport.reply(ok(), ok(), ok(), { HttpResponse(500, errorJson("server_error", null), null) }, ok())

            questions.take(10).forEach { analyzer.analyze(it.id, AnalysisMode.TEXT) }
            assertEquals(10, transport.calls)
            assertEquals(10, repository.aiRequestsInLimitWindow())

            assertEquals(AnalysisResult.Rejected(AnalysisRejection.REQUEST_LIMIT), analyzer.analyze(questions[10].id, AnalysisMode.TEXT))
            assertEquals(AiStatus.PENDING, repository.question(questions[10].id)!!.aiStatus)

            now += 60_000L
            val (restarted, restartedAnalyzer) = restart()
            assertEquals(10, restarted.aiRequestsInLimitWindow())
            assertEquals(
                AnalysisResult.Rejected(AnalysisRejection.REQUEST_LIMIT),
                restartedAnalyzer.analyze(questions[10].id, AnalysisMode.TEXT),
            )
            // The atomic check in SQLite refuses too, without claiming or logging.
            assertEquals(AiRequestStart.LimitReached, restarted.beginAiRequest(questions[10].id, "gpt-6-luna", AnalysisMode.TEXT, 10))
            assertEquals(10, transport.calls)
            assertEquals(10, database.aiRequestDao().countSince(0L))

            // 24 h after the first request, there is room again.
            now += AI_LIMIT_WINDOW_MS
            transport.reply(ok())
            assertTrue(restartedAnalyzer.analyze(questions[10].id, AnalysisMode.TEXT) is AnalysisResult.Answered)
            assertEquals(11, transport.calls)
        }

    /**
     * Phase 3.4 on the device: a monitoring session's automatic analysis over real SQLite and files,
     * with the real client parsing scripted replies. Each validated question is sent once, one at a
     * time; a repeated submission and an earlier answered question are not sent again; nothing is
     * resent after a restart.
     */
    @Test
    fun anAutomaticSession_sendsEachQuestionOnce_andNothingAfterARestart() =
        runBlocking {
            val first = storeQuestion(1)
            val second = storeQuestion(2)
            transport.reply(ok())
            val repository = repository().also { it.reconcile() }
            val auto =
                AutoAnalysisCoordinator(
                    analyzer(repository),
                    repository,
                    settings,
                    object : AutoSendPolicy {
                        override val realRequestsAllowed = true
                    },
                ).apply {
                    attach(this@runBlocking)
                    startSession()
                }
            val screen = { n: Int ->
                RecognizedText(
                    "Questão %02d — Redes\n$STATEMENT\nA) DHCP\nB) DNS\nC) FTP\nD) SMTP\nPergunte".format(n).lines().map { line ->
                        line.split(" ").map { RecognizedWord(it, 0.9f) }
                    },
                )
            }

            auto.submit(first, assessQuestion(screen(1)))
            auto.submit(second, assessQuestion(screen(2)))
            auto.submit(first, assessQuestion(screen(1)))
            kotlinx.coroutines.withTimeout(10_000) {
                while (auto.state.value.queued > 0 || auto.state.value.phase == AutoPhase.ANALYZING) kotlinx.coroutines.delay(20)
            }

            assertEquals(2, transport.calls)
            assertEquals(2, auto.state.value.answered)
            assertEquals(AiStatus.ANSWERED, repository.question(first.id)!!.aiStatus)
            assertEquals(AiStatus.ANSWERED, repository.question(second.id)!!.aiStatus)

            val (restarted, restartedAnalyzer) = restart()
            val again =
                AutoAnalysisCoordinator(
                    restartedAnalyzer,
                    restarted,
                    settings,
                    object : AutoSendPolicy {
                        override val realRequestsAllowed = true
                    },
                ).apply {
                    attach(this@runBlocking)
                    startSession()
                }
            // Both test questions share their text (only the number differs): either is "the same question".
            assertTrue(again.findSentToAi(first.ocrText) != null)
            again.submit(restarted.question(first.id)!!, assessQuestion(screen(1)))
            assertEquals(2, transport.calls)
            assertEquals(2, restarted.aiRequestsInLimitWindow())
        }

    /** Privacy, phase 3.5: a photo where no question can be located is never sent, not even on request. */
    @Test
    fun aPhotoWithoutALocatableQuestion_isNeverSent_evenManually() =
        runBlocking {
            val question = storeQuestion(1)
            val file = repository().fileOf(question)
            Bitmap.createBitmap(2_000, 1_500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }.let { bitmap ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
            transport.reply(ok())
            val repository = repository().also { it.reconcile() }

            val result =
                analyzer(repository, JpegQuestionImageEncoder(captures, MlKitTextRecognitionEngine(context)))
                    .analyze(question.id, AnalysisMode.IMAGE)

            assertEquals(AnalysisResult.Rejected(AnalysisRejection.QUESTION_REGION_NOT_FOUND), result)
            assertEquals(0, transport.calls)
            assertEquals(0, repository.aiRequestsInLimitWindow())
            assertEquals(AiStatus.PENDING, repository.question(question.id)!!.aiStatus)
        }

    /** The real encoder sends only the question's crop, and the request log keeps its size (not its content). */
    @Test
    fun imageMode_sendsOnlyTheCrop_andLogsItsSize() =
        runBlocking {
            val question = storeQuestion(1)
            val file = repository().fileOf(question)
            val bitmap = Bitmap.createBitmap(2_000, 1_500, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                val paint =
                    Paint().apply {
                        color = Color.BLACK
                        textSize = 34f
                        isAntiAlias = true
                    }
                drawText("Lembrança De Notificação Extrajudicial", 30f, 200f, paint)
                listOf(
                    "Mensagem anterior da conversa.",
                    "",
                    "",
                    "Questão 07 — Redes de Computadores",
                    "Qual protocolo atribui endereços IP automaticamente aos dispositivos?",
                    "",
                    "A) DNS",
                    "B) DHCP",
                    "C) FTP",
                    "D) SSH",
                    "",
                    "Pergunte ao ChatGPT",
                ).forEachIndexed { i, line -> drawText(line, 700f, 300f + i * 60f, paint) }
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            transport.reply(ok())
            val repository = repository().also { it.reconcile() }

            val result =
                analyzer(repository, JpegQuestionImageEncoder(captures, MlKitTextRecognitionEngine(context)))
                    .analyze(question.id, AnalysisMode.IMAGE)

            assertTrue(result.toString(), result is AnalysisResult.Answered)
            val dataUrl = Regex("\"image_url\":\"(data:image/jpeg;base64,[^\"]+)\"").find(transport.bodies.single())!!.groupValues[1]
            val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            assertTrue("${bounds.outWidth}x${bounds.outHeight}", bounds.outWidth < 2_000 && bounds.outHeight < 1_500)
            val log = repository.aiRequestsFor(question.id).single()
            assertEquals(bounds.outWidth, log.imageWidth)
            assertEquals(bounds.outHeight, log.imageHeight)
            assertEquals(bytes.size, log.imageBytes)
            // The stored photo is only read.
            assertEquals(2_000, BitmapFactory.decodeFile(file.path).width)
        }

    /** A photo missing on disk: the image mode sends nothing, claims nothing and logs nothing. */
    @Test
    fun aMissingPhoto_inImageMode_sendsNothing() =
        runBlocking {
            val question = storeQuestion(1)
            val repository = repository().also { it.reconcile() }
            assertTrue(repository.fileOf(question).delete())

            val result =
                analyzer(
                    repository,
                    JpegQuestionImageEncoder(captures, MlKitTextRecognitionEngine(context)),
                ).analyze(question.id, AnalysisMode.IMAGE)

            assertEquals(AnalysisResult.Rejected(AnalysisRejection.IMAGE_UNAVAILABLE), result)
            assertEquals(0, transport.calls)
            assertEquals(AiStatus.PENDING, repository.question(question.id)!!.aiStatus)
            assertEquals(0, repository.aiRequestsInLimitWindow())
        }

    @Test
    fun aRequestInFlightWhenTheAppDied_isInterrupted_andNotResent() =
        runBlocking {
            val question = storeQuestion(1)
            val repository = repository().also { it.reconcile() }
            val started = repository.beginAiRequest(question.id, "gpt-6-luna", AnalysisMode.TEXT, 10)
            assertTrue(started is AiRequestStart.Started)

            val (restarted, _) = restart()

            val stored = restarted.question(question.id)!!
            assertEquals(AiStatus.FAILED, stored.aiStatus)
            assertEquals(AiError.INTERRUPTED.name, stored.lastError)
            assertEquals(AiRequestOutcome.INTERRUPTED, restarted.aiRequestsFor(question.id).single().outcome)
            assertEquals(question.ocrText, stored.ocrText)
            assertTrue(restarted.fileOf(stored).exists())
            // It may have been billed: it still counts, and nothing was sent again.
            assertEquals(1, restarted.aiRequestsInLimitWindow())
            assertEquals(0, transport.calls)
        }

    @Test
    fun theLogcat_neverGetsTheKey_theQuestion_orTheAnswer() =
        runBlocking {
            val question = storeQuestion(1)
            val failing = storeQuestion(2)
            val analyzer = analyzer(repository().also { it.reconcile() })
            transport.reply(ok(), { HttpResponse(401, errorJson("invalid_request_error", "invalid_api_key"), null) })

            analyzer.analyze(question.id, AnalysisMode.TEXT)
            analyzer.analyze(failing.id, AnalysisMode.TEXT)

            val logs = ownLogcat()
            assertTrue("the request log line is there", logs.contains("QuestVisionAi") && logs.contains("outcome=COMPLETED"))
            for (secret in listOf(FAKE_KEY.value, "Bearer", "Authorization", STATEMENT, "dominio", EXPLANATION, "sigilosa")) {
                assertFalse("logcat contains \"$secret\"", logs.contains(secret))
            }
        }

    /** This process's own log buffer (an app may always read its own lines). */
    private fun ownLogcat(): String {
        val process = ProcessBuilder("logcat", "-d", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
        return process.inputStream
            .bufferedReader()
            .use { it.readText() }
            .also { process.waitFor() }
    }

    private fun responseJson(status: String = "completed"): String {
        val answer =
            """{"question_number":1,"answer_type":"single","answer":["B"],"explanation":"$EXPLANATION",""" +
                """"confidence":"high","needs_review":false}"""
        val text = if (status == "completed") answer else answer.take(20)
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        val incomplete = if (status == "incomplete") ""","incomplete_details":{"reason":"max_output_tokens"}""" else ""
        return """{"id":"resp_device_1","object":"response","status":"$status","service_tier":"default"$incomplete,""" +
            """"output":[{"type":"reasoning","id":"rs_1","summary":[]},{"type":"message","id":"msg_1","role":"assistant",""" +
            """"content":[{"type":"output_text","text":"$escaped","annotations":[]}]}],""" +
            """"usage":{"input_tokens":412,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},""" +
            """"output_tokens":96,"output_tokens_details":{"reasoning_tokens":40},"total_tokens":508}}"""
    }

    private fun errorJson(
        type: String,
        code: String?,
    ): String = """{"error":{"message":"Test error","type":"$type","code":${code?.let { "\"$it\"" } ?: "null"},"param":null}}"""
}
