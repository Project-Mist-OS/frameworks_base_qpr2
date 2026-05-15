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

import android.app.PendingIntent
import android.graphics.drawable.Drawable

sealed class MistHubState {

    object Hidden : MistHubState()

    data class Charging(
        val level: Int,
        val wattage: String? = null
    ) : MistHubState()

    data class NowPlaying(
        val title: String,
        val albumArt: Drawable? = null,
        val isPlaying: Boolean = true,
        val mediaController: android.media.session.MediaController? = null,
        val contentIntent: PendingIntent? = null
    ) : MistHubState()

    data class LiveUpdate(
        val packageName: String,
        val appLabel: String,
        val appIcon: Drawable?,
        val title: String,
        val text: String,
        val progress: Int = -1,
        val progressLabel: String = "",
        val isOngoing: Boolean = false,
        val showChronometer: Boolean = false,
        val chronometerBase: Long = 0L,
        val chronometerDown: Boolean = false,
        val actions: List<android.app.Notification.Action> = emptyList(),
        val contentIntent: PendingIntent? = null
    ) : MistHubState()

    data class Notification(
        val packageName: String,
        val appIcon: Drawable?,
        val title: String,
        val text: String,
        val count: Int = 1,
        val contentIntent: PendingIntent? = null
    ) : MistHubState()
}
