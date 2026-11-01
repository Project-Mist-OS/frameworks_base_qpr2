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

package com.android.systemui.statusbar.quickactions.popups.ui.viewmodel

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.android.internal.util.mist.MistBarSettings
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.StatusBarVisibilityInteractor
import com.android.systemui.statusbar.quickactions.alarm.ui.viewmodel.AlarmPopupChipViewModel
import com.android.systemui.statusbar.quickactions.assistant.StatusBarAssistantIcon
import com.android.systemui.statusbar.quickactions.assistant.ui.viewmodel.AssistantIconViewModel
import com.android.systemui.statusbar.quickactions.av.ui.viewmodel.AvControlsChipViewModel
import com.android.systemui.statusbar.quickactions.domain.interactor.QuickActionsInteractor
import com.android.systemui.statusbar.quickactions.flashlight.ui.viewmodel.FlashlightPopupChipViewModel
import com.android.systemui.statusbar.quickactions.ime.ui.viewmodel.ImeIndicatorChipViewModel
import com.android.systemui.statusbar.quickactions.livescore.ui.viewmodel.LiveScorePopupChipViewModel
import com.android.systemui.statusbar.quickactions.media.ui.viewmodel.MediaControlChipViewModel
import com.android.systemui.statusbar.quickactions.popups.StatusBarPopupChips
import com.android.systemui.statusbar.quickactions.popups.data.repository.DynamicIslandState
import com.android.systemui.statusbar.quickactions.popups.data.repository.MistBarForegroundApp
import com.android.systemui.statusbar.quickactions.popups.data.repository.observeMistBarForegroundApp
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.CALLS
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.LOCKSCREEN
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandEnabled
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.readDynamicIslandEnabled
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.readDynamicIslandFeatureEnabled
import com.android.systemui.statusbar.quickactions.ui.compose.observeMistBarState
import com.android.systemui.statusbar.quickactions.ui.compose.readMistBarState
import com.android.systemui.statusbar.quickactions.shared.model.ChipContent
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionPanelModel
import com.android.systemui.statusbar.quickactions.screenrecord.ui.viewmodel.LargeScreenRecordingChipViewModel
import com.android.systemui.statusbar.quickactions.screenrecord.ui.viewmodel.ScreenRecordPopupChipViewModel
import com.android.systemui.statusbar.quickactions.sharescreen.ui.viewmodel.ShareScreenPrivacyIndicatorViewModel
import com.android.systemui.statusbar.quickactions.stopwatch.ui.viewmodel.StopwatchPopupChipViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * View model deciding which system process chips to show in the status bar. Emits a list of
 * PopupChipModels.
 */
