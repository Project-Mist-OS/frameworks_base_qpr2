/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.composable

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnAttach
import com.android.lunaris.waterripple.WaterRippleView

/**
 * Renders the water or watercolor ([animation]) fingerprint unlock animation, playing over the
 * screen contents from [origin], while [isEnabled] is `true`.
 *
 * [onAnimationFinished] is called once the animation has run to completion. If the effect can't be
 * set up, [onAnimationFailed] is called instead so the caller can fall back to another effect.
 */
@Composable
fun WaterUnlockEffect(
    origin: Offset,
    animation: Int,
    isEnabled: Boolean,
    highFrameRate: Boolean,
    fullscreen: Boolean,
    durationMs: Long,
    onAnimationFinished: () -> Unit,
    onAnimationFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isEnabled) return

    val currentOnAnimationFinished by rememberUpdatedState(onAnimationFinished)
    val currentOnAnimationFailed by rememberUpdatedState(onAnimationFailed)

    // A WaterRippleView plays a single animation, so every request gets a new view.
    key(animation, highFrameRate, fullscreen) {
        AndroidView(
            factory = { context ->
                WaterRippleView(context).also { effect ->
                    // The effect needs a hardware accelerated window to start.
                    effect.doOnAttach {
                        effect.playUnlock(
                            origin.x,
                            origin.y,
                            durationMs,
                            animation,
                            highFrameRate,
                            fullscreen,
                            { currentOnAnimationFinished() },
                            { currentOnAnimationFailed() },
                        )
                    }
                }
            },
            onRelease = { it.cancel() },
            modifier = modifier.fillMaxSize(),
        )
    }
}
