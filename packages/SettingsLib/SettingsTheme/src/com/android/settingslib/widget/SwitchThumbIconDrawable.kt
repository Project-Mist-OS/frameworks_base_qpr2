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

package com.android.settingslib.widget

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import com.android.settingslib.widget.theme.R
import org.xmlpull.v1.XmlPullParser
import kotlin.math.min

/**
 * The icon shown inside a switch thumb while the switch is ON.
 *
 * Without a custom icon it simply draws the stock check mark, tinted by the switch like before.
 * When the user picked a custom icon in Mistify (Settings > Themes) it draws that image instead,
 * untinted and clipped to a circle. The OFF icon is a separate selector item and is never touched.
 */
class SwitchThumbIconDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val dest = RectF()

    private var checkIcon: Drawable? = null
    private var shader: BitmapShader? = null
    private var shaderBitmap: Bitmap? = null
    private var baseAlpha = 255
    private var isEnabledState = true

    // Set only for previews, which show a given icon regardless of what is saved in settings.
    private var isPreview = false
    private var previewIcon: Bitmap? = null

    init {
        SwitchThumbIconStore.register(this)
        SwitchThumbIconStore.ensureStarted()
    }

    override fun inflate(
        r: Resources,
        parser: XmlPullParser,
        attrs: AttributeSet,
        theme: Resources.Theme?,
    ) {
        super.inflate(r, parser, attrs, theme)
        checkIcon = r.getDrawable(R.drawable.settingslib_expressive_icon_check, theme)?.mutate()
        checkIcon?.bounds = bounds
    }

    override fun draw(canvas: Canvas) {
        // Retry in case the application context was not available when this was created.
        SwitchThumbIconStore.ensureStarted()

        val bitmap = if (isPreview) previewIcon else SwitchThumbIconStore.icon
        if (bitmap == null || bitmap.isRecycled) {
            checkIcon?.draw(canvas)
            return
        }
        if (bounds.isEmpty) return

        if (shaderBitmap !== bitmap) {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shaderBitmap = bitmap
        }
        // The icon slot is small, so let a custom image fill a little more of the thumb.
        val radius = min(bounds.width(), bounds.height()) * CUSTOM_ICON_SCALE / 2f
        dest.set(
            bounds.exactCenterX() - radius,
            bounds.exactCenterY() - radius,
            bounds.exactCenterX() + radius,
            bounds.exactCenterY() + radius,
        )
        matrix.setRectToRect(
            RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
            dest,
            Matrix.ScaleToFit.FILL,
        )
        shader?.setLocalMatrix(matrix)

        paint.shader = shader
        paint.alpha = if (isEnabledState) baseAlpha else (baseAlpha * DISABLED_ALPHA).toInt()
        canvas.drawCircle(dest.centerX(), dest.centerY(), radius, paint)
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val enabled = state.contains(android.R.attr.state_enabled)
        val changed = enabled != isEnabledState
        isEnabledState = enabled
        val iconChanged = checkIcon?.setState(state) ?: false
        return changed || iconChanged
    }

    override fun onBoundsChange(bounds: Rect) {
        checkIcon?.bounds = bounds
    }

    override fun setAlpha(alpha: Int) {
        baseAlpha = alpha
        checkIcon?.alpha = alpha
        invalidateSelf()
    }

    // The switch tints its icon; only the stock check mark should follow that, never a picture.
    override fun setTintList(tint: ColorStateList?) {
        checkIcon?.setTintList(tint)
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {
        checkIcon?.setTintMode(tintMode ?: PorterDuff.Mode.SRC_IN)
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        checkIcon?.colorFilter = colorFilter
    }

    override fun getIntrinsicWidth(): Int = checkIcon?.intrinsicWidth ?: -1

    override fun getIntrinsicHeight(): Int = checkIcon?.intrinsicHeight ?: -1

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // Without a constant state the selector that holds this drawable shares ONE instance between all
    // switches, so the icon gets drawn with another switch's bounds. Every switch needs its own copy.
    override fun getConstantState(): Drawable.ConstantState = State(checkIcon?.constantState)

    private class State(private val checkState: Drawable.ConstantState?) : Drawable.ConstantState() {
        override fun newDrawable(): Drawable = newDrawable(null)

        override fun newDrawable(res: Resources?): Drawable =
            SwitchThumbIconDrawable().apply {
                // mutate() so tinting this copy does not tint the shared check mark.
                checkIcon = checkState?.newDrawable(res)?.mutate()
            }

        override fun getChangingConfigurations(): Int = 0
    }

    companion object {
        private const val CUSTOM_ICON_SCALE = 1.25f
        private const val DISABLED_ALPHA = 0.38f

        /**
         * Creates a drawable pinned to [icon], or to the stock check mark when [icon] is null,
         * ignoring whatever is saved in settings. Used to preview a choice before/while applying it.
         */
        @JvmStatic
        fun createPreview(context: Context, icon: Bitmap?): SwitchThumbIconDrawable =
            SwitchThumbIconDrawable().apply {
                isPreview = true
                previewIcon = icon
                checkIcon = context.getDrawable(R.drawable.settingslib_expressive_icon_check)?.mutate()
            }
    }
}
