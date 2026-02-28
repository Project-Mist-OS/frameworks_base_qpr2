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

package com.android.systemui.qs.panels.ui.compose

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.grid.ui.compose.VerticalSpannedGrid
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.flags.QSMaterialExpressiveTiles
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.Tile
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQSPanelStyle
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.QuickQuickSettingsViewModel
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import com.android.systemui.res.R

@Composable
fun ContentScope.QuickQuickSettings(
    viewModel: QuickQuickSettingsViewModel,
    modifier: Modifier = Modifier,
    listening: () -> Boolean,
) {
    val columns = viewModel.columns
    val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val context = androidx.compose.ui.platform.LocalContext.current
    val cr = context.contentResolver

    fun readIosPanelEnabled(): Boolean = try {
        Settings.System.getIntForUser(
            cr, "qs_ios_control_panel", 0, UserHandle.USER_CURRENT
        ) == 1
    } catch (_: Exception) { false }

    var iosPanelEnabled by remember { mutableStateOf(readIosPanelEnabled()) }

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                iosPanelEnabled = readIosPanelEnabled()
            }
        }
        try {
            cr.registerContentObserver(
                Settings.System.getUriFor("qs_ios_control_panel"),
                false, observer, UserHandle.USER_ALL,
            )
        } catch (_: Exception) {}
        onDispose { cr.unregisterContentObserver(observer) }
    }

    val allSizedTiles = viewModel.tileViewModels
    val sizedTiles = if (iosPanelEnabled) {
        allSizedTiles.filter { it.tile.spec.spec !in setOf("internet", "bt") }
    } else {
        allSizedTiles
    }

    val tiles = sizedTiles.fastMap { it.tile }

    val useModifiedSpacing = remember {
        Settings.System.getInt(cr, Settings.System.QS_USE_MODIFIED_TILE_SPACING, 0) == 1
    }
    val classicStyle = rememberQSPanelStyle()

    val bounceables = remember(sizedTiles) { List(sizedTiles.size) { BounceableTileViewModel() } }
    val spans by remember(sizedTiles) { derivedStateOf { sizedTiles.fastMap { it.width } } }

    Box(modifier = modifier) {
        GridAnchor()

        if (QSMaterialExpressiveTiles.isEnabled) {
            ButtonGroupGrid(
                sizedTiles = sizedTiles,
                columns = columns,
                keys = { it.spec },
                elementKey = { it.spec.toElementKey() },
                horizontalPadding = dimensionResource(R.dimen.qs_tile_margin_horizontal),
                modifier = Modifier.sysuiResTag("qqs_tile_layout"),
            ) { sizedTile, interactionSource ->
                Tile(
                    tile = sizedTile.tile,
                    iconOnly = classicStyle || sizedTile.isIcon,
                    squishiness = { squishiness },
                    coroutineScope = scope,
                    tileHapticsViewModelFactoryProvider =
                        viewModel.tileHapticsViewModelFactoryProvider,
                    // There should be no QuickQuickSettings when the details
                    // view is enabled.
                    detailsViewModel = null,
                    isVisible = listening,
                    bounceableInfo = null,
                    interactionSource = interactionSource,
                )
            }
        } else {
            VerticalSpannedGrid(
                columns = columns,
                columnSpacing = if (useModifiedSpacing) {
                    CommonTileDefaults.TileColumnSpacing
                } else {
                    dimensionResource(R.dimen.qs_tile_margin_horizontal)
                },
                rowSpacing = if (useModifiedSpacing) {
                    CommonTileDefaults.TileRowSpacing
                } else {
                    dimensionResource(R.dimen.qs_tile_margin_vertical)
                },
                spans = spans,
                modifier = Modifier.sysuiResTag("qqs_tile_layout")
                    .then(if (useModifiedSpacing) Modifier.padding(horizontal = 10.dp) else Modifier),
                keys = { sizedTiles[it].tile.spec },
            ) { spanIndex, column, isFirstInColumn, isLastInColumn ->
                val it = sizedTiles[spanIndex]
                Element(it.tile.spec.toElementKey(), Modifier) {
                    Tile(
                        tile = it.tile,
                        iconOnly = classicStyle || it.isIcon,
                        squishiness = { squishiness },
                        coroutineScope = scope,
                        bounceableInfo =
                            bounceables.bounceableInfo(
                                it,
                                index = spanIndex,
                                column = column,
                                columns = columns,
                                isFirstInRow = isFirstInColumn,
                                isLastInRow = isLastInColumn,
                            ),
                        tileHapticsViewModelFactoryProvider =
                            viewModel.tileHapticsViewModelFactoryProvider,
                        // There should be no QuickQuickSettings when the details view is enabled.
                        detailsViewModel = null,
                        isVisible = listening,
                        interactionSource = null,
                    )
                }
            }
        }
    }

    TileListener(tiles, listening)
}
