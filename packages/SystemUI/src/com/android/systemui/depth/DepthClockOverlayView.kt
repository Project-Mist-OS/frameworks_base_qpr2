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
import android.graphics.Rect
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
private const val SETTING_DEPTH_BOUNDS  = "ax_depth_subject_bounds"
private const val SETTING_DEPTH_ENABLED = "ax_depth_clock_enabled"
private const val SETTING_DEPTH_OPACITY  = "lock_screen_depth_wallpaper_opacity"
private const val SETTING_DEPTH_OFFSET_X = "lock_screen_depth_wallpaper_offset_x"
private const val SETTING_DEPTH_OFFSET_Y = "lock_screen_depth_wallpaper_offset_y"
private const val NORMALIZE_RANGE = 10000f

private const val LAUNCHER_NEXUS_PIXEL = "com.google.android.apps.nexuslauncher"
private const val WALLPAPER_SCALE_PIXEL = 1.10f

class DepthClockOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    @Volatile private var cachedOpacityAlpha: Int = 255
    @Volatile private var cachedOffsetXPx: Float  = 0f
    @Volatile private var cachedOffsetYPx: Float  = 0f
    @Volatile private var cachedLauncherScale: Float = 1.0f

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
            Settings.Secure.getUriFor(SETTING_DEPTH_BOUNDS),
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
        cachedLauncherScale = resolveLauncherWallpaperScale()
    }

    private var hideOnAod = false
    fun setHideOnAod(hide: Boolean) {
        if (hideOnAod != hide) {
            hideOnAod = hide
            handler.post { updateVisibility() }
        }
    }
    private fun updateVisibility() {
        visibility = if (hideOnAod) INVISIBLE else (if (subjectBitmap != null) VISIBLE else INVISIBLE)
    }

    private fun refreshSubjectAsync() {
        Thread {
            val bitmap = buildSubjectBitmap()
            handler.post {
                subjectBitmap?.recycle()
                subjectBitmap = bitmap
                updateVisibility()

                if (bitmap != null) {
                    this@DepthClockOverlayView.z = 2f
                    (parent as? android.view.ViewGroup)?.let { parentView ->
                        parentView.findViewById<android.view.View>(com.android.systemui.res.R.id.device_entry_icon_view)?.z = 3f
                        parentView.findViewById<android.view.View>(com.android.systemui.res.R.id.ax_dynamic_bar_keyguard_chip)?.z = 3f
                        for (i in 0 until parentView.childCount) {
                            val child = parentView.getChildAt(i)
                            if (child.javaClass.simpleName == "MistHubView" || child is com.android.systemui.mist.hub.MistHubView) {
                                child.z = 3f
                            }
                        }
                        parentView.findViewById<android.view.View>(com.android.systemui.res.R.id.ambient_indication_container)?.z = 3f
                        parentView.findViewById<android.view.View>(com.android.systemui.res.R.id.keyguard_slice_view)?.z = 3f
                        parentView.findViewById<android.view.View>(com.android.systemui.res.R.id.bc_smartspace_view)?.z = 3f
                    }
                }

                invalidate()
            }
        }.start()
    }

    private fun parseCropBoundsFromSettings(cr: android.content.ContentResolver, actualW: Int, actualH: Int): Rect? {
        val boundsStr = Settings.Secure.getString(cr, SETTING_DEPTH_BOUNDS)
        if (boundsStr.isNullOrBlank()) return null

        return try {
            val parts = boundsStr.split(",")
            if (parts.size == 6) {
                val srcW = parts[0].toInt()
                val srcH = parts[1].toInt()
                val left = parts[2].toInt()
                val top = parts[3].toInt()
                val right = parts[4].toInt()
                val bottom = parts[5].toInt()

                if (srcW <= 0 || srcH <= 0) return null

                val scaleX = actualW.toFloat() / srcW
                val scaleY = actualH.toFloat() / srcH

                val l = (left * scaleX).toInt().coerceIn(0, actualW)
                val t = (top * scaleY).toInt().coerceIn(0, actualH)
                val r = (right * scaleX).toInt().coerceIn(l, actualW)
                val b = (bottom * scaleY).toInt().coerceIn(t, actualH)

                Rect(l, t, r, b)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse crop bounds: $boundsStr", e)
            null
        }
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

        val cropHint = parseCropBoundsFromSettings(cr, wallpaper.width, wallpaper.height)

        return try {
            buildMaskedBitmap(wallpaper, path, cropHint)
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

    private fun centerCropBitmap(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val srcW = src.width
        val srcH = src.height
        val srcAR = srcW.toFloat() / srcH
        val dstAR = dstW.toFloat() / dstH

        val cropW: Int
        val cropH: Int
        val left: Int
        val top: Int

        if (srcAR > dstAR) {
            cropH = srcH
            cropW = (srcH * dstAR).toInt().coerceAtMost(srcW)
            left = (srcW - cropW) / 2
            top  = 0
        } else {
            cropW = srcW
            cropH = (srcW / dstAR).toInt().coerceAtMost(srcH)
            left  = 0
            top   = (srcH - cropH) / 2
        }

        if (cropW >= srcW - 2 && cropH >= srcH - 2) return src

        return try {
            Bitmap.createBitmap(src, left, top, cropW, cropH)
        } catch (e: Exception) {
            Log.w(TAG, "centerCropBitmap failed, using original", e)
            src
        }
    }

    private fun buildMaskedBitmap(wallpaper: Bitmap, path: Path, cropHint: Rect?): Bitmap? {
        val dstW = if (width  > 0) width  else resources.displayMetrics.widthPixels
        val dstH = if (height > 0) height else resources.displayMetrics.heightPixels

        val croppedWall = if (cropHint != null && cropHint.width() > 0 && cropHint.height() > 0) {
            try {
                Bitmap.createBitmap(wallpaper, cropHint.left, cropHint.top, cropHint.width(), cropHint.height())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to crop using cropHint: $cropHint", e)
                centerCropBitmap(wallpaper, dstW, dstH)
            }
        } else {
            centerCropBitmap(wallpaper, dstW, dstH)
        }

        val scaledWall = Bitmap.createScaledBitmap(croppedWall, dstW, dstH, true)
        if (croppedWall !== wallpaper && !croppedWall.isRecycled) croppedWall.recycle()

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

    private fun resolveLauncherWallpaperScale(): Float {
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
            }
            val info = context.packageManager.resolveActivity(
                intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            val pkg = info?.activityInfo?.packageName
            Log.d(TAG, "Default launcher: $pkg")
            if (pkg == LAUNCHER_NEXUS_PIXEL) WALLPAPER_SCALE_PIXEL else 1.0f
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve default launcher, defaulting scale to 1.0", e)
            1.0f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = subjectBitmap ?: return
        bitmapPaint.alpha = cachedOpacityAlpha

        val scale = cachedLauncherScale
        if (scale != 1.0f) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.save()
            canvas.scale(scale, scale, cx, cy)
            canvas.drawBitmap(bmp, cachedOffsetXPx, cachedOffsetYPx, bitmapPaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bmp, cachedOffsetXPx, cachedOffsetYPx, bitmapPaint)
        }
    }
}
