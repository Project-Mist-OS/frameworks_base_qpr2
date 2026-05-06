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

package com.android.systemui.volume.dialog.oneplus.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import com.android.systemui.dagger.qualifiers.Application
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class OnePlusVolumePanelViewModel @AssistedInject constructor(
    @Application private val context: Context,
    @Assisted private val coroutineScope: CoroutineScope,
) {
    @AssistedFactory
    interface Factory {
        fun create(coroutineScope: CoroutineScope): OnePlusVolumePanelViewModel
    }

    private val _isExpanded = MutableStateFlow(false)
    val isExpanded: StateFlow<Boolean> = _isExpanded.asStateFlow()

    private val _windowBlurEnabled = MutableStateFlow(false)
    val windowBlurEnabled: StateFlow<Boolean> = _windowBlurEnabled.asStateFlow()
    
    private var clearWindowBlurJob: Job? = null

    private val _dismissEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dismissEvent: SharedFlow<Unit> = _dismissEvent.asSharedFlow()

    val expandedStreams: List<Int> = listOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_ALARM,
    )

    fun onExpandClicked() {
        clearWindowBlurJob?.cancel()
        _isExpanded.value = true
        _windowBlurEnabled.value = true
    }

    fun onCollapseRequested() {
        _isExpanded.value = false
        scheduleClearWindowBlurAfterExitAnimation()
    }

    fun collapse() {
        _isExpanded.value = false
        scheduleClearWindowBlurAfterExitAnimation()
    }

    fun onBackPressed(): Boolean {
        if (_isExpanded.value) {
            _isExpanded.value = false
            scheduleClearWindowBlurAfterExitAnimation()
            return true
        }
        return false
    }

    fun onSettingsClicked() {
        clearWindowBlurJob?.cancel()
        _isExpanded.value = false
        _windowBlurEnabled.value = false
        val intent = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        _dismissEvent.tryEmit(Unit)
    }

    fun onDismissRequested() {
        clearWindowBlurJob?.cancel()
        _isExpanded.value = false
        _windowBlurEnabled.value = false
        _dismissEvent.tryEmit(Unit)
    }

    private fun scheduleClearWindowBlurAfterExitAnimation() {
        clearWindowBlurJob?.cancel()
        clearWindowBlurJob = coroutineScope.launch {
            // Match OnePlus [AnimatedContent] exit (fade + scale) approx. duration.
            delay(EXPANDED_EXIT_MS)
            if (!_isExpanded.value) {
                _windowBlurEnabled.value = false
            }
        }
    }

    private companion object {
        private const val EXPANDED_EXIT_MS = 350L
    }
}
