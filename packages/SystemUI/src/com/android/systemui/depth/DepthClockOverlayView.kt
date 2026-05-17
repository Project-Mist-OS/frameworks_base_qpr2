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
package com.android.systemui.depth

import android.app.WallpaperManager
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.util.Base64
import android.util.Log
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "DepthClockOverlayView"
private const val SETTING_DEPTH_MASK    = "ax_depth_subject_mask"
private const val SETTING_DEPTH_ENABLED = "ax_depth_clock_enabled"
private const val SETTING_DEPTH_OPACITY  = "lock_screen_depth_wallpaper_opacity"
private const val SETTING_DEPTH_OFFSET_X = "lock_screen_depth_wallpaper_offset_x"
private const val SETTING_DEPTH_OFFSET_Y = "lock_screen_depth_wallpaper_offset_y"
private const val NORMALIZE_RANGE = 10000f

class DepthClockOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    @Volatile private var cachedOpacityAlpha: Int = 255
    @Volatile private var cachedOffsetXPx: Float  = 0f
    @Volatile private var cachedOffsetYPx: Float  = 0f

    private val handler = Handler(Looper.getMainLooper())

    private var subjectBitmap: Bitmap? = null

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var isRegistered = false

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            refreshCachedSettings()
            refreshSubjectAsync()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerObservers()
        refreshCachedSettings()
        refreshSubjectAsync()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unregisterObservers()
        subjectBitmap?.recycle()
        subjectBitmap = null
    }

    private fun registerObservers() {
        if (isRegistered) return
        val cr = context.contentResolver
        listOf(
            Settings.Secure.getUriFor(SETTING_DEPTH_ENABLED),
            Settings.Secure.getUriFor(SETTING_DEPTH_MASK),
            Settings.System.getUriFor(SETTING_DEPTH_OPACITY),
            Settings.System.getUriFor(SETTING_DEPTH_OFFSET_X),
            Settings.System.getUriFor(SETTING_DEPTH_OFFSET_Y),
        ).forEach { uri ->
            cr.registerContentObserver(uri, false, settingsObserver, UserHandle.USER_ALL)
        }
        isRegistered = true
    }

    private fun unregisterObservers() {
        if (!isRegistered) return
        context.contentResolver.unregisterContentObserver(settingsObserver)
        isRegistered = false
    }

    private fun refreshCachedSettings() {
        val cr = context.contentResolver
        val opacity = Settings.System.getIntForUser(cr, SETTING_DEPTH_OPACITY, 100,
            UserHandle.USER_CURRENT).coerceIn(0, 100)
        cachedOpacityAlpha = (opacity / 100f * 255).toInt().coerceIn(0, 255)

        val density = resources.displayMetrics.density
        cachedOffsetXPx = Settings.System.getIntForUser(cr, SETTING_DEPTH_OFFSET_X, 0,
            UserHandle.USER_CURRENT) * density
        cachedOffsetYPx = Settings.System.getIntForUser(cr, SETTING_DEPTH_OFFSET_Y, 0,
            UserHandle.USER_CURRENT) * density
    }

    private fun refreshSubjectAsync() {
        Thread {
            val bitmap = buildSubjectBitmap()
            handler.post {
                subjectBitmap?.recycle()
                subjectBitmap = bitmap
                visibility = if (bitmap != null) VISIBLE else INVISIBLE
                invalidate()
            }
        }.start()
    }

    private fun buildSubjectBitmap(): Bitmap? {
        val cr = context.contentResolver

        val enabled = Settings.Secure.getInt(cr, SETTING_DEPTH_ENABLED, 0)
        if (enabled != 1) return null

        val encoded = Settings.Secure.getString(cr, SETTING_DEPTH_MASK)
        if (encoded.isNullOrBlank()) return null

        val path = try {
            decodeSubjectPath(encoded)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode depth mask path", e)
            return null
        } ?: return null

        val wallpaper = loadWallpaperBitmap() ?: run {
            Log.w(TAG, "Could not load wallpaper bitmap for depth overlay")
            return null
        }

        return try {
            buildMaskedBitmap(wallpaper, path)
        } finally {
            if (!wallpaper.isRecycled) wallpaper.recycle()
        }
    }

    private fun decodeSubjectPath(encoded: String): Path? {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        val version = buf.get().toInt() and 0xFF
        if (version != 1) {
            Log.w(TAG, "Unknown depth mask version: $version")
            return null
        }

        @Suppress("UNUSED_VARIABLE") val extractW = (buf.short.toInt() and 0xFFFF).toFloat()
        @Suppress("UNUSED_VARIABLE") val extractH = (buf.short.toInt() and 0xFFFF).toFloat()
        val numContours = buf.short.toInt() and 0xFFFF
        if (numContours == 0) return null

        val dstW = if (width  > 0) width.toFloat()  else resources.displayMetrics.widthPixels.toFloat()
        val dstH = if (height > 0) height.toFloat() else resources.displayMetrics.heightPixels.toFloat()

        val scaleX = dstW / NORMALIZE_RANGE
        val scaleY = dstH / NORMALIZE_RANGE

        val path = Path()
        repeat(numContours) {
            val numPoints = buf.short.toInt() and 0xFFFF
            if (numPoints < 3) {
                repeat(numPoints) { buf.short; buf.short }
                return@repeat
            }
            var first = true
            repeat(numPoints) {
                val nx = (buf.short.toInt() and 0xFFFF) * scaleX
                val ny = (buf.short.toInt() and 0xFFFF) * scaleY
                if (first) { path.moveTo(nx, ny); first = false }
                else path.lineTo(nx, ny)
            }
            path.close()
        }

        return if (path.isEmpty) null else path
    }

    private fun buildMaskedBitmap(wallpaper: Bitmap, path: Path): Bitmap? {
        val dstW = if (width  > 0) width  else resources.displayMetrics.widthPixels
        val dstH = if (height > 0) height else resources.displayMetrics.heightPixels

        val scaledWall = Bitmap.createScaledBitmap(wallpaper, dstW, dstH, true)

        val result = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        canvas.drawBitmap(scaledWall, 0f, 0f, null)
        if (scaledWall !== wallpaper) scaledWall.recycle()

        val maskBmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        Canvas(maskBmp).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG))

        val dstInPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(maskBmp, 0f, 0f, dstInPaint)
        maskBmp.recycle()

        return result
    }

    private fun loadWallpaperBitmap(): Bitmap? {
        val wm = WallpaperManager.getInstance(context)
        return try {
            wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)?.use { pfd ->
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
            } ?: run {
                (wm.drawable as? BitmapDrawable)?.bitmap
                    ?.copy(Bitmap.Config.ARGB_8888, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wallpaper load failed", e)
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = subjectBitmap ?: return
        bitmapPaint.alpha = cachedOpacityAlpha
        canvas.drawBitmap(bmp, cachedOffsetXPx, cachedOffsetYPx, bitmapPaint)
    }
}
