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
import android.graphics.PixelFormat
import android.os.UserHandle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.compose.theme.PlatformTheme
import com.android.systemui.volume.dialog.oneplus.ui.compose.OnePlusVolumePanel
import com.android.systemui.volume.dialog.oneplus.ui.viewmodel.OnePlusVolumePanelViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * A floating window (not a Dialog subclass) that hosts the OnePlus-style
 * volume panel as a Compose UI attached directly to WindowManager.
 */
class OnePlusVolumeDialog(
    private val context: Context,
    private val isOnLeft: Boolean,
    private val viewModelFactory: OnePlusVolumePanelViewModel.Factory,
    private val onDismiss: () -> Unit,
    private val onExpandStateChanged: (Boolean) -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: FrameLayout? = null
    private var viewScope: CoroutineScope? = null

    private var viewModel: OnePlusVolumePanelViewModel? = null

    private val blurRadius = context.resources
        .getDimensionPixelSize(com.android.systemui.res.R.dimen.volume_dialog_oneplus_blur_radius)

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun show() {
        if (rootView != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        viewScope = scope

        val vm = viewModelFactory.create(scope)
        viewModel = vm

        val root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_UP && event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (vm.onBackPressed()) {
                        return true
                    }
                    dismiss()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        rootView = root

        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)

        val composeView = ComposeView(context).apply {
            setContent {
                PlatformTheme {
                    OnePlusVolumePanel(
                        viewModel = vm,
                        isOnLeft = isOnLeft,
                        onDismissRequest = {
                            dismiss()
                        },
                    )
                }
            }
        }
        root.addView(composeView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        val gravity = if (isOnLeft) Gravity.START else Gravity.END
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        windowManager.addView(root, lp)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Observe expanded state to update window focus
        vm.isExpanded.onEach { expanded: Boolean ->
            onExpandStateChanged(expanded)
            val window = lp
            if (expanded) {
                window.flags = window.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                window.flags = window.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            rootView?.let { windowManager.updateViewLayout(it, window) }
        }.launchIn(scope)

        // Observe blur state to handle delayed unblurring during collapse animations
        vm.windowBlurEnabled.onEach { blurOn: Boolean ->
            val window = lp
            if (blurOn) {
                window.blurBehindRadius = blurRadius
                window.flags = window.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            } else {
                window.blurBehindRadius = 0
                window.flags = window.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            }
            rootView?.let { windowManager.updateViewLayout(it, window) }
        }.launchIn(scope)

        // Propagate dismiss from ViewModel
        vm.dismissEvent.onEach { _: Unit ->
            onDismiss()
        }.launchIn(scope)
    }

    fun dismiss() {
        val root = rootView ?: return
        viewModel?.collapse()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {}
        rootView = null
        viewScope?.cancel()
        viewScope = null
    }
}
