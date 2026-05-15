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

package com.android.systemui.mist.hub

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import org.json.JSONArray
import javax.inject.Inject

@SysUISingleton
class MistHubController @Inject constructor(
    private val context: Context,
    private val mediaSessionManager: MediaSessionManager
) {

    companion object {
        private const val TAG = "MistHubController"

        const val KEY_ENABLED          = "mist_hub_enabled"
        const val KEY_CORNER_RADIUS    = "mist_hub_corner_radius"
        const val KEY_EDGE_GLOW        = "mist_hub_edge_glow"
        const val KEY_ANIM_SPEED       = "mist_hub_animation_speed"
        const val KEY_SPRING_ANIM      = "mist_hub_spring_animation"
        const val KEY_PULSE            = "mist_hub_pulse_notifications"
        const val KEY_BATTERY          = "mist_hub_battery_status"
        const val KEY_MUSIC_VIZ        = "mist_hub_music_visualizer"
        const val KEY_ALLOWED_APPS     = "mist_hub_allowed_apps"
        const val KEY_FONT_PACKAGE     = "mist_hub_font_package"
        const val KEY_VERTICAL_OFFSET  = "mist_hub_vertical_offset"
        const val KEY_MAX_WIDTH        = "mist_hub_max_width"

        private val LIVE_UPDATE_PACKAGES = setOf(
            "com.google.android.apps.maps",
            "in.swiggy.android",
            "com.application.zomato",
            "com.ola.client",
            "com.ubercab",
            "com.rapido.passenger",
            "com.delhivery.courier",
            "com.ekart.lookout",
            "in.amazon.mShop.android.shopping",
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.oneplus.deskclock",
            "com.coloros.alarmclock"
        )

        private val CLOCK_PACKAGES = setOf(
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.oneplus.deskclock",
            "com.coloros.alarmclock"
        )
    }

    var isEnabled: Boolean = false; private set
    var cornerRadius: Int = 50; private set
    var edgeGlow: Boolean = true; private set
    var animSpeed: Int = 100; private set
    var springAnim: Boolean = true; private set
    var pulseOnNotification: Boolean = true; private set
    var showBattery: Boolean = true; private set
    var showMusicViz: Boolean = false; private set
    var verticalOffset: Int = 16; private set
    var maxWidth: Int = 300; private set
    var hubTypeface: Typeface? = null; private set

    private var currentState: MistHubState = MistHubState.Hidden

    private var batteryLevel: Int = 100
    private var isCharging: Boolean = false
    private var chargingWattage: String? = null
    private var activeNotifications: List<StatusBarNotification> = emptyList()
    private var allowedPackages: Set<String>? = null
    
    private val dismissedKeys = mutableSetOf<String>()
    private var currentIndex: Int = 0
    var availableStates: List<MistHubState> = emptyList()
        private set

    private val listeners = mutableListOf<(MistHubState) -> Unit>()
    var invalidateCallback: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = reloadSettings()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            batteryLevel = if (level >= 0) (level * 100 / scale) else batteryLevel

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL

            chargingWattage = "Charging"

            updateState()
        }
    }

    private val mediaListener = MediaSessionManager.OnActiveSessionsChangedListener { _ ->
        updateState()
    }

    fun start() {
        reloadSettings()
        registerSettingsObserver()
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(mediaListener, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register media session listener", e)
        }
        Log.d(TAG, "MistHubController started")
    }

    fun stop() {
        unregisterSettingsObserver()
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { mediaSessionManager.removeOnActiveSessionsChangedListener(mediaListener) } catch (_: Exception) {}
        Log.d(TAG, "MistHubController stopped")
    }

    fun addListener(listener: (MistHubState) -> Unit) {
        listeners += listener
        listener(currentState)
    }

    fun removeListener(listener: (MistHubState) -> Unit) {
        listeners -= listener
    }

    fun onNotificationsChanged(sbns: List<StatusBarNotification>) {
        Log.d(TAG, "onNotificationsChanged: received ${sbns.size} active notifications")
        activeNotifications = sbns
        val activePackages = sbns.map { it.packageName }.toSet()
        val prevSize = dismissedKeys.size
        dismissedKeys.retainAll { key ->
            key == "charging" || key == "media" || activePackages.contains(key)
        }
        if (dismissedKeys.size != prevSize) {
            Log.d(TAG, "Pruned dismissedKeys: now contains ${dismissedKeys.size} items")
        }
        updateState()
    }

    private fun updateState() {
        if (!isEnabled) {
            pushState(MistHubState.Hidden)
            return
        }

        val states = mutableListOf<MistHubState>()

        if (isCharging && showBattery && !dismissedKeys.contains("charging")) {
            states.add(MistHubState.Charging(batteryLevel, chargingWattage))
        }

        val liveUpdates = resolveLiveUpdates()
        states.addAll(liveUpdates.filter { !dismissedKeys.contains(it.packageName) })

        val nowPlaying = resolveNowPlaying()
        if (nowPlaying != null && !dismissedKeys.contains("media")) {
            states.add(nowPlaying)
        }

        val notifications = resolveNotifications()
        states.addAll(notifications.filter { !dismissedKeys.contains(it.packageName) })

        availableStates = states
        Log.d(TAG, "updateState: generated ${availableStates.size} available states")

        if (availableStates.isEmpty()) {
            currentIndex = 0
            pushState(MistHubState.Hidden)
        } else {
            val previousState = this.currentState
            val matchIndex = availableStates.indexOfFirst {
                when {
                    previousState is MistHubState.Charging && it is MistHubState.Charging -> true
                    previousState is MistHubState.NowPlaying && it is MistHubState.NowPlaying -> true
                    previousState is MistHubState.LiveUpdate && it is MistHubState.LiveUpdate -> previousState.packageName == it.packageName
                    previousState is MistHubState.Notification && it is MistHubState.Notification -> previousState.packageName == it.packageName
                    else -> false
                }
            }

            if (matchIndex != -1) {
                currentIndex = matchIndex
            } else {
                if (currentIndex >= availableStates.size) {
                    currentIndex = 0
                } else if (currentIndex < 0) {
                    currentIndex = availableStates.size - 1
                }
            }
            pushState(availableStates[currentIndex])
        }
        invalidateCallback?.invoke()
    }

    fun next() {
        if (availableStates.size <= 1) return
        currentIndex = (currentIndex + 1) % availableStates.size
        Log.d(TAG, "User swiped next: jumping to index $currentIndex")
        pushState(availableStates[currentIndex])
    }

    fun previous() {
        if (availableStates.size <= 1) return
        currentIndex = if (currentIndex - 1 < 0) availableStates.size - 1 else currentIndex - 1
        Log.d(TAG, "User swiped previous: jumping to index $currentIndex")
        pushState(availableStates[currentIndex])
    }

    fun dismissCurrent() {
        val current = currentState
        Log.d(TAG, "User swiped down to dismiss state: $current")
        when (current) {
            is MistHubState.Charging -> dismissedKeys.add("charging")
            is MistHubState.NowPlaying -> dismissedKeys.add("media")
            is MistHubState.LiveUpdate -> dismissedKeys.add(current.packageName)
            is MistHubState.Notification -> dismissedKeys.add(current.packageName)
            else -> return
        }
        updateState()
    }

    private fun pushState(state: MistHubState) {
        if (state == currentState) return
        currentState = state
        Log.d(TAG, "State → $state")
        mainHandler.post { listeners.forEach { it(state) } }
    }

    private fun resolveLiveUpdates(): List<MistHubState.LiveUpdate> {
        val pm = context.packageManager
        return activeNotifications.filter { sbn ->
            LIVE_UPDATE_PACKAGES.contains(sbn.packageName) &&
            (sbn.isOngoing || CLOCK_PACKAGES.contains(sbn.packageName)) &&
            isPackageAllowed(sbn.packageName)
        }.map { sbn ->
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text  = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()  ?: ""
            val progress = extras.getInt(Notification.EXTRA_PROGRESS, -1)
            val progLabel = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val showChrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
            val chronoBase = sbn.notification.`when`
            val chronoDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)

            val icon: Drawable? = try { pm.getApplicationIcon(sbn.packageName) } catch (_: Exception) { null }
            val label: String   = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }
                                  catch (_: Exception) { sbn.packageName }

            val actions = sbn.notification.actions?.toList() ?: emptyList()

            MistHubState.LiveUpdate(
                packageName  = sbn.packageName,
                appLabel     = label,
                appIcon      = icon,
                title        = title,
                text         = text,
                progress     = progress,
                progressLabel= progLabel,
                isOngoing    = sbn.isOngoing,
                showChronometer = showChrono,
                chronometerBase = chronoBase,
                chronometerDown = chronoDown,
                actions      = actions,
                contentIntent= sbn.notification.contentIntent
            )
        }
    }

    private fun resolveNowPlaying(): MistHubState.NowPlaying? {
        val controllers = try {
            mediaSessionManager.getActiveSessions(null)
        } catch (_: Exception) { return null }

        for (ctrl in controllers) {
            val state = ctrl.playbackState ?: continue
            if (state.state != PlaybackState.STATE_PLAYING &&
                state.state != PlaybackState.STATE_PAUSED &&
                state.state != PlaybackState.STATE_BUFFERING) continue
            
            val isPlaying = state.state == PlaybackState.STATE_PLAYING || state.state == PlaybackState.STATE_BUFFERING
            val meta = ctrl.metadata ?: continue
            val title  = meta.getString(MediaMetadata.METADATA_KEY_TITLE)  ?: continue
            val albumArt = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let {
                android.graphics.drawable.BitmapDrawable(context.resources, it)
            }
            return MistHubState.NowPlaying(title, albumArt, isPlaying, ctrl, ctrl.sessionActivity)
        }
        return null
    }

    private fun resolveNotifications(): List<MistHubState.Notification> {
        val pm = context.packageManager
        return activeNotifications.filter { sbn ->
            isPackageAllowed(sbn.packageName) &&
            !LIVE_UPDATE_PACKAGES.contains(sbn.packageName)
        }.groupBy { it.packageName }.map { (pkg, sbns) ->
            val sbn = sbns.first()
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text  = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()  ?: ""
            val icon: Drawable? = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }

            MistHubState.Notification(
                packageName = pkg,
                appIcon     = icon,
                title       = title,
                text        = text,
                count       = sbns.size,
                contentIntent = sbn.notification.contentIntent
            )
        }
    }

    fun isHandledByHub(sbn: StatusBarNotification): Boolean {
        if (dismissedKeys.contains(sbn.packageName)) {
            Log.d(TAG, "isHandledByHub: ${sbn.packageName} is in dismissedKeys -> returning false")
            return false
        }

        val pkg = sbn.packageName
        val isHandled = availableStates.any {
            (it is MistHubState.Notification && it.packageName == pkg)
            || (it is MistHubState.LiveUpdate && it.packageName == pkg)
        }
        return isHandled
    }

    private fun isPackageAllowed(pkg: String): Boolean {
        val allowed = allowedPackages
        if (allowed == null || allowed.isEmpty()) return false
        return allowed.contains(pkg)
    }

    private fun reloadSettings() {
        val cr = context.contentResolver
        val oldWidth = maxWidth
        
        isEnabled = Settings.System.getInt(cr, KEY_ENABLED, 0) == 1
        cornerRadius = Settings.System.getInt(cr, KEY_CORNER_RADIUS, 50)
        edgeGlow = Settings.System.getInt(cr, KEY_EDGE_GLOW, 1) == 1
        animSpeed = Settings.System.getInt(cr, KEY_ANIM_SPEED, 100)
        springAnim = Settings.System.getInt(cr, KEY_SPRING_ANIM, 1) == 1
        pulseOnNotification = Settings.System.getInt(cr, KEY_PULSE, 1) == 1
        showBattery = Settings.System.getInt(cr, KEY_BATTERY, 1) == 1
        showMusicViz = Settings.System.getInt(cr, KEY_MUSIC_VIZ, 0) == 1
        verticalOffset = Settings.System.getInt(cr, KEY_VERTICAL_OFFSET, 16)
        maxWidth = Settings.System.getInt(cr, KEY_MAX_WIDTH, 300)

        val allowedJson = Settings.System.getString(cr, KEY_ALLOWED_APPS)
        allowedPackages = if (allowedJson == null) null
        else try {
            val arr = JSONArray(allowedJson)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Exception) { emptySet() }

        val fontPkg = Settings.System.getString(cr, KEY_FONT_PACKAGE)
        hubTypeface = resolveFontTypeface(fontPkg)

        updateState()
    }

    private fun resolveFontTypeface(pkg: String?): Typeface? {
        if (pkg.isNullOrEmpty() || pkg == "android") return null
        return try {
            val pm = context.packageManager
            val res = pm.getResourcesForApplication(pkg)
            val fontFamily = res.getString(
                res.getIdentifier("config_bodyFontFamily", "string", pkg)
            )
            Typeface.create(fontFamily, Typeface.NORMAL)
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve typeface from $pkg", e)
            null
        }
    }

    private fun registerSettingsObserver() {
        val cr = context.contentResolver
        listOf(KEY_ENABLED, KEY_CORNER_RADIUS, KEY_EDGE_GLOW, KEY_ANIM_SPEED,
               KEY_SPRING_ANIM, KEY_PULSE, KEY_BATTERY, KEY_MUSIC_VIZ,
               KEY_ALLOWED_APPS, KEY_FONT_PACKAGE, KEY_VERTICAL_OFFSET, KEY_MAX_WIDTH)
            .forEach { key ->
                cr.registerContentObserver(
                    Settings.System.getUriFor(key), false, settingsObserver
                )
            }
    }

    private fun unregisterSettingsObserver() {
        context.contentResolver.unregisterContentObserver(settingsObserver)
    }
}
