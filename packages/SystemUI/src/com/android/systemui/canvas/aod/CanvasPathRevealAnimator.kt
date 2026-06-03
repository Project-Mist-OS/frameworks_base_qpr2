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

package com.android.systemui.canvas.aod

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.util.Log

private const val TAG = "CanvasPathRevealAnimator"

private const val SPEED_SLOW   = 4000L
private const val SPEED_NORMAL = 2500L
private const val SPEED_FAST   = 1200L

class CanvasPathRevealAnimator(
    private val bitmap: Bitmap,
    private val speedMode: Int,
    private val onInvalidate: () -> Unit,
) {

    @Volatile var progress: Float = 0f
        private set

    @Volatile var fadeAlpha: Int = 0
        private set

    private var animator: ValueAnimator? = null

    @Volatile var isChargingPulse: Boolean = false
    private var pulseAnimator: ValueAnimator? = null
    @Volatile var pulseAlpha: Int = 255

    @Volatile var notifFlashAlpha: Int = 0
    private var notifAnimator: ValueAnimator? = null

    private val duration: Long = when (speedMode) {
        0    -> SPEED_SLOW
        2    -> SPEED_FAST
        else -> SPEED_NORMAL
    }

    fun start() {
        stop()
        progress  = 0f
        fadeAlpha = 0

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = this@CanvasPathRevealAnimator.duration
            interpolator  = LinearInterpolator()
            addUpdateListener { anim ->
                val frac = anim.animatedFraction
                progress  = frac
                fadeAlpha = ((frac / 0.3f).coerceIn(0f, 1f) * 255f).toInt()
                onInvalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
        notifAnimator?.cancel()
        notifAnimator = null
    }

    fun revealInstant() {
        stop()
        progress  = 1f
        fadeAlpha = 255
        onInvalidate()
    }

    fun fadeOut(onEnd: () -> Unit) {
        stop()
        val startAlpha = fadeAlpha
        if (startAlpha <= 0) {
            onEnd()
            return
        }
        animator = ValueAnimator.ofInt(startAlpha, 0).apply {
            duration = 400L
            interpolator = android.view.animation.AccelerateInterpolator()
            addUpdateListener { anim ->
                fadeAlpha = anim.animatedValue as Int
                onInvalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            start()
        }
    }

    fun startChargingPulse() {
        if (isChargingPulse) return
        isChargingPulse = true
        pulseAnimator = ValueAnimator.ofInt(255, 160, 255).apply {
            duration      = 1800L
            repeatMode    = ValueAnimator.RESTART
            repeatCount   = ValueAnimator.INFINITE
            interpolator  = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                pulseAlpha = anim.animatedValue as Int
                onInvalidate()
            }
            start()
        }
    }

    fun stopChargingPulse() {
        isChargingPulse = false
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseAlpha = 255
        onInvalidate()
    }

    fun triggerNotifPulse() {
        notifAnimator?.cancel()
        notifFlashAlpha = 0
        notifAnimator = ValueAnimator.ofInt(0, 80, 0).apply {
            duration     = 600L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                notifFlashAlpha = anim.animatedValue as Int
                onInvalidate()
            }
            start()
        }
    }

    fun draw(canvas: Canvas, paint: Paint) {
        if (bitmap.isRecycled) return
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val revealH = (h * progress).toInt().coerceIn(0, h.toInt())

        val effectiveAlpha = ((fadeAlpha * (pulseAlpha / 255f)).toInt())
            .coerceIn(0, 255)
        paint.alpha = effectiveAlpha

        canvas.save()
        canvas.clipRect(0f, 0f, w, revealH.toFloat())
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        canvas.restore()

        if (notifFlashAlpha > 0) {
            val flashPaint = Paint().apply { alpha = notifFlashAlpha }
            canvas.save()
            canvas.clipRect(0f, 0f, w, revealH.toFloat())
            canvas.drawBitmap(bitmap, 0f, 0f, flashPaint)
            canvas.restore()
        }
    }
}
