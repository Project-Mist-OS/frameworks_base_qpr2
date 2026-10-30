/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.composable

import android.animation.ValueAnimator
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.android.systemui.biometrics.GlowRippleShader

/**
 * Renders the glow wave (or [rainbow] glow) fingerprint unlock animation, expanding from [origin]
 * to the edges of the screen, while [isEnabled] is `true`.
 *
 * [onAnimationFinished] is called once the animation has run to completion, or right away if the
 * effect can't be drawn.
 */
@Composable
fun GlowUnlockEffect(
    origin: Offset,
    isEnabled: Boolean,
    rainbow: Boolean,
    highFrameRate: Boolean,
    onAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val view = LocalView.current
    val shader = remember { runCatching { GlowRippleShader() }.getOrNull() }
    val brush = remember(shader) { shader?.let { ShaderBrush(it) } }
    val progress = remember { Animatable(0f) }
    val currentOnAnimationFinished by rememberUpdatedState(onAnimationFinished)

    LaunchedEffect(isEnabled, rainbow, highFrameRate, shader) {
        if (!isEnabled) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        if (shader == null || !ValueAnimator.areAnimatorsEnabled()) {
            currentOnAnimationFinished()
            return@LaunchedEffect
        }
        shader.setRainbow(rainbow)
        view.setRequestedFrameRate(
            if (highFrameRate) View.REQUESTED_FRAME_RATE_CATEGORY_HIGH else 60f
        )
        try {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec =
                    tween(durationMillis = GlowRippleShader.DURATION_MS.toInt(), easing = LinearEasing),
            )
        } finally {
            view.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
        }
        currentOnAnimationFinished()
    }

    Box(
        modifier =
            modifier.fillMaxSize().drawBehind {
                val currentProgress = progress.value
                if (isEnabled && currentProgress > 0f && shader != null && brush != null) {
                    shader.setGeometry(
                        size.width.toInt(),
                        size.height.toInt(),
                        origin.x,
                        origin.y,
                        density,
                    )
                    shader.setProgress(currentProgress)
                    drawRect(brush = brush)
                }
            }
    )
}
