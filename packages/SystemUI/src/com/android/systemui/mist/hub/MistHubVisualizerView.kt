/*
 * Copyright (C) 2026 MistOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.systemui.mist.hub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class MistHubVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        alpha = 200
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private var waveform: ByteArray = ByteArray(0)

    fun updateWaveform(data: ByteArray) {
        waveform = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (waveform.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2f
        val step = w / waveform.size
        var x = 0f
        var prevX = 0f
        var prevY = mid
        for (i in waveform.indices) {
            val y = mid - (abs(waveform[i].toInt()) / 128f) * mid
            if (i > 0) canvas.drawLine(prevX, prevY, x, y, paint)
            prevX = x; prevY = y
            x += step
        }
    }
}
