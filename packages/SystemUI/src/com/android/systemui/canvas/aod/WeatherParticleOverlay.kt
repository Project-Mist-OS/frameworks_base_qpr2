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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import com.android.internal.util.mist.OmniJawsClient
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val TAG = "WeatherParticleOverlay"

private const val COND_THUNDERSTORM_MIN = 200
private const val COND_THUNDERSTORM_MAX = 232
private const val COND_DRIZZLE_MIN      = 300
private const val COND_DRIZZLE_MAX      = 321
private const val COND_RAIN_MIN         = 500
private const val COND_RAIN_MAX         = 531
private const val COND_SNOW_MIN         = 600
private const val COND_SNOW_MAX         = 622
private const val COND_ATMO_MIN         = 701
private const val COND_ATMO_MAX         = 741
private const val COND_CLOUDS_MIN       = 801
private const val COND_CLOUDS_MAX       = 804

private const val SETTING_WEATHER_EFFECTS    = "canvas_aod_weather_effects"
private const val SETTING_WEATHER_INTENSITY  = "canvas_aod_weather_intensity"

private enum class WeatherEffect { NONE, RAIN, SNOW, FOG, LIGHTNING, CLOUDS, SUNNY }

class WeatherParticleOverlay(
    private val context: Context,
    private val onInvalidate: () -> Unit,
) {

    var active: Boolean = false
        set(value) { field = value; if (value) scheduleFrame() }

    private var currentEffect = WeatherEffect.NONE
    private var intensityMultiplier = 1.0f
    private val rainPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val snowPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fogPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint()
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private data class RainDrop(
        var x: Float, var y: Float,
        var speed: Float, var length: Float, var alpha: Int,
    )

    private data class SnowFlake(
        var x: Float, var y: Float, var radius: Float,
        var speed: Float, var sway: Float, var swayPhase: Float, var alpha: Int,
    )

    private data class CloudShape(
        var x: Float, var y: Float, var radius: Float,
        var speedX: Float, var alpha: Int,
    )

    private var rainDrops  = emptyList<RainDrop>()
    private var snowFlakes = emptyList<SnowFlake>()
    private var cloudsList = emptyList<CloudShape>()

    private var fogOffsetX1 = 0f
    private var fogOffsetX2 = 0f
    private var fogPhase    = 0f

    private var sunPulsePhase = 0f

    private var lightningFlashAlpha = 0
    private var lightningCooldown   = 0L
    private var lightningFrameCount = 0

    private var viewW = 1f
    private var viewH = 1f

    private var cloudBitmap: Bitmap? = null

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback { doFrame() }

    private val omniJawsObserver = object : OmniJawsClient.OmniJawsObserver {
        override fun weatherUpdated() { refreshWeatherEffect() }
        override fun weatherError(errorReason: Int) { currentEffect = WeatherEffect.NONE }
    }

    fun init() {
        refreshSettings()
        if (isWeatherEnabled()) {
            OmniJawsClient.get().addObserver(context, omniJawsObserver)
            refreshWeatherEffect()
        }
    }

    fun release() {
        active = false
        OmniJawsClient.get().removeObserver(context, omniJawsObserver)
    }

    fun onSettingsChanged() {
        refreshSettings()
        if (isWeatherEnabled()) {
            OmniJawsClient.get().addObserver(context, omniJawsObserver)
            refreshWeatherEffect()
        } else {
            OmniJawsClient.get().removeObserver(context, omniJawsObserver)
            currentEffect = WeatherEffect.NONE
        }
    }

    private fun isWeatherEnabled(): Boolean =
        Settings.Secure.getIntForUser(
            context.contentResolver, SETTING_WEATHER_EFFECTS, 0, UserHandle.USER_CURRENT
        ) == 1

    private fun refreshSettings() {
        val intensity = Settings.Secure.getIntForUser(
            context.contentResolver, SETTING_WEATHER_INTENSITY, 1, UserHandle.USER_CURRENT
        )
        intensityMultiplier = when (intensity) {
            0    -> 0.5f
            2    -> 1.8f
            else -> 1.0f
        }
    }

    private fun refreshWeatherEffect() {
        if (!isWeatherEnabled()) { currentEffect = WeatherEffect.NONE; return }
        try {
            OmniJawsClient.get().queryWeather(context)
            val info = OmniJawsClient.get().weatherInfo
            if (info == null) { currentEffect = WeatherEffect.NONE; return }
            val codeStr = info.conditionCode?.toString() ?: ""
            val code = try { codeStr.toInt() } catch (e: Exception) { -1 }
            
            val isThunderstorm = code in COND_THUNDERSTORM_MIN..COND_THUNDERSTORM_MAX || 
                                 code in listOf(3, 4, 37, 38, 39, 45, 47)
            val isRain = code in COND_DRIZZLE_MIN..COND_DRIZZLE_MAX || 
                         code in COND_RAIN_MIN..COND_RAIN_MAX ||
                         code in listOf(5, 6, 8, 9, 10, 11, 12, 35, 40)
            val isSnow = code in COND_SNOW_MIN..COND_SNOW_MAX || 
                         code in listOf(7, 13, 14, 15, 16, 17, 18, 41, 42, 43, 46)
            val isFog = code in COND_ATMO_MIN..COND_ATMO_MAX || 
                        code in listOf(19, 20, 21, 22)
            val isClouds = code in COND_CLOUDS_MIN..COND_CLOUDS_MAX || 
                           code in listOf(26, 27, 28, 29, 30, 44)
            val isSunny = code == 800 || code in listOf(31, 32, 33, 34, 36)

            currentEffect = when {
                isThunderstorm -> WeatherEffect.LIGHTNING
                isRain         -> WeatherEffect.RAIN
                isSnow         -> WeatherEffect.SNOW
                isFog          -> WeatherEffect.FOG
                isClouds       -> WeatherEffect.CLOUDS
                isSunny        -> WeatherEffect.SUNNY
                else           -> WeatherEffect.NONE
            }
            Log.d(TAG, "Weather effect: $currentEffect (conditionCode=$code)")
            if (currentEffect != WeatherEffect.NONE && viewW > 1f) {
                initParticles(viewW, viewH)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Weather query failed", e)
        }
    }

    private fun initParticles(w: Float, h: Float) {
        val base = intensityMultiplier

        when (currentEffect) {
            WeatherEffect.RAIN -> {
                val count = (45 * base).toInt().coerceIn(15, 120)
                rainDrops = List(count) {
                    RainDrop(
                        x      = Random.nextFloat() * w,
                        y      = Random.nextFloat() * h,
                        speed  = 8f + Random.nextFloat() * 6f,
                        length = (25f + Random.nextFloat() * 25f),
                        alpha  = (60 + Random.nextInt(80)),
                    )
                }
            }
            WeatherEffect.SNOW -> {
                val count = (30 * base).toInt().coerceIn(10, 80)
                snowFlakes = List(count) {
                    SnowFlake(
                        x         = Random.nextFloat() * w,
                        y         = Random.nextFloat() * h,
                        radius    = 2f + Random.nextFloat() * 4f,
                        speed     = 0.8f + Random.nextFloat() * 1.5f,
                        sway      = 0.5f + Random.nextFloat() * 1.0f,
                        swayPhase = Random.nextFloat() * Math.PI.toFloat() * 2f,
                        alpha     = (100 + Random.nextInt(100)),
                    )
                }
            }
            WeatherEffect.FOG -> {
                fogOffsetX1 = 0f; fogOffsetX2 = w * 0.4f; fogPhase = 0f
            }
            WeatherEffect.CLOUDS -> {
                if (cloudBitmap == null) {
                    try {
                        val drawable = context.getDrawable(com.android.systemui.res.R.drawable.canvas_aod_cloudy)
                        if (drawable != null) {
                            val bitmap = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                            drawable.draw(canvas)
                            cloudBitmap = bitmap
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load cloud drawable", e)
                    }
                }
                
                val count = 3
                cloudsList = List(count) {
                    CloudShape(
                        x = Random.nextFloat() * w,
                        y = Random.nextFloat() * (h * 0.4f),
                        radius = w * 0.15f + Random.nextFloat() * (w * 0.1f),
                        speedX = 0.2f + Random.nextFloat() * 0.5f,
                        alpha = 15 + Random.nextInt(25)
                    )
                }
            }
            WeatherEffect.SUNNY -> {
                sunPulsePhase = 0f
            }
            WeatherEffect.LIGHTNING -> {
                lightningCooldown   = System.currentTimeMillis() + randomLightningDelay()
                lightningFlashAlpha = 0
                lightningFrameCount = 0
            }
            else -> {}
        }
    }

    private fun randomLightningDelay() = (8000L + Random.nextLong(12000L))

    private fun scheduleFrame() {
        choreographer.postFrameCallback(frameCallback)
    }

    private fun doFrame() {
        if (!active || currentEffect == WeatherEffect.NONE) return
        updateParticles()
        onInvalidate()
        scheduleFrame()
    }

    private fun updateParticles() {
        val w = viewW; val h = viewH
        when (currentEffect) {
            WeatherEffect.RAIN -> {
                for (drop in rainDrops) {
                    drop.y += drop.speed
                    drop.x += drop.speed * 0.35f
                    if (drop.y - drop.length > h || drop.x > w) {
                        drop.x = Random.nextFloat() * w
                        drop.y = -drop.length
                    }
                }
            }
            WeatherEffect.SNOW -> {
                for (flake in snowFlakes) {
                    flake.swayPhase += 0.03f
                    flake.y += flake.speed
                    flake.x += sin(flake.swayPhase) * flake.sway
                    if (flake.y - flake.radius > h) {
                        flake.y = -flake.radius
                        flake.x = Random.nextFloat() * w
                    }
                    if (flake.x < 0) flake.x = w
                    if (flake.x > w) flake.x = 0f
                }
            }
            WeatherEffect.FOG -> {
                fogPhase += 0.003f
                fogOffsetX1 = sin(fogPhase) * w * 0.08f
                fogOffsetX2 = cos(fogPhase * 0.7f) * w * 0.06f
            }
            WeatherEffect.CLOUDS -> {
                for (c in cloudsList) {
                    c.x += c.speedX
                    if (c.x - c.radius > w) {
                        c.x = -c.radius
                        c.y = Random.nextFloat() * (h * 0.4f)
                    }
                }
            }
            WeatherEffect.SUNNY -> {
                sunPulsePhase += 0.015f
            }
            WeatherEffect.LIGHTNING -> {
                if (lightningFlashAlpha > 0) {
                    lightningFlashAlpha = (lightningFlashAlpha - 12).coerceAtLeast(0)
                } else if (System.currentTimeMillis() >= lightningCooldown) {
                    lightningFlashAlpha = 60 + Random.nextInt(30)
                    lightningCooldown   = System.currentTimeMillis() + randomLightningDelay()
                }
            }
            else -> {}
        }
    }

    fun draw(canvas: Canvas) {
        viewW = canvas.width.toFloat()
        viewH = canvas.height.toFloat()

        if (viewW <= 1f) return
        if (currentEffect == WeatherEffect.NONE) return
        if (!active) return

        when (currentEffect) {
            WeatherEffect.RAIN      -> drawRain(canvas)
            WeatherEffect.SNOW      -> drawSnow(canvas)
            WeatherEffect.FOG       -> drawFog(canvas)
            WeatherEffect.CLOUDS    -> drawClouds(canvas)
            WeatherEffect.SUNNY     -> drawSunny(canvas)
            WeatherEffect.LIGHTNING -> drawLightning(canvas)
            else -> {}
        }

        if (rainDrops.isEmpty() && snowFlakes.isEmpty() && cloudsList.isEmpty() && currentEffect != WeatherEffect.FOG
            && currentEffect != WeatherEffect.LIGHTNING && currentEffect != WeatherEffect.SUNNY && currentEffect != WeatherEffect.NONE) {
            initParticles(viewW, viewH)
        }
        if (currentEffect == WeatherEffect.FOG || currentEffect == WeatherEffect.LIGHTNING || currentEffect == WeatherEffect.SUNNY) {
            if (fogOffsetX1 == 0f && fogOffsetX2 == 0f && fogPhase == 0f
                && lightningCooldown == 0L && sunPulsePhase == 0f) {
                initParticles(viewW, viewH)
            }
        }
    }

    private fun drawRain(canvas: Canvas) {
        for (drop in rainDrops) {
            rainPaint.color = Color.WHITE
            rainPaint.alpha = (drop.alpha * intensityMultiplier).toInt().coerceIn(0, 220)
            rainPaint.strokeWidth = 1.2f
            val angle = Math.toRadians(80.0)
            val endX = drop.x - drop.length * cos(angle).toFloat()
            val endY = drop.y - drop.length * sin(angle).toFloat()
            canvas.drawLine(drop.x, drop.y, endX, endY, rainPaint)
        }
    }

    private fun drawSnow(canvas: Canvas) {
        for (flake in snowFlakes) {
            snowPaint.color = Color.WHITE
            snowPaint.alpha = (flake.alpha * intensityMultiplier).toInt().coerceIn(0, 220)
            canvas.drawCircle(flake.x, flake.y, flake.radius, snowPaint)
        }
    }

    private fun drawFog(canvas: Canvas) {
        val w = viewW; val h = viewH
        val baseAlpha = (25 * intensityMultiplier).toInt().coerceIn(8, 50)

        fogPaint.shader = android.graphics.RadialGradient(
            w * 0.3f + fogOffsetX1, h * 0.4f,
            w * 0.8f,
            intArrayOf(Color.argb(baseAlpha, 240, 240, 255), Color.TRANSPARENT),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fogPaint)

        fogPaint.shader = android.graphics.RadialGradient(
            w * 0.7f + fogOffsetX2, h * 0.6f,
            w * 0.7f,
            intArrayOf(Color.argb((baseAlpha * 0.6f).toInt(), 220, 235, 255), Color.TRANSPARENT),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fogPaint)
    }

    private val cloudRect = RectF()

    private fun drawClouds(canvas: Canvas) {
        val bitmap = cloudBitmap
        if (bitmap == null) return
        
        for (c in cloudsList) {
            cloudPaint.color = Color.WHITE
            cloudPaint.alpha = (c.alpha * intensityMultiplier).toInt().coerceIn(0, 100)
            
            val w = c.radius * 2.5f
            val h = c.radius * 2.5f
            cloudRect.set(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)
            
            canvas.drawBitmap(bitmap, null, cloudRect, cloudPaint)
        }
    }

    private fun drawSunny(canvas: Canvas) {
        val w = viewW; val h = viewH
        val baseAlpha = (30 * intensityMultiplier).toInt().coerceIn(10, 60)
        val pulse = (sin(sunPulsePhase) * 10f).toInt()
        
        sunPaint.shader = android.graphics.RadialGradient(
            w * 0.8f, h * 0.15f,
            w * 0.5f,
            intArrayOf(Color.argb(baseAlpha + pulse, 255, 220, 120), Color.TRANSPARENT),
            null,
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, sunPaint)
    }

    private fun drawLightning(canvas: Canvas) {
        if (lightningFlashAlpha <= 0) return
        flashPaint.color = Color.WHITE
        flashPaint.alpha = lightningFlashAlpha
        canvas.drawRect(0f, 0f, viewW, viewH, flashPaint)
    }
}
