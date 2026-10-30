/*
 * Copyright 2025-2026 AxionOS
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

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import com.android.systemui.qs.panels.shared.model.AxQsImageFill
import kotlin.math.max

/** The optional image used instead of the primary color. Null keeps the default colors. */
val LocalAxQsImageFill = staticCompositionLocalOf<AxQsImageFill?> { null }

/**
 * Paints [bitmap] center-cropped to whatever area it is drawn in, so the image always covers the
 * tile or slider without being stretched.
 *
 * Sliders are laid out horizontally and then rotated by -90 degrees when vertical. Set
 * [rotateQuarter] for those so the image stays upright on screen instead of turning with them.
 */
private class ImageFillBrush(
    private val bitmap: Bitmap,
    private val rotateQuarter: Boolean,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val boxWidth = if (rotateQuarter) size.height else size.width
        val boxHeight = if (rotateQuarter) size.width else size.height
        val scale = max(boxWidth / bitmap.width, boxHeight / bitmap.height)

        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(-bitmap.width * scale / 2f, -bitmap.height * scale / 2f)
        if (rotateQuarter) matrix.postRotate(90f)
        matrix.postTranslate(size.width / 2f, size.height / 2f)

        return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
            it.setLocalMatrix(matrix)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ImageFillBrush && other.bitmap === bitmap && other.rotateQuarter == rotateQuarter

    override fun hashCode(): Int = 31 * System.identityHashCode(bitmap) + rotateQuarter.hashCode()
}

/**
 * Color for text and icons drawn on top of an active tile or slider. The theme's onPrimary is dark
 * in dark mode, which disappears on a dark image, so with an image set the color follows the image
 * (white, or black for a bright one) in both themes.
 */
@Composable
@ReadOnlyComposable
internal fun axQsActiveContentColor(): Color {
    val fill = LocalAxQsImageFill.current ?: return MaterialTheme.colorScheme.onPrimary
    return if (fill.isLight) Color.Black else Color.White
}

@Composable
internal fun rememberAxQsImageBrush(fill: AxQsImageFill?, rotateQuarter: Boolean = false): Brush? =
    remember(fill, rotateQuarter) {
        fill?.let { ImageFillBrush(it.bitmap, rotateQuarter) }
    }

/**
 * Draws the image behind the content while [active] is true. Inactive and unavailable states are
 * never touched, so they keep their default colors.
 */
@Composable
internal fun Modifier.axQsImageFill(active: Boolean): Modifier {
    val brush = rememberAxQsImageBrush(LocalAxQsImageFill.current) ?: return this
    val alpha by animateFloatAsState(targetValue = if (active) 1f else 0f, label = "AxQsImageFill")
    if (alpha <= 0f) return this
    return drawBehind { drawRect(brush = brush, alpha = alpha) }
}
