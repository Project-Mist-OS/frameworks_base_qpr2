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

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.drawable.ColorDrawable
import android.hardware.display.ColorDisplayManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.CrossWindowBlurListeners
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.qs.panels.shared.model.AxQsVerticalSliderStyle
import com.android.systemui.res.R

private val PopupSliderLength = 360.dp
private val PopupSliderLengthLandscape = 260.dp
private val PopupSliderThickness = 132.dp
private val PopupSliderThicknessLandscape = 108.dp
private val ToggleSize = 64.dp
private val ToggleWidth = 96.dp
private val ToggleInactiveCorner = 22.dp
private const val SCRIM_ALPHA_DARK = 0.40f
private const val SCRIM_ALPHA_LIGHT = 0.35f
private const val SCRIM_ALPHA_NO_BLUR = 0.92f
private const val TOGGLE_INACTIVE_ALPHA = 0.22f

@Composable
private fun axPopupString(resName: String, fallback: String): String {
    val context = LocalContext.current
    val id = remember(resName) { context.resources.getIdentifier(resName, "string", context.packageName) }
    return if (id != 0) stringResource(id) else fallback
}

/**
 * The window blur radius in px, driven by the same "QS Blur Radius" setting as the rest of the
 * system blur (see [BlurConfig]), so Mistify's blur slider also controls this panel.
 */
