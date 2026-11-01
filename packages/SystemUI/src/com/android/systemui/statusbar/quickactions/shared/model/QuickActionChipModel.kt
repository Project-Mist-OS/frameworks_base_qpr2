/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.quickactions.shared.model

import android.content.Context
import android.graphics.RectF
import com.android.internal.util.mist.MistBarSettings
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.media.controls.shared.MediaSeekBarStyle
import com.android.systemui.statusbar.chips.ui.model.Chronometer
import com.android.systemui.statusbar.quickactions.alarm.shared.model.AlarmPopupModel
import com.android.systemui.statusbar.quickactions.flashlight.shared.model.FlashlightPopupModel
import com.android.systemui.statusbar.quickactions.livescore.shared.model.LiveScoreChipModel
import com.android.systemui.statusbar.quickactions.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.popups.ui.model.SystemEventKind
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.StatusBarPopupViewModel
import com.android.systemui.statusbar.quickactions.screenrecord.shared.model.ScreenRecordPopupModel
import com.android.systemui.statusbar.quickactions.stopwatch.shared.model.StopwatchPopupModel
import com.android.systemui.statusbar.quickactions.ui.compose.ChipColors
import com.android.systemui.util.time.SystemClock

/**
 * Ids used to track different types of popup chips. Will be used to ensure only one chip is
 * displaying its popup at a time.
 */
sealed class QuickActionChipId(val value: String) {
    /** A user configured Mist Bar utility card (shortcuts, widget, clock, ...). */
    data class Utility(val cardId: String) : QuickActionChipId("Utility:$cardId")

    /** A Mist Bar system event (call, charging, bluetooth, ...). */
    data class SystemEvent(val eventId: String) : QuickActionChipId("SystemEvent:$eventId")

    data object MediaControl : QuickActionChipId("MediaControl")

    data object ScreenRecord : QuickActionChipId("ScreenRecord")

    data object LiveScore : QuickActionChipId("LiveScore")

    data object Flashlight : QuickActionChipId("Flashlight")

    data object Stopwatch : QuickActionChipId("Stopwatch")

    data object Alarm : QuickActionChipId("Alarm")

    data object AvControlsIndicator : QuickActionChipId("AvControlsIndicator")

    data object ShareScreenPrivacyIndicator : QuickActionChipId("ShareScreenPrivacyIndicator")

    data object AssistantIcon : QuickActionChipId("AssistantIcon")

    data object ImeIndicator : QuickActionChipId("ImeIndicator")

    data object ScreenRecording : QuickActionChipId("ScreenRecording")
}

/**
 * Model for an optionally clickable icon that is displayed on the chip.
 *
 * @param isHighlighted shows a highlighted background around this icon
 * @param tint false when the icon is artwork (album art, app icon) that must not be tinted
 */
data class ChipIcon(
    val icon: Icon,
    val onClick: (() -> Unit)? = null,
    val isHighlighted: Boolean = false,
    val tint: Boolean = true,
)

/** Content displayed to the right of the popup chip icons */
sealed class ChipContent {
    data class Text(val text: String) : ChipContent()

    data class IconOnly(val icon: Icon) : ChipContent()

    data class Timer(val chronometer: Chronometer, val timeSource: SystemClock) : ChipContent()
}

enum class BluetoothBatteryComponent {
    LeftEarbud,
    RightEarbud,
    Case,
}

data class BluetoothBatteryModel(
    val label: String,
    val level: Int,
    val component: BluetoothBatteryComponent,
    val isCharging: Boolean = false,
)

data class ChargingDetailModel(val label: String, val value: String, val unit: String)

/** Rich popup contents associated with a status bar chip. */
sealed interface PopupContentModel {
    data object None : PopupContentModel

    /** A Mist Bar utility card configured by the user. */
    data class Utility(val card: MistBarSettings.Card) : PopupContentModel

