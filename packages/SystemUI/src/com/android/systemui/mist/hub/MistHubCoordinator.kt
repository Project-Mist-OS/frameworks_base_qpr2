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

import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.Coordinator
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import android.util.Log
import javax.inject.Inject

@CoordinatorScope
class MistHubCoordinator @Inject constructor(
    private val mistHubController: MistHubController
) : Coordinator {

    companion object { private const val TAG = "MistHubCoordinator" }

    private lateinit var pipeline: NotifPipeline

    override fun attach(pipeline: NotifPipeline) {
        this.pipeline = pipeline
        pipeline.addPreGroupFilter(mistHubFilter)
        pipeline.addCollectionListener(collectionListener)

        mistHubController.invalidateCallback = {
            Log.d(TAG, "MistHub states updated, invalidating MistHubFilter")
            mistHubFilter.invalidateList("MistHub states updated")
        }
        Log.d(TAG, "MistHubCoordinator attached to pipeline")
    }

    private val mistHubFilter = object : NotifFilter("MistHubFilter") {
        override fun shouldFilterOut(entry: NotificationEntry, now: Long): Boolean {
            return false
        }
    }

    private val collectionListener = object : NotifCollectionListener {
        override fun onEntryAdded(entry: NotificationEntry) {
            updateController()
        }

        override fun onEntryUpdated(entry: NotificationEntry) {
            updateController()
        }

        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
            updateController()
        }

        private fun updateController() {
            val sbns = pipeline.allNotifs.map { it.sbn }
            mistHubController.onNotificationsChanged(sbns.toList())
        }
    }
}