@Composable
private fun rememberQsBlurRadiusPx(): Int {
    val context = LocalContext.current
    fun read(): Int {
        val percent =
            Settings.Secure.getFloatForUser(
                context.contentResolver,
                BlurConfig.KEY_BLUR_RADIUS_PCT,
                100f,
                UserHandle.USER_CURRENT,
            )
        if (!percent.isFinite()) return BlurConfig.MAX_BLUR_RADIUS_PX.toInt()
        return (BlurConfig.MAX_BLUR_RADIUS_PX * percent.coerceIn(0f, 100f) / 100f).toInt()
    }
    var radius by remember { mutableIntStateOf(read()) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    radius = read()
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(BlurConfig.KEY_BLUR_RADIUS_PCT),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return radius
}

/**
 * Extended brightness panel, opened by long pressing the QS brightness slider. It shows a large
 * version of the same slider (following the selected Material 3 Expressive or Platform style) and
 * the auto brightness, dark theme and night light toggles. The layout adapts to landscape.
 */
@Composable
internal fun AxExtendedBrightnessPopup(
    viewModel: BrightnessSliderViewModel,
    verticalStyle: AxQsVerticalSliderStyle,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val blurRadiusPx = rememberQsBlurRadiusPx()
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        SideEffect {
            dialogWindowProvider?.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val params = window.attributes
                params.blurBehindRadius = blurRadiusPx
                window.attributes = params
            }
        }

        val darkTheme = isSystemInDarkTheme()
        // The whole screen behind is blurred and tinted for the current theme, with no card. When
        // cross-window blur is disabled, fall back to a nearly opaque tint so content stays legible.
        val blurEnabled = remember { CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled() }
        val scrimColor =
            when {
                !blurEnabled -> MaterialTheme.colorScheme.surface.copy(alpha = SCRIM_ALPHA_NO_BLUR)
                darkTheme -> Color.Black.copy(alpha = SCRIM_ALPHA_DARK)
                else -> Color.White.copy(alpha = SCRIM_ALPHA_LIGHT)
            }

        BoxWithConstraints(
            modifier =
                Modifier.fillMaxSize()
                    .background(scrimColor)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
                    .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            // Read the constraints here: inside the nested Box the implicit receiver is not accessible.
            val availableHeight = maxHeight
            val landscape = maxWidth > availableHeight
            Box(
                modifier =
                    // Taps on the controls' gaps must not dismiss the panel.
                    Modifier.pointerInput(Unit) { detectTapGestures(onTap = {}) }
                        .padding(if (landscape) 24.dp else 16.dp)
            ) {
                if (landscape) {
                    LandscapeContent(
                        viewModel = viewModel,
                        verticalStyle = verticalStyle,
                        sliderLength =
                            minOf(PopupSliderLengthLandscape, availableHeight - 96.dp)
                                .coerceAtLeast(120.dp),
                    )
                } else {
                    PortraitContent(
                        viewModel = viewModel,
                        verticalStyle = verticalStyle,
                        sliderLength = minOf(PopupSliderLength, availableHeight * 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PortraitContent(
    viewModel: BrightnessSliderViewModel,
    verticalStyle: AxQsVerticalSliderStyle,
    sliderLength: Dp,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Header(viewModel)
        Spacer(Modifier.height(24.dp))
        AxQsExtendedBrightnessSlider(
            verticalStyle = verticalStyle,
            viewModel = viewModel,
            modifier = Modifier.width(PopupSliderThickness).height(sliderLength),
        )
        Spacer(Modifier.height(28.dp))
        Toggles(viewModel, verticalStyle)
    }
}

@Composable
private fun LandscapeContent(
    viewModel: BrightnessSliderViewModel,
    verticalStyle: AxQsVerticalSliderStyle,
    sliderLength: Dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AxQsExtendedBrightnessSlider(
            verticalStyle = verticalStyle,
            viewModel = viewModel,
            modifier = Modifier.width(PopupSliderThicknessLandscape).height(sliderLength),
        )
        Spacer(Modifier.width(32.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Header(viewModel)
            Spacer(Modifier.height(24.dp))
            Toggles(viewModel, verticalStyle)
        }
    }
}

@Composable
private fun Header(viewModel: BrightnessSliderViewModel) {
    val gamma = viewModel.currentBrightness.value
    val min = viewModel.minBrightness.value
    val max = viewModel.maxBrightness.value
    val percentage =
        if (max > min) ((gamma - min) * 100f / (max - min)).coerceIn(0f, 100f) else 0f
    val iconRes =
        if (viewModel.autoMode) {
            R.drawable.ic_qs_brightness_auto_on
        } else {
            BrightnessSliderViewModel.getIconForPercentage(percentage)
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.quick_settings_brightness_dialog_title),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "${percentage.toInt()}%",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun Toggles(viewModel: BrightnessSliderViewModel, verticalStyle: AxQsVerticalSliderStyle) {
    val context = LocalContext.current
    val uiModeManager =
        remember { context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager }
    val colorDisplayManager =
        remember { context.getSystemService(Context.COLOR_DISPLAY_SERVICE) as ColorDisplayManager }

    // Dark theme follows the configuration, which also changes when the theme is switched elsewhere.
    val configDark =
        (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    var darkTheme by remember { mutableStateOf(configDark) }
    LaunchedEffect(configDark) { darkTheme = configDark }

    var nightLight by remember { mutableStateOf(colorDisplayManager.isNightDisplayActivated) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    nightLight = colorDisplayManager.isNightDisplayActivated
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.NIGHT_DISPLAY_ACTIVATED),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        ExtendedToggle(
            label = axPopupString("ax_qs_auto_brightness", "Auto brightness"),
            iconRes =
                if (viewModel.autoMode) R.drawable.ic_qs_brightness_auto_on
                else R.drawable.ic_qs_brightness_auto_off,
            active = viewModel.autoMode,
            verticalStyle = verticalStyle,
            onToggle = { viewModel.onIconClick() },
        )
        ExtendedToggle(
            label = stringResource(R.string.quick_settings_ui_mode_night_label),
            // painterResource can't load the animated-vector tile icons, use the static vector.
            iconRes = R.drawable.vd_theme,
            active = darkTheme,
            verticalStyle = verticalStyle,
            onToggle = {
                darkTheme = !darkTheme
                uiModeManager.setNightModeActivated(darkTheme)
            },
        )
        ExtendedToggle(
            label = stringResource(R.string.quick_settings_night_display_label),
            iconRes = R.drawable.vd_night_light,
            active = nightLight,
            verticalStyle = verticalStyle,
            onToggle = {
                nightLight = !nightLight
                colorDisplayManager.setNightDisplayActivated(nightLight)
            },
        )
    }
}

/**
 * A round toggle matching the selected slider style: in Material 3 Expressive the shape morphs from
 * a squircle to a circle when active, in Platform it is always a circle.
 */
@Composable
private fun ExtendedToggle(
    label: String,
    @DrawableRes iconRes: Int,
    active: Boolean,
    verticalStyle: AxQsVerticalSliderStyle,
    onToggle: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val corner by
        animateDpAsState(
            targetValue =
                if (verticalStyle == AxQsVerticalSliderStyle.PLATFORM || active) {
                    ToggleSize / 2
                } else {
                    ToggleInactiveCorner
                },
            label = "AxExtendedToggleCorner",
        )
    val background by
        animateColorAsState(
            targetValue =
                if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    // Translucent glass so the blurred backdrop shows through.
                    MaterialTheme.colorScheme.onSurface.copy(alpha = TOGGLE_INACTIVE_ALPHA)
                },
            label = "AxExtendedToggleBackground",
        )
    val foreground by
        animateColorAsState(
            targetValue =
                if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
            label = "AxExtendedToggleForeground",
        )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(ToggleWidth)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(ToggleSize)
                    .clip(RoundedCornerShape(corner))
                    .background(background)
                    .toggleable(
                        value = active,
                        role = Role.Switch,
                        onValueChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggle()
                        },
                    ),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = foreground,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            text = stringResource(if (active) R.string.switch_bar_on else R.string.switch_bar_off),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
