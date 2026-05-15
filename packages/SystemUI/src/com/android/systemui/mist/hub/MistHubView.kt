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

package com.android.systemui.mist.hub

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.graphics.Typeface
import com.airbnb.lottie.LottieAnimationView
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.systemui.res.R

class MistHubView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    companion object {
        private const val TAG = "MistHubView"
        private const val FLING_DISTANCE = 80f
        private const val FLING_VELOCITY = 80f
    }

    private lateinit var pillContainer: ViewGroup
    private lateinit var iconView: ImageView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var chronometerView: android.widget.Chronometer
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var batteryLevelView: TextView
    private lateinit var visualizerView: LottieAnimationView
    private lateinit var actionsContainer: LinearLayout
    private lateinit var actionBtn1: TextView
    private lateinit var actionBtn2: TextView
    private lateinit var actionBtn3: TextView

    var cornerRadiusDp: Float = 40f
    var edgeGlowEnabled: Boolean = false
    var springAnimEnabled: Boolean = true
    var pulseEnabled: Boolean = true
    var musicVizEnabled: Boolean = false
        set(v) { 
            field = v
            if (v && currentState is MistHubState.NowPlaying) {
                visualizerView.playAnimation()
            } else {
                visualizerView.cancelAnimation()
            }
        }
    var animSpeedMs: Long = 120L

    private var controller: MistHubController? = null
    private var activityStarter: com.android.systemui.plugins.ActivityStarter? = null
    private var currentState: MistHubState = MistHubState.Hidden

    private val springY = SpringAnimation(FloatValueHolder()).apply {
        spring = SpringForce().apply {
            stiffness = SpringForce.STIFFNESS_MEDIUM
            dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        }
        addUpdateListener { _, value, _ -> translationY = value }
    }

    private val pillHitRect = RectF()

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var hasSwiped = false
    private var childConsumedDown = false

    private fun launchCurrentIntent() {
        val pendingIntent: PendingIntent? = when (val s = currentState) {
            is MistHubState.NowPlaying   -> s.contentIntent
            is MistHubState.LiveUpdate   -> s.contentIntent
            is MistHubState.Notification -> s.contentIntent
            else                         -> null
        }
        if (pendingIntent == null) {
            Log.d(TAG, "No intent for state=$currentState")
            return
        }
        try {
            activityStarter?.postStartActivityDismissingKeyguard(pendingIntent)
            Log.d(TAG, "Launched intent for state=$currentState and requested keyguard dismiss")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch intent", e)
        }
    }

    init {
        setWillNotDraw(false)
        elevation = 100f
        translationZ = 100f
        LayoutInflater.from(context).inflate(R.layout.mist_hub_view, this, true)

        pillContainer    = requireViewById(R.id.mist_hub_pill)
        pillContainer.isClickable = false
        pillContainer.isFocusable = false

        iconView         = requireViewById(R.id.mist_hub_icon)
        iconView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        iconView.clipToOutline = true
        titleView        = requireViewById(R.id.mist_hub_title)
        subtitleView     = requireViewById(R.id.mist_hub_subtitle)
        chronometerView  = requireViewById(R.id.mist_hub_chronometer)
        progressBar      = requireViewById(R.id.mist_hub_progress)
        progressLabel    = requireViewById(R.id.mist_hub_progress_label)
        batteryLevelView = requireViewById(R.id.mist_hub_battery_level)
        visualizerView   = requireViewById(R.id.mist_hub_visualizer)
        actionsContainer = requireViewById(R.id.mist_hub_actions)
        actionBtn1       = requireViewById(R.id.mist_hub_action_1)
        actionBtn2       = requireViewById(R.id.mist_hub_action_2)
        actionBtn3       = requireViewById(R.id.mist_hub_action_3)

        titleView.isSelected = true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (visibility != VISIBLE || alpha < 0.1f) return super.dispatchTouchEvent(ev)
        if (!::pillContainer.isInitialized) return super.dispatchTouchEvent(ev)

        val loc = IntArray(2)
        pillContainer.getLocationOnScreen(loc)
        val onPill = ev.rawX >= loc[0] && ev.rawX <= loc[0] + pillContainer.width &&
                     ev.rawY >= loc[1] && ev.rawY <= loc[1] + pillContainer.height

        val childHandled = super.dispatchTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!onPill) return childHandled
                parent?.requestDisallowInterceptTouchEvent(true)
                touchDownX = ev.rawX
                touchDownY = ev.rawY
                touchDownTime = System.currentTimeMillis()
                hasSwiped = false
                childConsumedDown = childHandled
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hasSwiped) return true
                val dx = ev.rawX - touchDownX
                val dy = ev.rawY - touchDownY
                val threshold = 80f
                
                if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy)) {
                    hasSwiped = true
                    if (dx < 0) {
                        Log.d(TAG, "swipe left → next()")
                        controller?.next()
                    } else {
                        Log.d(TAG, "swipe right → previous()")
                        controller?.previous()
                    }
                } else if (dy > threshold && Math.abs(dy) > Math.abs(dx)) {
                    hasSwiped = true
                    Log.d(TAG, "swipe down → dismissCurrent()")
                    controller?.dismissCurrent()
                }
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!hasSwiped && !childConsumedDown && onPill) {
                    val dx = ev.rawX - touchDownX
                    val dy = ev.rawY - touchDownY
                    if (Math.abs(dx) < 20f && Math.abs(dy) < 20f && System.currentTimeMillis() - touchDownTime < 400) {
                        Log.d(TAG, "tap on state=$currentState")
                        launchCurrentIntent()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        return true
    }

    fun applyConfig(ctrl: MistHubController, starter: com.android.systemui.plugins.ActivityStarter? = null) {
        controller        = ctrl
        activityStarter   = starter
        cornerRadiusDp    = ctrl.cornerRadius.toFloat()
        edgeGlowEnabled   = ctrl.edgeGlow
        springAnimEnabled = ctrl.springAnim
        pulseEnabled      = ctrl.pulseOnNotification
        animSpeedMs       = ctrl.animSpeed.toLong()
        musicVizEnabled   = ctrl.showMusicViz
        
        requestLayout()

        val tf = ctrl.hubTypeface ?: Typeface.DEFAULT
        titleView.typeface    = tf
        subtitleView.typeface = tf

        updateBackground(currentState)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val exactWidthPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            controller?.maxWidth?.toFloat() ?: 300f,
            context.resources.displayMetrics
        ).toInt()

        val newWidthSpec = MeasureSpec.makeMeasureSpec(exactWidthPx, MeasureSpec.EXACTLY)
        super.onMeasure(newWidthSpec, heightMeasureSpec)
    }

    private fun updateBackground(state: MistHubState) {
        val cornerRadiusPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            cornerRadiusDp,
            context.resources.displayMetrics
        )
        val fillDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(0x80000000.toInt())
            cornerRadius = cornerRadiusPx
        }
        val gradientDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0x00FFFFFF, 0x28FFFFFF)
        ).apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }
        
        val strokeColor = if (edgeGlowEnabled && state !is MistHubState.Hidden) {
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)
            typedValue.data
        } else 0x33FFFFFF
        val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(0x00000000)
            setStroke(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 3f, context.resources.displayMetrics).toInt(), strokeColor)
            cornerRadius = cornerRadiusPx
        }
        
        val layers = if (state is MistHubState.Charging) {
            val levelColor = when {
                state.level <= 20 -> 0x8AFF4444.toInt()
                state.level <= 50 -> 0x8AFFAA00.toInt()
                else              -> 0x8A10B272.toInt()
            }
            val waveDrawable = MistHubBatteryFillDrawable(cornerRadiusPx, state.level, levelColor)
            arrayOf(fillDrawable, waveDrawable, gradientDrawable, borderDrawable)
        } else {
            arrayOf(fillDrawable, gradientDrawable, borderDrawable)
        }
        pillContainer.background = android.graphics.drawable.LayerDrawable(layers)
    }

    fun applyState(state: MistHubState) {
        val stateChanged = currentState != state
        if (stateChanged) {
            Log.d(TAG, "applyState: $state")
            iconView.animate().cancel()
            iconView.clearAnimation()
            iconView.rotation = 0f
            iconView.scaleX = 1f
            iconView.scaleY = 1f
            iconView.alpha = 1f
        }
        
        if (visibility == VISIBLE && springAnimEnabled) {
            val transition = android.transition.AutoTransition().apply {
                duration = animSpeedMs
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }
            android.transition.TransitionManager.beginDelayedTransition(pillContainer, transition)
        }

        currentState = state
        updateBackground(state)
        when (state) {
            is MistHubState.Hidden       -> animateOut()
            is MistHubState.Charging     -> renderCharging(state)
            is MistHubState.NowPlaying   -> renderNowPlaying(state)
            is MistHubState.LiveUpdate   -> renderLiveUpdate(state)
            is MistHubState.Notification -> renderNotification(state)
        }
    }

    private fun renderCharging(s: MistHubState.Charging) {
        val levelColor = when {
            s.level <= 20 -> 0xFFFF4444.toInt()
            s.level <= 50 -> 0xFFFFAA00.toInt()
            else          -> 0xFF10B272.toInt()
        }
        
        iconView.visibility = VISIBLE
        iconView.imageTintList = null
        iconView.setImageDrawable(context.getDrawable(R.drawable.ic_mist_hub_charging))

        titleView.text    = "${s.level}%"
        titleView.setTextColor(0xFFFFFFFF.toInt())
        titleView.gravity = android.view.Gravity.CENTER
        titleView.textSize = 22f
        
        subtitleView.visibility = GONE
        chronometerView.visibility = GONE
        
        batteryLevelView.visibility = GONE
        progressBar.visibility      = GONE
        progressLabel.visibility    = GONE
        visualizerView.visibility   = GONE
        actionsContainer.visibility = GONE
        animateIn()
    }

    private var rotationAnimator: android.animation.ObjectAnimator? = null

    private fun renderNowPlaying(s: MistHubState.NowPlaying) {
        iconView.visibility = VISIBLE
        iconView.imageTintList = null
        titleView.setTextColor(0xFFFFFFFF.toInt())
        titleView.gravity = android.view.Gravity.START
        titleView.textSize = 13f
        iconView.setImageDrawable(
            s.albumArt ?: context.getDrawable(R.drawable.ic_mist_hub_music)
        )
        titleView.text = s.title
        subtitleView.visibility = GONE
        subtitleView.gravity = android.view.Gravity.START
        chronometerView.visibility = GONE
        batteryLevelView.visibility = GONE
        progressBar.visibility      = GONE
        progressLabel.visibility    = GONE
        visualizerView.visibility = if (musicVizEnabled) VISIBLE else GONE
        if (musicVizEnabled) {
            visualizerView.playAnimation()
        } else {
            visualizerView.cancelAnimation()
        }
        
        actionsContainer.visibility = VISIBLE
        actionBtn1.visibility = VISIBLE
        actionBtn2.visibility = VISIBLE
        actionBtn3.visibility = VISIBLE
        
        fun setMediaIcon(btn: android.widget.TextView, resId: Int) {
            val d = context.getDrawable(resId)?.apply {
                setTint(0xFFFFFFFF.toInt())
                setBounds(0, 0, 48, 48)
            }
            btn.setCompoundDrawables(d, null, null, null)
            btn.text = ""
        }
        
        setMediaIcon(actionBtn1, R.drawable.ic_media_prev)
        setMediaIcon(actionBtn2, if (s.isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play)
        setMediaIcon(actionBtn3, R.drawable.ic_media_next)
        
        actionBtn1.setOnClickListener { s.mediaController?.transportControls?.skipToPrevious() }
        actionBtn2.setOnClickListener {
            if (s.isPlaying) s.mediaController?.transportControls?.pause()
            else s.mediaController?.transportControls?.play()
        }
        actionBtn3.setOnClickListener { s.mediaController?.transportControls?.skipToNext() }

        animateIn()
        
        rotationAnimator?.cancel()
        if (s.isPlaying) {
            rotationAnimator = android.animation.ObjectAnimator.ofFloat(iconView, "rotation", 0f, 360f).apply {
                duration = 8000
                interpolator = android.view.animation.LinearInterpolator()
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun renderLiveUpdate(s: MistHubState.LiveUpdate) {
        iconView.visibility = VISIBLE
        iconView.imageTintList = null
        titleView.setTextColor(0xFFFFFFFF.toInt())
        titleView.gravity = android.view.Gravity.START
        titleView.textSize = 13f
        iconView.setImageDrawable(s.appIcon)
        if (s.showChronometer) {
            titleView.visibility = GONE
            chronometerView.visibility = VISIBLE
            val baseAdj = s.chronometerBase - System.currentTimeMillis() + android.os.SystemClock.elapsedRealtime()
            chronometerView.base = baseAdj
            try { chronometerView.isCountDown = s.chronometerDown } catch(_: Exception) {}
            chronometerView.start()
        } else {
            titleView.text = s.title
            titleView.visibility = VISIBLE
            chronometerView.visibility = GONE
            chronometerView.stop()
        }
        subtitleView.visibility = VISIBLE
        subtitleView.text = s.text
        subtitleView.gravity = android.view.Gravity.START
        batteryLevelView.visibility = GONE
        visualizerView.visibility   = GONE
        if (s.progress in 0..100) {
            progressBar.visibility    = VISIBLE
            progressBar.progress      = s.progress
            progressLabel.visibility  = VISIBLE
            progressLabel.text        = s.progressLabel
        } else {
            progressBar.visibility   = GONE
            progressLabel.visibility = GONE
        }
        
        if (s.actions.isNotEmpty()) {
            actionsContainer.visibility = VISIBLE
            val btns = arrayOf(actionBtn1, actionBtn2, actionBtn3)
            btns.forEach { it.visibility = GONE; it.setOnClickListener(null) }
            
            s.actions.take(3).forEachIndexed { i, action ->
                btns[i].visibility = VISIBLE
                val d = action.getIcon()?.loadDrawable(context)
                if (d != null) {
                    d.setTint(0xFFFFFFFF.toInt())
                    d.setBounds(0, 0, 48, 48)
                    btns[i].setCompoundDrawables(d, null, null, null)
                    btns[i].text = ""
                } else {
                    btns[i].setCompoundDrawables(null, null, null, null)
                    btns[i].text = action.title
                }
                btns[i].setOnClickListener {
                    try { action.actionIntent.send() } catch (_: Exception) {}
                }
            }
        } else {
            actionsContainer.visibility = GONE
        }
        
        animateIn()
    }

    private fun renderNotification(s: MistHubState.Notification) {
        iconView.visibility = VISIBLE
        iconView.imageTintList = null
        titleView.setTextColor(0xFFFFFFFF.toInt())
        titleView.gravity = android.view.Gravity.START
        titleView.textSize = 13f
        iconView.setImageDrawable(s.appIcon)
        titleView.text    = s.title
        titleView.visibility = VISIBLE
        chronometerView.visibility = GONE
        chronometerView.stop()
        subtitleView.visibility = VISIBLE
        subtitleView.text = s.text
        subtitleView.gravity = android.view.Gravity.START
        batteryLevelView.visibility = GONE
        progressBar.visibility      = GONE
        progressLabel.visibility    = GONE
        visualizerView.visibility   = GONE
        actionsContainer.visibility = GONE
        animateIn()
    }

    private fun animateIn() {
        if (visibility == VISIBLE && alpha == 1f) return
        visibility = VISIBLE
        if (springAnimEnabled) {
            springY.cancel()
            translationY = 100f
            springY.animateToFinalPosition(0f)
            animate().alpha(1f).setDuration(animSpeedMs).start()
        } else {
            translationY = 0f
            alpha = 1f
        }
    }

    private fun animateOut() {
        if (visibility != VISIBLE) return
        if (springAnimEnabled) {
            animate().alpha(0f).translationY(100f).setDuration(animSpeedMs)
                .withEndAction { visibility = GONE }.start()
        } else {
            visibility = GONE
        }
    }

    fun pulse() {
        if (!pulseEnabled) return
        animate().scaleX(1.07f).scaleY(1.07f).setDuration(animSpeedMs / 2)
            .withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(animSpeedMs / 2).start()
            }.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        visualizerView.cancelAnimation()
    }
}

class MistHubBatteryFillDrawable(
    private val cornerRadius: Float,
    private val levelPct: Int,
    private val baseColor: Int
) : android.graphics.drawable.Drawable() {

    private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    private val shimmerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    private val clipPath = android.graphics.Path()

    private var shimmerPos = 0f
    private val shimmerAnimator = android.animation.ValueAnimator.ofFloat(-0.3f, 1.1f).apply {
        duration = 2200
        startDelay = 400
        repeatCount = android.animation.ValueAnimator.INFINITE
        repeatMode  = android.animation.ValueAnimator.RESTART
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { anim ->
            shimmerPos = anim.animatedValue as Float
            invalidateSelf()
        }
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        if (bounds.width() > 0 && !shimmerAnimator.isRunning) shimmerAnimator.start()
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w == 0f || h == 0f) return

        val fillRight = w * (levelPct.coerceIn(0, 100) / 100f)
        if (fillRight <= 0f) return

        clipPath.reset()
        clipPath.addRoundRect(
            android.graphics.RectF(0f, 0f, w, h),
            cornerRadius, cornerRadius,
            android.graphics.Path.Direction.CW
        )
        canvas.save()
        canvas.clipPath(clipPath)

        fillPaint.color = baseColor
        canvas.drawRect(0f, 0f, fillRight, h, fillPaint)

        val stripeW = fillRight * 0.35f
        val centerX  = fillRight * shimmerPos
        val x0 = centerX - stripeW / 2f
        val x1 = centerX + stripeW / 2f
        shimmerPaint.shader = android.graphics.LinearGradient(
            x0, 0f, x1, 0f,
            intArrayOf(0x00FFFFFF, 0x28FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(x0.coerceAtLeast(0f), 0f, x1.coerceAtMost(fillRight), h, shimmerPaint)

        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha    = alpha
        shimmerPaint.alpha = alpha
    }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {
        fillPaint.colorFilter    = cf
        shimmerPaint.colorFilter = cf
    }
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

