/*
 * Copyright (C) 2026 MistOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.volume.dialog.oneplus.ui.compose

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class StreamInfo(
    val label: String,
    val icon: ImageVector,
)

private fun streamInfo(streamType: Int): StreamInfo = when (streamType) {
    AudioManager.STREAM_MUSIC -> StreamInfo("Media", Icons.Filled.MusicNote)
    AudioManager.STREAM_RING -> StreamInfo("Ring", Icons.Filled.VolumeUp)
    AudioManager.STREAM_NOTIFICATION -> StreamInfo("Notif", Icons.Filled.NotificationsNone)
    AudioManager.STREAM_ALARM -> StreamInfo("Alarm", Icons.Filled.VolumeUp)
    else -> StreamInfo("Vol", Icons.Filled.VolumeUp)
}

enum class OnePlusPillStyling {
    OnWallpaper,
    OnDarkScrim,
}

private data class OnePlusPillStyleColors(
    val track: Color,
    val fill: Color,
    val iconTint: Color,
    val label: Color,
)

@Composable
private fun onePlusPillStyleColors(
    styling: OnePlusPillStyling,
): OnePlusPillStyleColors {
    if (styling == OnePlusPillStyling.OnDarkScrim) {
        return OnePlusPillStyleColors(
            track = Color.White.copy(alpha = 0.15f),
            fill = Color.White,
            iconTint = Color.White,
            label = Color.White,
        )
    }
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        // Frosted light pill on a typically dark / busy background.
        OnePlusPillStyleColors(
            track = Color.White.copy(alpha = 0.2f),
            fill = Color.White,
            iconTint = Color(0.1f, 0.1f, 0.1f, 1f),
            label = MaterialTheme.colorScheme.onBackground,
        )
    } else {
        // Solid dark-surface pill: visible on bright wallpapers in light mode.
        val onSurface = MaterialTheme.colorScheme.onSurface
        OnePlusPillStyleColors(
            track = Color.Black.copy(alpha = 0.32f),
            fill = onSurface,
            iconTint = Color.White,
            label = onSurface,
        )
    }
}

@Composable
fun OnePlusPillSlider(
    streamType: Int,
    sliderWidth: Dp = 64.dp,
    sliderHeight: Dp = 200.dp,
    modifier: Modifier = Modifier,
    styling: OnePlusPillStyling = OnePlusPillStyling.OnWallpaper,
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(AudioManager::class.java)!! }
    val info = remember(streamType) { streamInfo(streamType) }

    val maxVolume = remember(streamType) { audioManager.getStreamMaxVolume(streamType).toFloat() }
    var fraction by remember {
        mutableFloatStateOf(
            audioManager.getStreamVolume(streamType).toFloat() / maxVolume
        )
    }

    // Poll volume changes every 300ms (lightweight)
    LaunchedEffect(streamType) {
        while (isActive) {
            val current = audioManager.getStreamVolume(streamType).toFloat() / maxVolume
            if (current != fraction) fraction = current
            delay(300L)
        }
    }

    val sliderShape = RoundedCornerShape(24.dp)
    val colors = onePlusPillStyleColors(styling)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .width(sliderWidth)
                .height(sliderHeight)
                .clip(sliderShape)
                .background(colors.track)
                .pointerInput(streamType, maxVolume) {
                    detectVerticalDragGestures(
                        onDragStart = {},
                        onDragEnd = {},
                        onDragCancel = {},
                    ) { _, dragAmount ->
                        val delta = -dragAmount / size.height.toFloat()
                        fraction = (fraction + delta).coerceIn(0f, 1f)
                        val newVol = (fraction * maxVolume).toInt()
                            .coerceIn(0, maxVolume.toInt())
                        audioManager.setStreamVolume(
                            streamType,
                            newVol,
                            AudioManager.FLAG_SHOW_UI,
                        )
                    }
                },
        ) {
            // Fill bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sliderHeight * fraction)
                    .background(colors.fill),
            )
            // Icon
            Icon(
                imageVector = info.icon,
                contentDescription = info.label,
                tint = colors.iconTint,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .size(24.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = info.label,
            color = colors.label,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
