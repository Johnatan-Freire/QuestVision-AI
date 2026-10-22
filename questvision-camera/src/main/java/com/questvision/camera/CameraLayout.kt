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

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * Places the camera preview and its controls for the available space:
 *
 * - **Side panel** ([useSidePanel]): [controls] in a fixed-width column on the end side and the
 *   preview taking the whole remaining height. Used whenever the space is wider than tall, where
 *   stacking controls above and below would leave the preview only a sliver of height.
 * - **Stacked**: [top] above, [controls] below, the preview in between.
 *
 * The preview is always the first child, whatever the arrangement, so switching layouts (e.g. on
 * rotation) never disposes the viewfinder. A CameraX `SurfaceRequest` can only be fulfilled once, so
 * re-creating the viewfinder for the same request would leave the preview blank.
 */
@Composable
fun CameraLayout(
    useSidePanel: Boolean,
    sidePanelWidth: Dp,
    preview: @Composable () -> Unit,
    top: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            Box { preview() }
            Box { top() }
            Box { controls() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val (previewMeasurable, topMeasurable, controlsMeasurable) = measurables
        val width = constraints.maxWidth
        val height = constraints.maxHeight

        if (useSidePanel) {
            val panelWidth = sidePanelWidth.roundToPx().coerceAtMost(width / 2)
            val panel = controlsMeasurable.measure(Constraints.fixed(panelWidth, height))
            val topPlaceable = topMeasurable.measure(Constraints())
            val previewPlaceable = previewMeasurable.measure(Constraints.fixed(width - panelWidth, height))
            layout(width, height) {
                previewPlaceable.placeRelative(0, 0)
                topPlaceable.placeRelative(0, 0)
                panel.placeRelative(width - panelWidth, 0)
            }
        } else {
            val topPlaceable = topMeasurable.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
            val controlsPlaceable =
                controlsMeasurable.measure(
                    Constraints(minWidth = width, maxWidth = width, maxHeight = height - topPlaceable.height),
                )
            val previewHeight = (height - topPlaceable.height - controlsPlaceable.height).coerceAtLeast(0)
            val previewPlaceable = previewMeasurable.measure(Constraints.fixed(width, previewHeight))
            layout(width, height) {
                previewPlaceable.placeRelative(0, topPlaceable.height)
                topPlaceable.placeRelative(0, 0)
                controlsPlaceable.placeRelative(0, height - controlsPlaceable.height)
            }
        }
    }
}

/**
 * The current display rotation (a [Surface].ROTATION_* constant), updated from a
 * [DisplayManager.DisplayListener]. Unlike a configuration-based lookup, this also catches 180° turns
 * (landscape → reverse landscape), which change the rotation without a configuration change and
 * would otherwise leave preview and captures upside down.
 */
@Composable
fun rememberLiveDisplayRotation(): Int {
    val view = LocalView.current
    var rotation by remember { mutableIntStateOf(view.display?.rotation ?: Surface.ROTATION_0) }
    DisposableEffect(view) {
        val displayManager = view.context.getSystemService(DisplayManager::class.java)
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayChanged(displayId: Int) {
                    val display = view.display ?: return
                    if (display.displayId == displayId) rotation = display.rotation
                }

                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit
            }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        // The rotation may have changed between composition and registration.
        view.display?.let { rotation = it.rotation }
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }
    return rotation
}
