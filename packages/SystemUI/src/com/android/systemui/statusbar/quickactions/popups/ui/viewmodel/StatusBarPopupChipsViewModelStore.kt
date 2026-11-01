/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.statusbar.quickactions.popups.ui.viewmodel

import androidx.compose.runtime.snapshotFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.quickactions.popups.data.repository.DynamicIslandStateRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Shares one [StatusBarPopupChipsViewModel] per display between the home status bar and the
 * lockscreen status bar.
 *
 * The view model depends on per display bindings, so it must be built in the display's own
 * dagger scope. The home status bar view model of each display creates it through [getOrCreate];
 * other users (the keyguard status bar) look it up with [forDisplay].
 */
@SysUISingleton
class StatusBarPopupChipsViewModelStore
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val dynamicIslandStateRepository: DynamicIslandStateRepository,
) {
    private val viewModels = mutableMapOf<Int, StatusBarPopupChipsViewModel>()

    /** The view model of [displayId] if that display's status bar already created it. */
    @Synchronized
    fun forDisplay(displayId: Int): StatusBarPopupChipsViewModel? = viewModels[displayId]

    /** Returns the view model of [displayId], building it with [create] the first time. */
    @Synchronized
    fun getOrCreate(
        displayId: Int,
        create: () -> StatusBarPopupChipsViewModel,
    ): StatusBarPopupChipsViewModel {
        return viewModels.getOrPut(displayId) {
            create().also { model ->
                applicationScope.launch {
                    try {
                        coroutineScope {
                            launch { model.activate() }
                            snapshotFlow { model.dynamicIslandState }.collect {
                                dynamicIslandStateRepository.setState(displayId, it)
                            }
                        }
                    } finally {
                        dynamicIslandStateRepository.removeDisplay(displayId)
                    }
                }
            }
        }
    }
}
