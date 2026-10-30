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

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Holds the user's custom "switch on" icon for the current process.
 *
 * Mistify keeps the chosen icon as a small PNG in /sdcard/MistOS/SwitchThumbIcon and stores its
 * path plus an enabled flag in secure settings. Because everything lives in settings and on disk,
 * each process (Settings, SystemUI, ...) simply reloads it on start, so the icon survives a reboot
 * or a SystemUI restart, and live-updates when the user picks a different one.
 */
internal object SwitchThumbIconStore {

    const val SETTING_ENABLED = "mist_switch_thumb_icon_enabled"
    const val SETTING_PATH = "mist_switch_thumb_icon_path"

    // Must match the directory Mistify writes to; anything outside of it is ignored.
    private const val ICON_DIR = "MistOS/SwitchThumbIcon"
    private const val MAX_ICON_SIZE_PX = 256
    private const val TAG = "SwitchThumbIconStore"

    /** The decoded icon, or null when the feature is off or the file is unavailable. */
    @Volatile
    var icon: Bitmap? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor by lazy { Executors.newSingleThreadExecutor() }
    private val drawables = ArrayList<WeakReference<SwitchThumbIconDrawable>>()

    private var resolver: ContentResolver? = null
    private var observer: ContentObserver? = null

    fun register(drawable: SwitchThumbIconDrawable) {
        synchronized(drawables) { drawables.add(WeakReference(drawable)) }
    }

    /** Starts watching the settings. Safe to call repeatedly; retries until a context exists. */
    @Synchronized
    fun ensureStarted() {
        if (resolver != null) return
        val cr = currentApplication()?.contentResolver ?: return
        val obs = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) = reload()
        }
        cr.registerContentObserver(Settings.Secure.getUriFor(SETTING_ENABLED), false, obs)
        cr.registerContentObserver(Settings.Secure.getUriFor(SETTING_PATH), false, obs)
        resolver = cr
        observer = obs
        reload()
    }

    private fun reload() {
        val cr = resolver ?: return
        // Single thread executor: results are posted back in the order they were requested.
        executor.execute {
            val bitmap = loadIcon(cr)
            mainHandler.post {
                icon = bitmap
                notifyChanged()
            }
        }
    }

    private fun loadIcon(cr: ContentResolver): Bitmap? {
        try {
            if (Settings.Secure.getInt(cr, SETTING_ENABLED, 0) == 0) return null
            val path = Settings.Secure.getString(cr, SETTING_PATH)
            if (path.isNullOrBlank()) return null

            val file = File(path).canonicalFile
            val root = File(Environment.getExternalStorageDirectory(), ICON_DIR).canonicalFile
            if (file.parentFile != root || !file.isFile) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val longest = max(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null

            var sample = 1
            while (longest / (sample * 2) >= MAX_ICON_SIZE_PX) sample *= 2
            return BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (e: Exception) {
            // Missing or unreadable (e.g. storage still locked): keep the default icon.
            Log.w(TAG, "Unable to load custom switch icon", e)
            return null
        }
    }

    private fun notifyChanged() {
        synchronized(drawables) {
            val iterator = drawables.iterator()
            while (iterator.hasNext()) {
                val drawable = iterator.next().get()
                if (drawable == null) iterator.remove() else drawable.invalidateSelf()
            }
        }
    }

    // The drawable is inflated from XML and has no context, so use the process application.
    private fun currentApplication(): Context? =
        try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        } catch (e: Exception) {
            null
        }
}
