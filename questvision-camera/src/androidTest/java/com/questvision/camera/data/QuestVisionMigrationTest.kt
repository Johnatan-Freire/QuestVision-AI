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
package com.questvision.camera.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrading a phase 3.2 database (version 1, built from the exported schema) to version 2 keeps every
 * session and question as it was, and the result matches the current schema.
 */
@RunWith(AndroidJUnit4::class)
class QuestVisionMigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            QuestVisionDatabase::class.java,
        )

    @Test
    fun version1To2_keepsAllData() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO capture_sessions (id, started_at, ended_at) VALUES ('20261026-143646', 100, 200)")
            execSQL(
                "INSERT INTO questions (id, session_id, sequence, question_number, origin, image_path, captured_at, ocr_text, " +
                    "dedup_key, ai_status, ai_response, ai_model, ai_answered_at, attempts, last_error, telegram_status, updated_at) " +
                    "VALUES ('q1', '20261026-143646', 1, 2, 'AUTOMATIC', 'capture_1.jpg', 150, 'Questão 02\nQual…', " +
                    "'04d05c07b55605b6', 'PENDING', NULL, NULL, NULL, 0, NULL, 'NOT_REQUESTED', 150)",
            )
            execSQL(
                "INSERT INTO questions (id, session_id, sequence, question_number, origin, image_path, captured_at, ocr_text, " +
                    "dedup_key, ai_status, ai_response, ai_model, ai_answered_at, attempts, last_error, telegram_status, updated_at) " +
                    "VALUES ('q2', NULL, NULL, NULL, 'MANUAL', 'kept/capture_2.jpg', 160, NULL, NULL, 'NOT_REQUESTED', " +
                    "NULL, NULL, NULL, 0, NULL, 'NOT_REQUESTED', 160)",
            )
            close()
        }

        // Validates the migrated tables against the exported version 2 schema.
        helper.runMigrationsAndValidate(dbName, 2, true).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, QuestVisionDatabase::class.java, dbName).build()
        try {
            runBlocking {
                val dao = database.questionDao()
                val q1 = dao.question("q1")!!
                assertEquals(AiStatus.PENDING, q1.aiStatus)
                assertEquals("Questão 02\nQual…", q1.ocrText)
                assertEquals("04d05c07b55605b6", q1.dedupKey)
                assertEquals(2, q1.questionNumber)
                assertEquals("kept/capture_2.jpg", dao.question("q2")!!.imagePath)
                assertNull(dao.question("q2")!!.sessionId)
                assertEquals(200L, dao.session("20261026-143646")!!.endedAt)
                assertTrue(database.aiRequestDao().all().isEmpty())
            }
        } finally {
            database.close()
            context.deleteDatabase(dbName)
        }
    }

    /** Phase 3.5: version 2 to 3 adds nullable columns to `ai_requests`; every row stays as it was. */
    @Test
    fun version2To3_keepsAllData_andTheRequestLog() {
        helper.createDatabase(dbName, 2).apply {
            execSQL("INSERT INTO capture_sessions (id, started_at, ended_at) VALUES ('20261029-025608', 100, 200)")
            execSQL(
                "INSERT INTO questions (id, session_id, sequence, question_number, origin, image_path, captured_at, ocr_text, " +
                    "dedup_key, ai_status, ai_response, ai_model, ai_answered_at, attempts, last_error, telegram_status, updated_at) " +
                    "VALUES ('q7', '20261029-025608', 7, 12, 'AUTOMATIC', 'capture_7.jpg', 150, 'Questão 12', " +
                    "NULL, 'ANSWERED', '{\"answer\":[\"D\"]}', 'gpt-6-luna', 160, 1, NULL, 'NOT_REQUESTED', 160)",
            )
            execSQL(
                "INSERT INTO ai_requests (id, question_id, model, mode, started_at, finished_at, outcome, error_code, http_status, " +
                    "response_id, input_tokens, cached_tokens, output_tokens, reasoning_tokens, cache_write_tokens, service_tier) " +
                    "VALUES (2, 'q7', 'gpt-6-luna', 'IMAGE', 1793254530992, 1793254534440, 'SUCCEEDED', NULL, 200, " +
                    "'resp_00bb', 1486, 0, 56, 0, 1483, 'default')",
            )
            close()
        }

        helper.runMigrationsAndValidate(dbName, 3, true).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, QuestVisionDatabase::class.java, dbName).build()
        try {
            runBlocking {
                val q7 = database.questionDao().question("q7")!!
                assertEquals(AiStatus.ANSWERED, q7.aiStatus)
                assertEquals("{\"answer\":[\"D\"]}", q7.aiResponse)
                val log = database.aiRequestDao().all().single()
                assertEquals(1486, log.inputTokens)
                assertEquals(1483, log.cacheWriteTokens)
                assertEquals("default", log.serviceTier)
                assertEquals(AiRequestOutcome.SUCCEEDED, log.outcome)
                assertNull(log.cacheWrite12hTokens)
                assertNull(log.imageWidth)
                assertNull(log.imageBytes)
            }
        } finally {
            database.close()
            context.deleteDatabase(dbName)
        }
    }
}
