package com.android.systemui.axdynamicbar.data.source

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.user.utils.UserScopedService
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/** AxDynamicBar owns a separate session of the shared event source. */
@SysUISingleton
class SystemIslandManager @Inject constructor(
    @Application context: Context,
    @Application applicationScope: CoroutineScope,
    @Background backgroundDispatcher: CoroutineDispatcher,
    @Main mainHandler: Handler,
    batteryInteractor: BatteryInteractor,
    batteryController: BatteryController,
    userTracker: UserTracker,
    clipboardManagerProvider: UserScopedService<ClipboardManager>,
) :
    com.android.systemui.islandevents.data.source.SystemIslandManager(
        context,
        applicationScope,
        backgroundDispatcher,
        mainHandler,
        batteryInteractor,
        batteryController,
        userTracker,
        clipboardManagerProvider,
    )
