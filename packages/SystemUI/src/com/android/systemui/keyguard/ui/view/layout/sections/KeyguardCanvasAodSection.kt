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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.canvas.aod.CanvasAodView
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import javax.inject.Inject

private const val TAG = "KeyguardCanvasAodSection"
private const val SETTING_CANVAS_ENABLED = "canvas_aod_enabled"

class KeyguardCanvasAodSection
@Inject
constructor(
    private val context: Context,
    private val statusBarStateController: StatusBarStateController,
) : KeyguardSection() {

    private val TAG_LOCAL = TAG
    private var canvasAodView: CanvasAodView? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var isCanvasEnabled = false

    private val enabledObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            isCanvasEnabled = Settings.Secure.getIntForUser(
                context.contentResolver, SETTING_CANVAS_ENABLED, 0, UserHandle.USER_CURRENT
            ) == 1
            updateVisibility()
        }
    }

    private val statusBarListener = object : StatusBarStateController.StateListener {
        override fun onDozingChanged(dozing: Boolean) {
            canvasAodView?.onDozingChanged(dozing)
            updateVisibility()
        }
    }

    companion object {
        val VIEW_ID: Int
            get() = R.id.canvas_aod_view
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        constraintLayout.findViewById<View?>(VIEW_ID)?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }

        canvasAodView = CanvasAodView(context).apply {
            id = VIEW_ID
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT,
            )
            visibility = View.INVISIBLE
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        canvasAodView?.let { constraintLayout.addView(it) }
        Log.d(TAG_LOCAL, "CanvasAodView added to keyguard layout")

        statusBarStateController.addCallback(statusBarListener)
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_CANVAS_ENABLED),
            false,
            enabledObserver,
            UserHandle.USER_ALL,
        )

        isCanvasEnabled = Settings.Secure.getIntForUser(
            context.contentResolver, SETTING_CANVAS_ENABLED, 0, UserHandle.USER_CURRENT
        ) == 1

        val dozing = statusBarStateController.isDozing
        canvasAodView?.onDozingChanged(dozing)
        updateVisibility()
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val id = VIEW_ID
        constraintSet.apply {
            connect(id, ConstraintSet.START,  ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(id, ConstraintSet.END,    ConstraintSet.PARENT_ID, ConstraintSet.END)
            connect(id, ConstraintSet.TOP,    ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            constrainWidth(id,  ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(id, ConstraintSet.MATCH_CONSTRAINT)
            setElevation(id, 1.5f)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        statusBarStateController.removeCallback(statusBarListener)
        context.contentResolver.unregisterContentObserver(enabledObserver)
        canvasAodView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        canvasAodView = null
    }

    private fun updateVisibility() {
        val dozing = statusBarStateController.isDozing
        canvasAodView?.visibility = if (isCanvasEnabled && dozing) View.VISIBLE else View.INVISIBLE
        Log.d(TAG_LOCAL, "updateVisibility: enabled=$isCanvasEnabled dozing=$dozing")
    }
}
