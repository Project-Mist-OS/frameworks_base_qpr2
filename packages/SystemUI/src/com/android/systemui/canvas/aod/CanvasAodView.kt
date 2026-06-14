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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.view.View
import java.io.File
import kotlin.random.Random

private const val TAG = "CanvasAodView"

private const val SETTING_ENABLED           = "canvas_aod_enabled"
private const val SETTING_CACHE_PATH        = "canvas_aod_cache_path"
private const val SETTING_STYLE             = "canvas_aod_style"
private const val SETTING_ANIMATION_ENABLED = "canvas_aod_animation_enabled"
private const val SETTING_ANIMATION_SPEED   = "canvas_aod_animation_speed"
private const val SETTING_ANIMATION_TYPE    = "canvas_aod_animation_type"
private const val SETTING_WEATHER_EFFECTS   = "canvas_aod_weather_effects"
private const val SETTING_WEATHER_INTENSITY = "canvas_aod_weather_intensity"
private const val SETTING_CHARGING_ANIM     = "canvas_aod_charging_animation"
private const val SETTING_NOTIF_PULSE       = "canvas_aod_notification_pulse"

private const val BURN_IN_MAX_PX      = 4
private const val BURN_IN_INTERVAL_MS = 60_000L

class CanvasAodView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    @Volatile private var canvasBitmap: Bitmap? = null
    @Volatile private var isDozing: Boolean = false

    private var burnInOffsetX = 0f
    private var burnInOffsetY = 0f
    private val burnInRunnable = Runnable { applyBurnInJitter() }

    private var animEnabled  = true
    private var animSpeed    = 1
    private var chargingAnim = true
    private var notifPulse   = true
    private var weatherEnabled = false
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private var animator: CanvasPathRevealAnimator? = null
    private var weatherOverlay: WeatherParticleOverlay? = null
    private var isRegistered = false
    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            refreshSettings()
            refreshBitmapAsync()
        }
    }

    private val chargingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED    -> if (chargingAnim) animator?.startChargingPulse()
                Intent.ACTION_POWER_DISCONNECTED -> animator?.stopChargingPulse()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerObservers()
        refreshSettings()
        refreshBitmapAsync()

        weatherOverlay = WeatherParticleOverlay(context) { postInvalidate() }.also { it.init() }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(chargingReceiver, filter)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unregisterObservers()
        handler.removeCallbacks(burnInRunnable)
        animator?.stop()
        weatherOverlay?.release()
        weatherOverlay = null
        canvasBitmap?.recycle()
        canvasBitmap = null
        try { context.unregisterReceiver(chargingReceiver) } catch (_: Exception) {}
    }

    private fun registerObservers() {
        if (isRegistered) return
        val cr = context.contentResolver
        listOf(
            Settings.Secure.getUriFor(SETTING_ENABLED),
            Settings.Secure.getUriFor(SETTING_CACHE_PATH),
            Settings.Secure.getUriFor(SETTING_STYLE),
            Settings.Secure.getUriFor(SETTING_ANIMATION_ENABLED),
            Settings.Secure.getUriFor(SETTING_ANIMATION_SPEED),
            Settings.Secure.getUriFor(SETTING_ANIMATION_TYPE),
            Settings.Secure.getUriFor(SETTING_WEATHER_EFFECTS),
            Settings.Secure.getUriFor(SETTING_WEATHER_INTENSITY),
            Settings.Secure.getUriFor(SETTING_CHARGING_ANIM),
            Settings.Secure.getUriFor(SETTING_NOTIF_PULSE),
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

    private fun refreshSettings() {
        val cr = context.contentResolver
        animEnabled    = Settings.Secure.getIntForUser(cr, SETTING_ANIMATION_ENABLED, 1, UserHandle.USER_CURRENT) == 1
        animSpeed      = Settings.Secure.getIntForUser(cr, SETTING_ANIMATION_SPEED, 1, UserHandle.USER_CURRENT)
        chargingAnim   = Settings.Secure.getIntForUser(cr, SETTING_CHARGING_ANIM, 1, UserHandle.USER_CURRENT) == 1
        notifPulse     = Settings.Secure.getIntForUser(cr, SETTING_NOTIF_PULSE, 1, UserHandle.USER_CURRENT) == 1
        weatherEnabled = Settings.Secure.getIntForUser(cr, SETTING_WEATHER_EFFECTS, 0, UserHandle.USER_CURRENT) == 1
        weatherOverlay?.onSettingsChanged()
    }

    private fun refreshBitmapAsync() {
        Thread {
            val bitmap = loadCanvasBitmap()
            handler.post {
                canvasBitmap?.recycle()
                canvasBitmap = bitmap
                if (bitmap != null && isDozing) {
                    startRevealAnimation(bitmap)
                }
                invalidate()
            }
        }.start()
    }

    private fun loadCanvasBitmap(): Bitmap? {
        val cr = context.contentResolver
        if (Settings.Secure.getIntForUser(cr, SETTING_ENABLED, 0, UserHandle.USER_CURRENT) != 1) return null

        val pathOrUri = Settings.Secure.getStringForUser(cr, SETTING_CACHE_PATH, UserHandle.USER_CURRENT) ?: return null

        return try {
            if (pathOrUri.startsWith("content://")) {
                val uri = android.net.Uri.parse(pathOrUri)
                cr.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } else {
                val file = File(pathOrUri)
                if (!file.exists() || file.length() == 0L) {
                    Log.w(TAG, "Canvas cache file missing: $pathOrUri")
                    return null
                }
                BitmapFactory.decodeFile(file.absolutePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load canvas bitmap", e)
            null
        }
    }

    fun onDozingChanged(dozing: Boolean, isCanvasEnabled: Boolean) {
        isDozing = dozing
        weatherOverlay?.active = dozing

        if (dozing) {
            if (isCanvasEnabled) {
                visibility = View.VISIBLE
                val bmp = canvasBitmap
                if (bmp != null) startRevealAnimation(bmp)
                handler.removeCallbacks(burnInRunnable)
                handler.postDelayed(burnInRunnable, BURN_IN_INTERVAL_MS)
            } else {
                visibility = View.INVISIBLE
            }
        } else {
            handler.removeCallbacks(burnInRunnable)
            burnInOffsetX = 0f; burnInOffsetY = 0f
            if (visibility == View.VISIBLE && isCanvasEnabled) {
                animator?.fadeOut {
                    visibility = View.INVISIBLE
                    animator?.stop()
                }
            } else {
                animator?.stop()
                visibility = View.INVISIBLE
            }
        }
        invalidate()
    }

    fun onNotificationReceived() {
        if (notifPulse) animator?.triggerNotifPulse()
    }

    private fun startRevealAnimation(bitmap: Bitmap) {
        animator?.stop()
        animator = CanvasPathRevealAnimator(
            bitmap     = bitmap,
            speedMode  = animSpeed,
            onInvalidate = { postInvalidate() },
        )
        if (animEnabled) {
            animator?.start()
        } else {
            animator?.revealInstant()
        }

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (chargingAnim && status == BatteryManager.BATTERY_STATUS_CHARGING) {
            animator?.startChargingPulse()
        }
    }

    private fun applyBurnInJitter() {
        if (!isDozing) return
        burnInOffsetX = (Random.nextFloat() * BURN_IN_MAX_PX * 2 - BURN_IN_MAX_PX)
        burnInOffsetY = (Random.nextFloat() * BURN_IN_MAX_PX * 2 - BURN_IN_MAX_PX)
        invalidate()
        handler.postDelayed(burnInRunnable, BURN_IN_INTERVAL_MS)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()

        if (burnInOffsetX != 0f || burnInOffsetY != 0f) {
            canvas.translate(burnInOffsetX, burnInOffsetY)
        }

        val anim = animator
        val bmp  = canvasBitmap
        if (anim != null && bmp != null) {
            anim.draw(canvas, bitmapPaint)
        }

        if (weatherEnabled && isDozing) {
            weatherOverlay?.draw(canvas)
        }

        canvas.restore()
    }
}

