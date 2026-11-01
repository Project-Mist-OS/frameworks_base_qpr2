/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.statusbar.quickactions.ui.compose

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.StopReason
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.alarm.shared.model.AlarmPopupModel
import com.android.systemui.statusbar.quickactions.flashlight.shared.model.FlashlightPopupModel
import com.android.systemui.statusbar.quickactions.livescore.shared.model.LiveScoreChipModel
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.screenrecord.shared.model.ScreenRecordPopupModel
import com.android.systemui.statusbar.quickactions.stopwatch.shared.model.StopwatchPopupModel

@Composable
internal fun MistBarAlarmPopup(model: AlarmPopupModel) {
    MistBarCard {
        MistBarCardHeader(model.title, model.dayText, glyph = Icons.Default.Alarm)
        MistBarCardValue(model.fullTimeText)
        PopupActionChips(
            buildList {
                addAll(model.actions)
                model.onOpen?.let {
                    add(
                        PopupActionModel(
                            stringResource(R.string.dynamic_island_open_clock_action),
                            it,
                        )
                    )
                }
            },
            LocalMistBarColors.current.accent,
        )
    }
}

@Composable
internal fun MistBarStopwatchPopup(model: StopwatchPopupModel) {
    MistBarCard {
        MistBarCardHeader(model.title, model.subtitle, model.icon, Icons.Default.Timer)
        MistBarCardValue(rememberStopwatchText(model))
        PopupActionChips(
            buildList {
                addAll(model.actions)
                model.onOpen?.let {
                    add(
                        PopupActionModel(
                            stringResource(R.string.dynamic_island_open_clock_action),
                            it,
                        )
                    )
                }
            },
            LocalMistBarColors.current.accent,
        )
    }
}

@Composable
internal fun MistBarScreenRecordPopup(model: ScreenRecordPopupModel) {
    MistBarCard {
        MistBarCardHeader(
            stringResource(R.string.screenrecord_ongoing_screen_only),
            when (model) {
                is ScreenRecordPopupModel.Starting ->
                    stringResource(
                        R.string.dynamic_island_screen_record_countdown,
                        model.secondsUntilStarted,
                    )
                is ScreenRecordPopupModel.Recording ->
                    stringResource(R.string.dynamic_island_screen_record_active)
            },
            glyph = Icons.Default.Videocam,
        )
        when (model) {
            is ScreenRecordPopupModel.Starting ->
                MistBarCardValue(model.secondsUntilStarted.toString())
            is ScreenRecordPopupModel.Recording -> {
                MistBarCardValue(rememberElapsedDurationText(model.startElapsedRealtimeMs))
                PopupActionChips(
                    listOf(
                        PopupActionModel(
                            stringResource(R.string.screenrecord_stop_label),
                            model.stopRecording,
                            emphasized = true,
                        )
                    ),
                    LocalMistBarColors.current.accent,
                )
            }
        }
    }
}

@Composable
internal fun MistBarLiveScorePopup(model: LiveScoreChipModel) {
    MistBarCard {
        MistBarCardHeader(
            model.title ?: model.appName,
            model.subtitle,
            model.icon,
            Icons.Default.SportsScore,
            model.appName,
        )
        MistBarCardValue(model.score)
        model.onOpen?.let {
            PopupActionChips(
                listOf(PopupActionModel(stringResource(R.string.dynamic_island_event_open), it)),
                LocalMistBarColors.current.accent,
            )
        }
    }
}

@Composable
internal fun MistBarFlashlightPopup(model: FlashlightPopupModel) {
    val settings = LocalMistBarState.current.settings
    val colors = LocalMistBarColors.current
    val view = LocalView.current
    MistBarCard {
        MistBarCardHeader(
            stringResource(R.string.quick_settings_flashlight_label),
            stringResource(R.string.dynamic_island_flashlight_enabled),
            glyph = Icons.Default.FlashlightOn,
        )
        val level = model.level
        val max = model.maxLevel
        if (level != null && max != null && max > 1) {
            var dragging by remember(max) { mutableStateOf(false) }
            var value by remember(max) { mutableIntStateOf(level.coerceIn(1, max)) }
            val latest by rememberUpdatedState(model)
            LaunchedEffect(level, dragging) { if (!dragging) value = level.coerceIn(1, max) }
            DisposableEffect(max) { onDispose { if (dragging) latest.setLevel(value) } }
            MistBarCardValue("${value * 100 / max}%")
            Slider(
                value = value.toFloat(),
                valueRange = 1f..max.toFloat(),
                steps = (max - 2).coerceAtLeast(0),
                onValueChange = {
                    dragging = true
                    val next = it.toInt().coerceIn(1, max)
                    if (next != value && settings.isHapticsEnabled)
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    value = next
                    model.setLevelTemporary(value)
                },
                onValueChangeFinished = {
                    model.setLevel(value)
                    dragging = false
                },
                colors =
                    SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor =
                            rememberMistBarPalette(
                                    settings.integer("progress_palette", 5, 0, 20),
                                    mistBarPartColors(
                                        "progress",
                                        colors.accent to colors.content,
                                    ),
                                )
                                .first,
                        inactiveTrackColor =
                            rememberMistBarPalette(
                                    settings.integer("track_palette", 2, 0, 20),
                                    mistBarPartColors("track", colors.surface to colors.content),
                                )
                                .first,
                    ),
            )
        }
        PopupActionChips(
            listOf(
                PopupActionModel(
                    stringResource(R.string.flashlight_dialog_turn_off),
                    model.turnOff,
                    emphasized = true,
                )
            ),
            colors.accent,
        )
    }
}

@Composable
internal fun MistBarPrivacyPopup(model: QuickActionChipModel.PopupChip) {
    val context = LocalContext.current
    val sharing = model.chipId == QuickActionChipId.ShareScreenPrivacyIndicator
    MistBarCard {
        MistBarCardHeader(
            model.contentDescription.loadContentDescription(context)
                ?: stringResource(
                    if (sharing) R.string.mist_bar_screen_sharing
                    else R.string.mist_bar_privacy
                ),
            icon = model.icons.firstOrNull()?.icon,
            glyph = if (sharing) Icons.Default.ScreenShare else Icons.Default.PrivacyTip,
        )
        PopupActionChips(
            listOf(
                PopupActionModel(
                    stringResource(
                        if (sharing) R.string.screen_share_privacy_indicator_stop_sharing
                        else R.string.mist_bar_privacy_settings
                    ),
                    {
                        if (sharing) {
                            context
                                .getSystemService(MediaProjectionManager::class.java)
                                ?.stopActiveProjection(StopReason.STOP_PRIVACY_CHIP)
                        } else mistBarLaunch(context, Intent(Settings.ACTION_PRIVACY_SETTINGS))
                        model.hidePopup()
                    },
                    emphasized = true,
                )
            ),
            LocalMistBarColors.current.accent,
        )
    }
}
