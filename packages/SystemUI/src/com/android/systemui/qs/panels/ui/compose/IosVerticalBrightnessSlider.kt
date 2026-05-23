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

package com.android.systemui.qs.panels.ui.compose

import android.content.ContentResolver
import android.content.Context
import com.android.settingslib.display.BrightnessUtils
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private fun brightnessToFraction(brightness: Float, min: Float = 1f, max: Float = 255f): Float {
    val gamma = BrightnessUtils.convertLinearToGammaFloat(brightness, min, max)
    return (gamma.toFloat() / BrightnessUtils.GAMMA_SPACE_MAX).coerceIn(0f, 1f)
}

private fun fractionToBrightness(fraction: Float, min: Float = 1f, max: Float = 255f): Float {
    val gamma = (fraction.coerceIn(0f, 1f) * BrightnessUtils.GAMMA_SPACE_MAX).toInt()
    return BrightnessUtils.convertGammaToLinearFloat(gamma, min, max)
}

@Composable
fun IosVerticalBrightnessSlider(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val cr: ContentResolver = context.contentResolver

    @Suppress("UNUSED_VARIABLE")
    val pm = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }

    val brightnessMin = 1f
    val brightnessMax = 255f

    fun readBrightness(): Float = try {
        Settings.System.getIntForUser(
            cr, Settings.System.SCREEN_BRIGHTNESS, 128, UserHandle.USER_CURRENT
        ).toFloat().coerceIn(brightnessMin, brightnessMax)
    } catch (_: Exception) { 128f }

    fun readAutoMode(): Boolean = try {
        Settings.System.getIntForUser(
            cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0, UserHandle.USER_CURRENT
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    } catch (_: Exception) { false }

    var brightness by remember { mutableFloatStateOf(readBrightness()) }
    var autoMode   by remember { mutableStateOf(readAutoMode()) }
    var isDragging by remember { mutableStateOf(false) }
    var showExpandedPopup by remember { mutableStateOf(false) }

    val targetFraction = brightnessToFraction(brightness, brightnessMin, brightnessMax)

    val animFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(if (isDragging) 0 else 150),
        label = "BrightnessFraction"
    )
    val currentFraction = if (isDragging) targetFraction else animFraction

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (!isDragging) {
                    brightness = readBrightness()
                    autoMode   = readAutoMode()
                }
            }
        }
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false, observer, UserHandle.USER_ALL
        )
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            false, observer, UserHandle.USER_ALL
        )
        onDispose { cr.unregisterContentObserver(observer) }
    }

    val trackBgColor = Color.White.copy(alpha = 0.18f)
    val fillColor by animateColorAsState(
        if (autoMode) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
        label = "BrightnessFill"
    )
    val iconTint by animateColorAsState(
        if (autoMode) MaterialTheme.colorScheme.onPrimary else Color(0xFF2C2C2E),
        label = "BrightnessIconTint"
    )
    val iconRes = if (autoMode) R.drawable.ic_qs_brightness_auto_on
                  else          R.drawable.ic_qs_brightness_auto_off

    fun yToBrightness(y: Float, heightPx: Int): Float {
        val fraction = 1f - (y / heightPx).coerceIn(0f, 1f)
        return fractionToBrightness(fraction, brightnessMin, brightnessMax)
    }

    fun writeBrightness(value: Float) {
        scope.launch(Dispatchers.IO) {
            try {
                Settings.System.putIntForUser(
                    cr, Settings.System.SCREEN_BRIGHTNESS,
                    value.toInt(), UserHandle.USER_CURRENT
                )
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(28.dp))
            .background(trackBgColor)
            .pointerInput(Unit) {
                var longPressJob: Job? = null

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    view.parent?.requestDisallowInterceptTouchEvent(true)

                    var dragging = false
                    var longPressed = false

                    longPressJob = scope.launch {
                        delay(400)
                        if (!dragging) {
                            longPressed = true
                            showExpandedPopup = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                    }

                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val currentPointer = event.changes.firstOrNull { it.id == down.id }
                                ?: break

                            if (!currentPointer.pressed) {
                                longPressJob?.cancel()
                                if (!dragging && !longPressed) {
                                    val tapBrightness = yToBrightness(currentPointer.position.y, size.height)
                                    brightness = tapBrightness
                                    writeBrightness(tapBrightness)
                                }
                                break
                            }

                            val dragAmount = currentPointer.position.y - down.position.y

                            if (!dragging && !longPressed && abs(dragAmount) > viewConfiguration.touchSlop) {
                                dragging = true
                                isDragging = true
                                longPressJob?.cancel()
                            }

                            if (dragging) {
                                currentPointer.consume()
                                val v = yToBrightness(currentPointer.position.y, size.height)
                                brightness = v
                                writeBrightness(v)
                            }
                        }
                    } finally {
                        longPressJob?.cancel()
                        isDragging = false
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(currentFraction)
                .align(Alignment.BottomCenter)
                .background(fillColor, RoundedCornerShape(28.dp))
        )

        Icon(
            painter = painterResource(iconRes),
            contentDescription = "Brightness",
            tint = iconTint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(22.dp)
        )
    }

    if (showExpandedPopup) {
        IosBrightnessExpandedPopup(
            initialBrightness = brightness,
            brightnessMin = brightnessMin,
            brightnessMax = brightnessMax,
            onDismiss = { showExpandedPopup = false },
            onBrightnessChanged = { 
                brightness = it
                writeBrightness(it)
            }
        )
    }
}