    /** A Mist Bar system event (call, timer, charging, bluetooth, ...). */
    data class SystemEvent(
        val kind: SystemEventKind,
        val title: String,
        val text: String,
        val actions: List<PopupActionModel>,
        val progress: Float? = null,
        val callStartTimeMs: Long? = null,
        val timerEndTimeMs: Long? = null,
        val timerEndElapsedRealtimeMs: Long? = null,
        val timerRemainingMs: Long? = null,
        val timerOriginalDurationMs: Long? = null,
        val timerPaused: Boolean = false,
        val indeterminate: Boolean = false,
        val icon: Icon? = null,
        val appName: String? = null,
        val prominentText: String? = null,
        val pulse: Boolean = false,
        val image: Icon? = null,
        val bluetoothBatteries: List<BluetoothBatteryModel> = emptyList(),
        val chargingDetails: List<ChargingDetailModel> = emptyList(),
    ) : PopupContentModel

    data class Media(
        val model: MediaControlChipModel,
        val seekBarStyle: MediaSeekBarStyle = MediaSeekBarStyle.DEFAULT,
        val useRedesignedMedia: Boolean = true,
    ) : PopupContentModel

    data class ScreenRecord(val model: ScreenRecordPopupModel) : PopupContentModel

    data class LiveScore(val model: LiveScoreChipModel) : PopupContentModel

    data class Flashlight(
        val model: FlashlightPopupModel,
        val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    ) : PopupContentModel

    data class Stopwatch(val model: StopwatchPopupModel) : PopupContentModel

    data class Alarm(val model: AlarmPopupModel) : PopupContentModel
}

/** Model for individual status bar quick action chips. */
sealed class QuickActionChipModel {
    abstract val logName: String
    abstract val chipId: QuickActionChipId

    data class Hidden(override val chipId: QuickActionChipId, val shouldAnimate: Boolean = true) :
        QuickActionChipModel() {
        override val logName = "Hidden(id=$chipId, anim=$shouldAnimate)"
    }

    data class LaunchChip(
        override val chipId: QuickActionChipId,
        val chipContent: ChipContent,
        val onClick: (Context?) -> Unit = {},
        val isSelected: Boolean = false,
        val contentDescription: ContentDescription? = null,
    ) : QuickActionChipModel() {
        override val logName = "Launch(id=$chipId, selected=$isSelected)"
    }

    data class PopupChip(
        override val chipId: QuickActionChipId,
        /** Icons shown on the chip when no specific hover behavior. */
        val icons: List<ChipIcon>,
        val chipContent: ChipContent? = null,
        /** Determines the colors used for the chip. Defaults to system themed colors. */
        val colors: ChipColors = ChipColors.SystemTheme,
        val isPopupShown: Boolean = false,
        val togglePopup: (Context, RectF) -> Unit = { _, _ -> },
        val showPopup: () -> Unit = {},
        val hidePopup: () -> Unit = {},
        val contentDescription: ContentDescription? = null,
        val popupViewModelFactory: StatusBarPopupViewModel.Factory? = null,
        val popupContent: PopupContentModel = PopupContentModel.None,
        /** Wall-clock start of an active call; null for ringing calls and other chips. */
        val callStartTimeMs: Long? = null,
        /** A new event revision requests one automatic expansion once the device is unlocked. */
        val autoPopupRequest: Long? = null,
        val autoPopupDurationMs: Long = 0L,
        val onAutoPopupShown: () -> Unit = {},
        val onPopupShown: () -> Unit = {},
        val onPopupHidden: () -> Unit = {},
        val sourcePackageName: String? = null,
        val sourceUserId: Int? = null,
        val dismissNotification: (() -> Unit)? = null,
    ) : QuickActionChipModel() {
        override val logName = "Shown(id=$chipId, toggled=$isPopupShown)"

        /** Convenience accessor for the plain text shown next to the icons, if any. */
        val chipText: String?
            get() = (chipContent as? ChipContent.Text)?.text
    }
}
