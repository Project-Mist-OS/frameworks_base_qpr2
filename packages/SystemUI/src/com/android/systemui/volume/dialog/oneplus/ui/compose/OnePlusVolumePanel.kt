/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.volume.dialog.oneplus.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.android.systemui.volume.dialog.oneplus.ui.viewmodel.OnePlusVolumePanelViewModel

@Composable
fun OnePlusVolumePanel(
    viewModel: OnePlusVolumePanelViewModel,
    isOnLeft: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isExpanded by viewModel.isExpanded.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = isExpanded,
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.95f))
                    .togetherWith(fadeOut() + scaleOut(targetScale = 0.95f))
            },
            label = "oneplus_volume_panel",
        ) { expanded ->
            if (expanded) {
                OnePlusExpandedPanel(
                    streams = viewModel.expandedStreams,
                    onSettingsClicked = { viewModel.onSettingsClicked() },
                    onDismiss = { viewModel.onCollapseRequested() },
                )
            } else {
                Box(
                    contentAlignment = if (isOnLeft) Alignment.CenterStart else Alignment.CenterEnd,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onDismissRequest() },
                ) {
                    OnePlusCollapsedSlider(
                        onExpandClicked = { viewModel.onExpandClicked() },
                        isOnLeft = isOnLeft,
                    )
                }
            }
        }
    }
}
