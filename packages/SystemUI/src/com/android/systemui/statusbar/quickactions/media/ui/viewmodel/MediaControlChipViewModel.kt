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

package com.android.systemui.statusbar.quickactions.media.ui.viewmodel

import androidx.compose.runtime.getValue
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.media.controls.shared.MediaSeekBarStyle
import com.android.systemui.media.dialog.MediaOutputDialogManager
import com.android.systemui.statusbar.quickactions.media.domain.interactor.MediaControlChipInteractor
import com.android.systemui.statusbar.quickactions.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.quickactions.shared.model.ChipContent
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.ui.compose.ChipColors
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.StatusBarPopupChipViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.Locale
import kotlinx.coroutines.flow.combine

/**
 * [StatusBarPopupChipViewModel] for a media control chip in the status bar. This view model is
 * responsible for converting the [MediaControlChipModel] to a [QuickActionChipModel] that can be used to
 * display a media control chip.
 */
class MediaControlChipViewModel
@AssistedInject
constructor(
    mediaControlChipInteractor: MediaControlChipInteractor,
    private val mediaOutputDialogManager: MediaOutputDialogManager,
) : StatusBarPopupChipViewModel, ExclusiveActivatable() {
    private val hydrator: Hydrator = Hydrator("MediaControlChipViewModel.hydrator")
    /**
     * A snapshot [State] of the current [QuickActionChipModel]. This emits a new [QuickActionChipModel]
     * whenever the underlying [MediaControlChipModel] changes.
     */
    override val chip: QuickActionChipModel by
        hydrator.hydratedStateOf(
            traceName = "chip",
            initialValue = QuickActionChipModel.Hidden(QuickActionChipId.MediaControl),
            source =
                combine(
                    mediaControlChipInteractor.mediaControlChipModel,
                    mediaControlChipInteractor.mediaSeekBarStyle,
                    mediaControlChipInteractor.useRedesignedMedia,
                ) { model, seekBarStyle, useRedesignedMedia ->
                    toPopupChipModel(model, seekBarStyle, useRedesignedMedia)
                },
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    private fun toPopupChipModel(
        model: MediaControlChipModel?,
        seekBarStyle: MediaSeekBarStyle,
        useRedesignedMedia: Boolean,
    ): QuickActionChipModel {
        if (model == null || model.songName.isNullOrEmpty()) {
            return QuickActionChipModel.Hidden(QuickActionChipId.MediaControl)
        }

        val contentDescription = model.appName?.let { ContentDescription.Loaded(description = it) }
        val defaultIcon =
            model.artworkIcon
                ?: model.appIcon
                ?: Icon.Resource(
                    resId = com.android.internal.R.drawable.ic_audio_media,
                    contentDescription = contentDescription,
                )
        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.MediaControl,
            icons = listOf(ChipIcon(icon = defaultIcon)),
            chipContent =
                ChipContent.Text(
                    normalizeSongTitle(model.songName.toString(), model.artistName?.toString())
                ),
            colors = ChipColors.DynamicIsland,
            popupContent =
                PopupContentModel.Media(
                    model.copy(
                        openOutput =
                            model.packageName?.let { packageName ->
                                {
                                    mediaOutputDialogManager.createAndShow(
                                        packageName = packageName,
                                        aboveStatusBar = true,
                                        userHandle =
                                            model.sourceUserId?.let { android.os.UserHandle.of(it) },
                                    )
                                }
                            }
                    ),
                    seekBarStyle,
                    useRedesignedMedia,
                ),
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): MediaControlChipViewModel
    }

    private fun normalizeSongTitle(title: String, artist: String?): String {
        if (title.isBlank()) return title
        val separatorRegex = Regex("\\s*[-–—|•]\\s*")
        val parts = title.split(separatorRegex, limit = 2)
        if (parts.size != 2) return title

        val left = parts[0].trim()
        val right = parts[1].trim()
        if (left.isBlank() || right.isBlank()) return title

        val artistKey = artist?.toComparableKey() ?: return title
        val leftKey = left.toComparableKey()
        val rightKey = right.toComparableKey()

        return when {
            leftKey.contains(artistKey) -> right
            rightKey.contains(artistKey) -> left
            else -> title
        }
    }

    private fun String.toComparableKey(): String {
        return lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}
