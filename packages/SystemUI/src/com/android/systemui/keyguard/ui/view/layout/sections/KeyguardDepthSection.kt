/*
 * Copyright (C) 2025 MistOS
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
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.depth.DepthClockOverlayView
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import javax.inject.Inject

class KeyguardDepthSection
@Inject
constructor(
    private val context: Context,
) : KeyguardSection() {

    private val TAG = "KeyguardDepthSection"
    private var depthView: DepthClockOverlayView? = null

    companion object {
        val VIEW_ID: Int
            get() = R.id.depth_wallpaper_subject
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        constraintLayout.findViewById<View?>(VIEW_ID)?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }

        depthView = DepthClockOverlayView(context).apply {
            id = VIEW_ID
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT,
            )
            visibility = View.INVISIBLE
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        depthView?.let { constraintLayout.addView(it) }
        Log.d(TAG, "DepthClockOverlayView added to keyguard layout")
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val id = VIEW_ID
        constraintSet.apply {
            connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(id, ConstraintSet.END,   ConstraintSet.PARENT_ID, ConstraintSet.END)
            connect(id, ConstraintSet.TOP,   ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            constrainWidth(id, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(id, ConstraintSet.MATCH_CONSTRAINT)
            setElevation(id, 2f)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        depthView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        depthView = null
    }
}
