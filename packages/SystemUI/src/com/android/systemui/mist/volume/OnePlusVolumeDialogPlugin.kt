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

package com.android.systemui.mist.volume

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.compose.theme.PlatformTheme
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.plugins.VolumeDialog
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.oneplus.ui.compose.OnePlusVolumePanel
import com.android.systemui.volume.dialog.oneplus.ui.viewmodel.OnePlusVolumePanelViewModel
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lineageos.providers.LineageSettings

class OnePlusVolumeDialogPlugin @Inject constructor(
    @Application private val context: Context,
    private val controller: VolumeDialogController,
    private val viewModelFactory: OnePlusVolumePanelViewModel.Factory,
) : VolumeDialog {

    private val handler = Handler(Looper.getMainLooper())
    private var pluginScope: CoroutineScope? = null

    private var dialog: OnePlusVolumeDialog? = null
    private var autoDismissJob: Job? = null
    private var expandStateJob: Job? = null

    private var isShowing = false
    private var isOnLeft = false

    companion object {
        private const val DISMISS_TIMEOUT_MS = 3000L
        private const val DISMISS_TIMEOUT_EXPANDED_MS = 6000L
    }

    private val volumePanelOnLeftObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            val onLeft = LineageSettings.Secure.getIntForUser(
                context.contentResolver,
                LineageSettings.Secure.VOLUME_PANEL_ON_LEFT,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
            if (onLeft != isOnLeft) {
                isOnLeft = onLeft
                recreateDialog()
            }
        }
    }

    private val controllerCallbacks = object : VolumeDialogController.Callbacks {
        override fun onShowRequested(reason: Int, keyguardLocked: Boolean, lockTaskModeState: Int) {
            handler.post { showDialog() }
        }

        override fun onDismissRequested(reason: Int) {
            handler.post { dismissDialog() }
        }

        override fun onScreenOff() {
            handler.post { dismissDialog() }
        }

        override fun onVolumeChangedFromKey() {
            handler.post {
                showDialog()
                rescheduleAutoDismiss()

                val hapticEnabled = Settings.Secure.getIntForUser(
                    context.contentResolver,
                    Settings.Secure.VOLUME_DIALOG_HAPTIC_FEEDBACK,
                    1,
                    UserHandle.USER_CURRENT,
                ) != 0
                if (hapticEnabled) {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (vibrator?.hasVibrator() == true) {
                        try {
                            vibrator.vibrate(
                                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                            )
                        } catch (_: Exception) {
                            vibrator.vibrate(
                                VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE)
                            )
                        }
                    }
                }
            }
        }

        override fun onStateChanged(state: VolumeDialogController.State) {}
        override fun onLayoutDirectionChanged(layoutDirection: Int) {}
        override fun onConfigurationChanged() {}
        override fun onShowVibrateHint() {}
        override fun onShowSilentHint() {}
        override fun onShowSafetyWarning(flags: Int) {}
        override fun onAccessibilityModeChanged(showA11yStream: Boolean?) {}
        override fun onCaptionEnabledStateChanged(isEnabled: Boolean, checkBeforeSwitch: Boolean) {}
        override fun onCaptionComponentStateChanged(isComponentEnabled: Boolean?, fromTooltip: Boolean) {}
        override fun onShowCsdWarning(csdWarning: Int, durationMs: Int) {}
    }

    override fun init(windowType: Int, callback: VolumeDialog.Callback) {
        pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        isOnLeft = LineageSettings.Secure.getIntForUser(
            context.contentResolver,
            LineageSettings.Secure.VOLUME_PANEL_ON_LEFT,
            0,
            UserHandle.USER_CURRENT,
        ) != 0

        context.contentResolver.registerContentObserver(
            LineageSettings.Secure.getUriFor(LineageSettings.Secure.VOLUME_PANEL_ON_LEFT),
            false,
            volumePanelOnLeftObserver,
            UserHandle.USER_ALL,
        )

        createDialog()
        controller.addCallback(controllerCallbacks, handler)
    }

    private fun createDialog() {
        dialog = OnePlusVolumeDialog(
            context = context,
            isOnLeft = isOnLeft,
            viewModelFactory = viewModelFactory,
            onDismiss = {
                isShowing = false
                controller.notifyVisible(false)
            },
            onExpandStateChanged = { expanded ->
                rescheduleAutoDismiss(expanded)
            },
        )
    }

    private fun recreateDialog() {
        val wasShowing = isShowing
        dialog?.dismiss()
        dialog = null
        isShowing = false
        createDialog()
        if (wasShowing) showDialog()
    }

    private fun showDialog() {
        val d = dialog ?: return
        if (!isShowing) {
            isShowing = true
            d.show()
            controller.notifyVisible(true)
        }
        rescheduleAutoDismiss(false)
    }

    private fun dismissDialog() {
        val d = dialog ?: return
        if (isShowing) {
            d.dismiss()
            isShowing = false
            controller.notifyVisible(false)
        }
        autoDismissJob?.cancel()
    }

    fun rescheduleAutoDismiss(expanded: Boolean = false) {
        autoDismissJob?.cancel()
        val timeout = if (expanded) DISMISS_TIMEOUT_EXPANDED_MS else DISMISS_TIMEOUT_MS
        autoDismissJob = pluginScope?.launch {
            delay(timeout)
            handler.post { dismissDialog() }
        }
    }

    override fun destroy() {
        controller.removeCallback(controllerCallbacks)
        context.contentResolver.unregisterContentObserver(volumePanelOnLeftObserver)
        autoDismissJob?.cancel()
        expandStateJob?.cancel()
        dialog?.dismiss()
        dialog = null
        pluginScope?.cancel()
        pluginScope = null
    }
}