class StatusBarPopupChipsViewModel
@AssistedInject
constructor(
    @Assisted private val displayId: Int,
    @Application private val context: Context,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val userTracker: UserTracker,
    private val quickActionsInteractor: QuickActionsInteractor,
    private val statusBarVisibilityInteractor: StatusBarVisibilityInteractor,
    systemEventChipsFactory: SystemEventPopupChipsViewModel.Factory,
    mediaControlChipFactory: MediaControlChipViewModel.Factory,
    screenRecordChipFactory: ScreenRecordPopupChipViewModel.Factory,
    liveScoreChipFactory: LiveScorePopupChipViewModel.Factory,
    flashlightChipFactory: FlashlightPopupChipViewModel.Factory,
    stopwatchChipFactory: StopwatchPopupChipViewModel.Factory,
    alarmChipFactory: AlarmPopupChipViewModel.Factory,
    avControlsChipFactory: AvControlsChipViewModel.Factory,
    shareScreenPrivacyIndicatorFactory: ShareScreenPrivacyIndicatorViewModel.Factory,
    assistantIconFactory: AssistantIconViewModel.Factory,
    imeIndicatorChipFactory: ImeIndicatorChipViewModel.Factory,
    largeScreenRecordingChipViewModelFactory: LargeScreenRecordingChipViewModel.Factory,
) : HydratedActivatable() {

    private val systemEventChips by lazy { systemEventChipsFactory.create() }
    private val mediaControlChip by lazy { mediaControlChipFactory.create() }
    private val screenRecordChip by lazy { screenRecordChipFactory.create() }
    private val liveScoreChip by lazy { liveScoreChipFactory.create() }
    private val flashlightChip by lazy { flashlightChipFactory.create() }
    private val stopwatchChip by lazy { stopwatchChipFactory.create() }
    private val alarmChip by lazy { alarmChipFactory.create() }
    private val avControlsChip by lazy { avControlsChipFactory.create() }
    private val shareScreenPrivacyIndicator by lazy { shareScreenPrivacyIndicatorFactory.create() }
    private val assistantIcon by lazy { assistantIconFactory.create() }
    private val imeIndicatorChip by lazy { imeIndicatorChipFactory.create(displayId) }
    private val largeScreenRecordingChip by lazy {
        largeScreenRecordingChipViewModelFactory.create()
    }
    private var isDynamicIslandEnabled by mutableStateOf(readDynamicIslandEnabled(context))
    private var foregroundApp by mutableStateOf<MistBarForegroundApp?>(null)
    private var mediaHiddenAfterPause by mutableStateOf(false)
    private var mistBarState by mutableStateOf(readMistBarState(context))
    private var isCallsEnabled by
        mutableStateOf(context.contentResolver.readDynamicIslandFeatureEnabled(CALLS, false))
    private var isLockscreenEnabled by
        mutableStateOf(
            context.contentResolver.readDynamicIslandFeatureEnabled(
                LOCKSCREEN,
                defaultValue = false,
            )
        )
    private val dynamicIslandObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                isDynamicIslandEnabled = readDynamicIslandEnabled(context)
                isCallsEnabled =
                    context.contentResolver.readDynamicIslandFeatureEnabled(CALLS, false)
                isLockscreenEnabled =
                    context.contentResolver.readDynamicIslandFeatureEnabled(
                        LOCKSCREEN,
                        defaultValue = false,
                    )
                if (!isDynamicIslandEnabled || (isOnLockscreen && !isLockscreenEnabled)) {
                    showPopup(null)
                }
            }
        }
    private val userChangedCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                showPopup(null)
                foregroundApp = null
                dynamicIslandObserver.onChange(false)
            }
        }

    private var isOnLockscreen by
        mutableStateOf(
            keyguardTransitionInteractor.currentKeyguardState.value == KeyguardState.LOCKSCREEN
        )
    private var isShadeVisible by
        mutableStateOf(
            (shadeInteractor.isAnyExpanded.value && !isOnLockscreen) ||
                shadeInteractor.anyExpansion.value > 0f ||
                shadeInteractor.isUserInteracting.value
        )
    private var isReadyForAutoPopup by mutableStateOf(false)
    private var autoPopupJob: Job? = null

    private fun showPopup(id: QuickActionChipId?) {
        if (
            id != null &&
                (!isDynamicIslandEnabled ||
                    isShadeVisible ||
                    (isOnLockscreen && !isLockscreenEnabled))
        ) {
            return
        }
        autoPopupJob?.cancel()
        autoPopupJob = null
        if (currentShownPopupChipId != id) {
            systemEventChips.chips
                .firstOrNull { it.chipId == currentShownPopupChipId }
                ?.onPopupHidden
                ?.invoke()
        }
        currentShownPopupChipId = id
        systemEventChips.chips.firstOrNull { it.chipId == id }?.onPopupShown?.invoke()
    }

    private val isShadeWindowOnThisDisplay by
        statusBarVisibilityInteractor.isShadeWindowOnThisDisplay.hydratedStateOf(
            traceName = "isShadeWindowOnThisDisplay"
        )

    /** The ID of the quick action panel that is currently open, or `null` if none is. */
    private val currentActiveQuickActionId: QuickActionChipId?
        get() = quickActionsInteractor.activePanel?.chipId.takeIf { isShadeWindowOnThisDisplay }

    /** The ID of the current chip that is showing its popup, or `null` if no chip is shown. */
    private var currentShownPopupChipId by mutableStateOf<QuickActionChipId?>(null)

    private val incomingPopupChipBundle: PopupChipBundle by derivedStateOf {
        PopupChipBundle(
            media = mediaControlChip.chip,
            screenRecord = screenRecordChip.chip,
            liveScore = liveScoreChip.chip,
            flashlight = flashlightChip.chip,
            stopwatch = stopwatchChip.chip,
            alarm = alarmChip.chip,
            privacy = avControlsChip.chip,
            shareScreen = shareScreenPrivacyIndicator.chip,
            assistant = assistantIcon.chip,
            ime = imeIndicatorChip.chip,
            largeScreenRecording = largeScreenRecordingChip.chip,
        )
    }

    val shownQuickActionChips: List<QuickActionChipModel>
        get() {
            if (!isDynamicIslandEnabled || isOnLockscreen) return emptyList()
            val bundle = incomingPopupChipBundle
            return visiblePopupChips +
                (if (StatusBarPopupChips.isEnabled) {
                    listOf(bundle.assistant, bundle.ime).filter { it !is QuickActionChipModel.Hidden }
                } else {
                    emptyList()
                })
        }

    val mediaTransitionChip: QuickActionChipModel.PopupChip?
        get() {
            if (!isDynamicIslandEnabled || !isReadyForAutoPopup || isOnLockscreen || isShadeVisible) {
                return null
            }
            val settings =
                mistBarState.settings.forScreen(
                    mistBarState.screenWidth,
                    mistBarState.screenHeight,
                )
            if (settings.isReducedMotion || !settings.enabled("media_mini", true)) return null
            val chip = mediaControlChip.chip as? QuickActionChipModel.PopupChip ?: return null
            val media = (chip.popupContent as? PopupContentModel.Media)?.model ?: return null
            return chip.takeIf { media.isPlaying && (media.sourceUserId ?: -1) >= 0 }
        }

    val lockscreenPopupChips: List<QuickActionChipModel.PopupChip>
        get() = if (isOnLockscreen) visiblePopupChips else emptyList()

    internal val dynamicIslandState: DynamicIslandState
        get() =
            DynamicIslandState(
                canShowCalls =
                    isDynamicIslandEnabled &&
                        isCallsEnabled &&
                        !isShadeVisible &&
                        (isReadyForAutoPopup || (isOnLockscreen && isLockscreenEnabled)),
                callNotificationKeys =
                    visiblePopupChips
                        .mapNotNull { chip ->
                            (chip.chipId as? QuickActionChipId.SystemEvent)
                                ?.eventId
                                ?.takeIf { it.startsWith("call_") }
                                ?.removePrefix("call_")
                        }
                        .toSet(),
                isScreenRecordingShown =
                    visiblePopupChips.any { it.chipId == QuickActionChipId.ScreenRecord },
            )

    private fun hiddenByForeground(chip: QuickActionChipModel.PopupChip): Boolean {
        if (isOnLockscreen) return false
        val foreground = foregroundApp ?: return false
        val media = (chip.popupContent as? PopupContentModel.Media)?.model
        val packageName = chip.sourcePackageName ?: media?.packageName ?: return false
        val userId = chip.sourceUserId ?: media?.sourceUserId ?: userTracker.userId
        return packageName == foreground.packageName &&
            (userId == foreground.userId || userId == UserHandle.USER_ALL)
    }

    private val visiblePopupChips: List<QuickActionChipModel.PopupChip> by derivedStateOf {
        if (!isDynamicIslandEnabled || (isOnLockscreen && !isLockscreenEnabled) || isShadeVisible) {
            return@derivedStateOf emptyList()
        }

        val bundle = incomingPopupChipBundle
        val candidateChips =
            if (StatusBarPopupChips.isEnabled) {
                listOfNotNull(
                    bundle.media,
                    bundle.screenRecord,
                    bundle.liveScore,
                    bundle.stopwatch,
                    bundle.alarm,
                    bundle.flashlight,
                    bundle.privacy,
                    bundle.shareScreen,
                )
            } else {
                listOfNotNull(
                    bundle.media,
                    bundle.screenRecord,
                    bundle.liveScore,
                    bundle.stopwatch,
                    bundle.alarm,
                    bundle.flashlight,
                )
            }

        val activeChips =
            (systemEventChips.chips +
                    candidateChips.filterIsInstance<QuickActionChipModel.PopupChip>().filterNot {
                        it.chipId == QuickActionChipId.MediaControl && mediaHiddenAfterPause
                    })
                .filterNot(::hiddenByForeground)
        val settings =
            mistBarState.settings.forScreen(
                mistBarState.screenWidth,
                mistBarState.screenHeight,
            )
        val utilityCards =
            settings.cards
                .filter { it.isEnabled && (!isOnLockscreen || it.isAllowedOnLockscreen) }
                .ifEmpty {
                    if (settings.isAlwaysVisible && !isOnLockscreen && activeChips.isEmpty()) {
                        listOf(
                            MistBarSettings.Card.create("shortcuts", "MistBar")
                                .with("id", "mistbar_home")
                                .with(
                                    "actions",
                                    "[{\"label\":\"MistBar\",\"action\":\"settings\"}]",
                                )
                        )
                    } else emptyList()
                }
        val utilities =
            utilityCards.map { card ->
                QuickActionChipModel.PopupChip(
                    chipId = QuickActionChipId.Utility(card.id),
                    icons = listOf(ChipIcon(Icon.Resource(android.R.drawable.ic_menu_view, null))),
                    chipContent = ChipContent.Text(card.title),
                    contentDescription = ContentDescription.Loaded(card.title),
                    popupContent = PopupContentModel.Utility(card),
                )
            }
        (activeChips + utilities).map { chip ->
            chip.copy(
                isPopupShown =
                    chip.chipId == currentShownPopupChipId || chip.chipId == currentActiveQuickActionId,
                showPopup = { showPopup(chip.chipId) },
                hidePopup = {
                    if (currentShownPopupChipId == chip.chipId) showPopup(null)
                    quickActionsInteractor.close()
                },
                togglePopup = { _, anchorBounds ->
                    chip.popupViewModelFactory?.let { factory ->
                        quickActionsInteractor.toggle(
                            QuickActionPanelModel(
                                chipId = chip.chipId,
                                anchorBounds = anchorBounds,
                                panelContentViewModelFactory = factory,
                            )
                        )
                    } ?: showPopup(if (currentShownPopupChipId == chip.chipId) null else chip.chipId)
                },
            )
        }
    }

    override suspend fun onActivated() {
        coroutineScope {
            launch { observeMistBarForegroundApp().collect { foregroundApp = it } }
            launch {
                snapshotFlow { visiblePopupChips.map { it.chipId } }
                    .collect { ids ->
                        if (currentShownPopupChipId != null && currentShownPopupChipId !in ids)
                            showPopup(null)
                    }
            }
            launch {
                observeMistBarState(context).collect { state ->
                    val previous = mistBarState
                    if (previous.userId != state.userId) showPopup(null)
                    mistBarState = state
                    if (previous.userId == state.userId) {
                        val remaining =
                            state.settings.cards
                                .filter { it.type == "widget" }
                                .map { it.widgetId }
                                .toSet()
                        val removed =
                            previous.settings.cards.filter {
                                it.type == "widget" && it.widgetId >= 0 && it.widgetId !in remaining
                            }
                        if (removed.isNotEmpty()) {
                            val userContext =
                                context.createContextAsUser(UserHandle.of(state.userId), 0)
                            val host =
                                android.appwidget.AppWidgetHost(
                                    userContext,
                                    MistBarSettings.WIDGET_HOST_ID,
                                )
                            removed.forEach { runCatching { host.deleteAppWidgetId(it.widgetId) } }
                        }
                    }
                    val current = currentShownPopupChipId as? QuickActionChipId.Utility
                    if (
                        current != null &&
                            state.settings.cards.none { it.id == current.cardId && it.isEnabled }
                    )
                        showPopup(null)
                }
            }
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(CALLS),
                false,
                dynamicIslandObserver,
                UserHandle.USER_ALL,
            )
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(LOCKSCREEN),
                false,
                dynamicIslandObserver,
                UserHandle.USER_ALL,
            )
            userTracker.addCallback(userChangedCallback, context.mainExecutor)
            dynamicIslandObserver.onChange(false)
            launch {
                observeDynamicIslandEnabled(context).collect { enabled ->
                    isDynamicIslandEnabled = enabled
                    if (!enabled) showPopup(null)
                }
            }
            launch {
                combine(
                        shadeInteractor.isAnyExpanded,
                        shadeInteractor.anyExpansion,
                        shadeInteractor.isUserInteracting,
                        keyguardTransitionInteractor.isFinishedIn(KeyguardState.LOCKSCREEN),
                    ) { expanded, expansion, interacting, onLockscreen ->
                        (expanded && !onLockscreen) || expansion > 0f || interacting
                    }
                    .distinctUntilChanged()
                    .collect { visible ->
                        isShadeVisible = visible
                        if (visible) showPopup(null)
                    }
            }
            launch {
                keyguardTransitionInteractor.isFinishedIn(KeyguardState.LOCKSCREEN).collectLatest {
                    if (isOnLockscreen != it) showPopup(null)
                    isOnLockscreen = it
                }
            }
            launch {
                keyguardTransitionInteractor.isFinishedIn(KeyguardState.GONE).collectLatest {
                    isReadyForAutoPopup = it
                }
            }
            launch {
                val consumedRequests = mutableMapOf<QuickActionChipId, Long>()
                snapshotFlow {
                        Triple(
                            systemEventChips.chips.map { it to hiddenByForeground(it) },
                            isDynamicIslandEnabled,
                            (isReadyForAutoPopup || (isOnLockscreen && isLockscreenEnabled)) &&
                                !isShadeVisible,
                        )
                    }
                    .collect { (candidates, enabled, canAutoPopup) ->
                        val chips = candidates.map { it.first }
                        val ids = chips.map { it.chipId }.toSet()
                        consumedRequests.keys.retainAll(ids)
                        if (!enabled || !canAutoPopup) {
                            showPopup(null)
                            return@collect
                        }
                        if (
                            currentShownPopupChipId is QuickActionChipId.SystemEvent &&
                                currentShownPopupChipId !in ids
                        ) {
                            showPopup(null)
                        }
                        val requests =
                            chips.filter { chip ->
                                chip.autoPopupRequest != null &&
                                    consumedRequests[chip.chipId] != chip.autoPopupRequest
                            }
                        requests.forEach { consumedRequests[it.chipId] = it.autoPopupRequest!! }
                        requests
                            .firstOrNull { !hiddenByForeground(it) }
                            ?.let { chip ->
                                showPopup(chip.chipId)
                                chip.onAutoPopupShown()
                                if (chip.autoPopupDurationMs > 0L)
                                    autoPopupJob = launch {
                                        delay(chip.autoPopupDurationMs)
                                        if (currentShownPopupChipId == chip.chipId) {
                                            showPopup(null)
                                        }
                                    }
                            }
                    }
            }
            launch { systemEventChips.activate() }
            launch { avControlsChip.activate() }
            launch { mediaControlChip.activate() }
            launch {
                snapshotFlow {
                        val media =
                            (mediaControlChip.chip as? QuickActionChipModel.PopupChip)?.popupContent
                                as? PopupContentModel.Media
                        Triple(
                            media?.model?.isPlaying,
                            mistBarState.settings.enabled("media_hide_paused", false),
                            mistBarState.settings.integer("media_hide_delay", 0, 0, 300),
                        )
                    }
                    .collectLatest { (playing, hide, seconds) ->
                        mediaHiddenAfterPause = false
                        if (playing == false && hide) {
                            delay(seconds * 1000L)
                            if (currentShownPopupChipId == QuickActionChipId.MediaControl) showPopup(null)
                            mediaHiddenAfterPause = true
                        }
                    }
            }
            launch { screenRecordChip.activate() }
            launch { liveScoreChip.activate() }
            launch { flashlightChip.activate() }
            launch { stopwatchChip.activate() }
            launch { alarmChip.activate() }
            launch { shareScreenPrivacyIndicator.activate() }
            launch { largeScreenRecordingChip.activate() }
            if (StatusBarAssistantIcon.isEnabled) {
                launch { assistantIcon.activate() }
            }
            launch { imeIndicatorChip.activate() }

            // Close A17 quick action panels whose chip disappeared.
            launch {
                snapshotFlow {
                        val activeId = currentActiveQuickActionId ?: return@snapshotFlow false
                        incomingPopupChipBundle.asList.find { it.chipId == activeId } is
                            QuickActionChipModel.Hidden
                    }
                    .filter { isHidden -> isHidden }
                    .collect { quickActionsInteractor.close() }
            }
            try {
                awaitCancellation()
            } finally {
                showPopup(null)
                userTracker.removeCallback(userChangedCallback)
                context.contentResolver.unregisterContentObserver(dynamicIslandObserver)
            }
        }
    }

    private data class PopupChipBundle(
        val media: QuickActionChipModel = QuickActionChipModel.Hidden(chipId = QuickActionChipId.MediaControl),
        val screenRecord: QuickActionChipModel = QuickActionChipModel.Hidden(chipId = QuickActionChipId.ScreenRecord),
        val liveScore: QuickActionChipModel = QuickActionChipModel.Hidden(chipId = QuickActionChipId.LiveScore),
        val flashlight: QuickActionChipModel = QuickActionChipModel.Hidden(chipId = QuickActionChipId.Flashlight),
        val stopwatch: QuickActionChipModel = QuickActionChipModel.Hidden(chipId = QuickActionChipId.Stopwatch),
        val alarm: QuickActionChipModel = QuickActionChipModel.Hidden(chipId = QuickActionChipId.Alarm),
        val privacy: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.AvControlsIndicator),
        val shareScreen: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.ShareScreenPrivacyIndicator),
        val assistant: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.AssistantIcon),
        val ime: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.ImeIndicator),
        val largeScreenRecording: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.ScreenRecording),
    ) {
        val asList: List<QuickActionChipModel>
            get() =
                listOf(
                    media, screenRecord, liveScore, flashlight, stopwatch, alarm, privacy,
                    shareScreen, assistant, ime, largeScreenRecording,
                )
    }

    @AssistedFactory
    interface Factory {
        fun create(displayId: Int): StatusBarPopupChipsViewModel
    }
}
