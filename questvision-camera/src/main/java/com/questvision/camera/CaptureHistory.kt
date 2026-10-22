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
package com.questvision.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.android.camera.coretheme.monoFontFamily
import com.android.camera.coreui.controls.ScrimIconButton
import com.questvision.camera.detection.MonitoringStats
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Every stored capture, newest first, as a grid of thumbnails labeled with time, origin (automatic
 * with its session number, manual, or from before labeling) and whether it is preserved. Tapping a
 * capture opens it full size. Shown over the viewfinder, so monitoring keeps running underneath.
 */
@Composable
fun CaptureHistory(
    entries: List<CaptureEntry>,
    session: String?,
    stats: MonitoringStats,
    onBack: () -> Unit,
    onOpen: (File) -> Unit,
    loadThumbnail: suspend (File) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScrimIconButton(
                onClick = onBack,
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.questvision_history_back),
                size = 34.dp,
                iconSize = 18.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.questvision_history_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = pluralStringResource(R.plurals.questvision_history_count, entries.size, entries.size),
                style = TextStyle(fontFamily = monoFontFamily, fontSize = 11.sp),
                color = Color.White.copy(alpha = 0.6f),
            )
        }

        if (session != null) {
            HistoryNote(
                text =
                    stringResource(
                        R.string.questvision_history_session_stats,
                        session,
                        stats.changesDetected,
                        stats.capturesRequested,
                        stats.capturesSaved,
                        stats.duplicatesIgnored + stats.textDuplicatesIgnored,
                        stats.unreadableIgnored,
                        stats.capturesFailed + stats.recognitionFailures,
                    ),
            )
        }
        HistoryNote(text = stringResource(R.string.questvision_history_retention, MAX_RECENT_CAPTURES))

        if (entries.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.questvision_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = entries, key = { it.file.path }) { entry ->
                    HistoryTile(
                        entry = entry,
                        dateText = dateFormat.format(Date(entry.capturedAtMs)),
                        onClick = { onOpen(entry.file) },
                        loadThumbnail = loadThumbnail,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = TextStyle(fontFamily = monoFontFamily, fontSize = 10.sp, letterSpacing = 0.04.em),
        color = Color.White.copy(alpha = 0.55f),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun HistoryTile(
    entry: CaptureEntry,
    dateText: String,
    onClick: () -> Unit,
    loadThumbnail: suspend (File) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, entry.file) {
        value = loadThumbnail(entry.file)?.asImageBitmap()
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier =
            modifier
                .clip(shape)
                .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
        ) {
            thumbnail?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            OriginBadge(
                entry = entry,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
            )
            if (entry.isPreserved) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = stringResource(R.string.questvision_history_preserved),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(18.dp),
                )
            }
        }
        Text(
            text = dateText,
            style = TextStyle(fontFamily = monoFontFamily, fontSize = 10.sp),
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun OriginBadge(
    entry: CaptureEntry,
    modifier: Modifier = Modifier,
) {
    val label =
        when (entry.origin) {
            CaptureOrigin.AUTOMATIC -> {
                entry.sequence?.let { stringResource(R.string.questvision_history_auto_numbered, it) }
                    ?: stringResource(R.string.questvision_history_auto)
            }

            CaptureOrigin.MANUAL -> {
                stringResource(R.string.questvision_history_manual)
            }

            CaptureOrigin.UNKNOWN -> {
                stringResource(R.string.questvision_history_unlabeled)
            }
        }
    val accent = entry.origin == CaptureOrigin.AUTOMATIC
    Text(
        text = label.uppercase(),
        style = TextStyle(fontFamily = monoFontFamily, fontSize = 9.sp, letterSpacing = 0.08.em),
        color = if (accent) MaterialTheme.colorScheme.onPrimary else Color.White,
        modifier =
            modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(if (accent) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
