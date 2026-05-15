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
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.mist.hub.MistHubController
import com.android.systemui.mist.hub.MistHubState
import com.android.systemui.mist.hub.MistHubView
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.awaitCancellation
import javax.inject.Inject

import com.android.systemui.plugins.ActivityStarter

class MistHubSection @Inject constructor(
    @ShadeDisplayAware private val context: Context,
    private val mistHubController: MistHubController,
    private val activityStarter: ActivityStarter
) : KeyguardSection() {

    companion object { private const val TAG = "MistHubSection" }

    private val viewId = View.generateViewId()
    private var bindHandle: DisposableHandle? = null
    private var mistHubView: MistHubView? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        mistHubView = MistHubView(context).apply {
            id = viewId
        }
        constraintLayout.addView(mistHubView)
        Log.d(TAG, "MistHubView added to KeyguardRootView hierarchy")
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val view = mistHubView ?: return

        Log.d(TAG, "bindData: Starting MistHubController")
        mistHubController.start()

        bindHandle = view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                view.applyConfig(mistHubController, activityStarter)

                val updateMargin = {
                    val currentOffset = mistHubController.verticalOffset
                    val offsetPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        currentOffset.toFloat(),
                        context.resources.displayMetrics
                    ).toInt()
                    val baseMarginPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        80f,
                        context.resources.displayMetrics
                    ).toInt()

                    val cs = ConstraintSet()
                    cs.clone(constraintLayout)
                    cs.setMargin(viewId, ConstraintSet.BOTTOM, offsetPx + baseMarginPx)
                    cs.applyTo(constraintLayout)
                }

                val listener = { state: MistHubState ->
                    view.applyConfig(mistHubController, activityStarter)
                    view.applyState(state)
                    updateMargin()
                }
                mistHubController.addListener(listener)
                
                mistHubController.invalidateCallback = {
                    view.applyConfig(mistHubController, activityStarter)
                    updateMargin()
                }

                try {
                    awaitCancellation()
                } finally {
                    mistHubController.removeListener(listener)
                    mistHubController.invalidateCallback = null
                }
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val offsetPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            mistHubController.verticalOffset.toFloat(),
            context.resources.displayMetrics
        ).toInt()

        constraintSet.apply {
            constrainWidth(viewId, ViewGroup.LayoutParams.WRAP_CONTENT)
            constrainHeight(viewId, ViewGroup.LayoutParams.WRAP_CONTENT)

            connect(viewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(viewId, ConstraintSet.END,   ConstraintSet.PARENT_ID, ConstraintSet.END)

            val baseMarginPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                80f,
                context.resources.displayMetrics
            ).toInt()

            connect(viewId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            setMargin(viewId, ConstraintSet.BOTTOM, offsetPx + baseMarginPx)

            Log.d(TAG, "applyConstraints: vertical offset=${mistHubController.verticalOffset}dp (${offsetPx}px)")
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        bindHandle?.dispose()
        bindHandle = null
        mistHubController.stop()
        mistHubView?.let { constraintLayout.removeView(it) }
        mistHubView = null
    }
}
