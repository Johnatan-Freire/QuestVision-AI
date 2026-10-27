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

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The app's SQLite database, in its private `databases/` folder (excluded from backups since phase
 * 3.1). Schema changes must come with a [androidx.room.migration.Migration] or auto-migration: there
 * is deliberately no destructive fallback, so an update never wipes the history.
 */
@Database(
    entities = [CaptureSessionEntity::class, QuestionEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class QuestVisionDatabase : RoomDatabase() {
    abstract fun questionDao(): QuestionDao

    companion object {
        const val NAME = "questvision.db"
    }
}

/** Runs several DAO calls atomically; a seam so repository logic can be tested without SQLite. */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): QuestVisionDatabase =
        Room
            .databaseBuilder(context, QuestVisionDatabase::class.java, QuestVisionDatabase.NAME)
            .build()

    @Provides
    fun provideQuestionDao(database: QuestVisionDatabase): QuestionDao = database.questionDao()

    @Provides
    fun provideTransactionRunner(database: QuestVisionDatabase): TransactionRunner =
        object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction { block() }
        }
}
