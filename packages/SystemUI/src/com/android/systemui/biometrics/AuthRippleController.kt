/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.content.Context
import android.graphics.Point
import android.graphics.PointF
import android.hardware.biometrics.BiometricFingerprintConstants
import android.hardware.biometrics.BiometricSourceType
import android.os.UserHandle
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.keyguard.logging.KeyguardLogger
import com.android.lunaris.waterripple.WaterRippleView
import com.android.settingslib.Utils
import com.android.systemui.CoreStartable
import com.android.systemui.biometrics.data.repository.FacePropertyRepository
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.domain.interactor.AuthRippleInteractor
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.statusbar.CircleReveal
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.ViewController
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Controls two ripple effects:
 * 1. Unlocked ripple: shows when authentication is successful
 * 2. UDFPS dwell ripple: shows when the user has their finger down on the UDFPS area and reacts to
 *    errors and successes
 *
 * The ripple uses the accent color of the current theme.
 */
@SysUISingleton
class AuthRippleController
@Inject
constructor(
    @Main private val sysuiContext: Context,
    private val authController: AuthController,
    @Main private val configurationController: ConfigurationController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val keyguardStateController: KeyguardStateController,
    private val commandRegistry: CommandRegistry,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val udfpsControllerProvider: Provider<UdfpsController>,
    private val statusBarStateController: StatusBarStateController,
    private val displayMetrics: DisplayMetrics,
    private val logger: KeyguardLogger,
    private val authRippleInteractor: AuthRippleInteractor,
    private val facePropertyRepository: FacePropertyRepository,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    rippleView: AuthRippleView?,
) : ViewController<AuthRippleView>(rippleView), CoreStartable {

    var fingerprintSensorLocation: Point? = null
    private var faceSensorLocation: Point? = null
    private var circleReveal: LightRevealEffect? = null

    private var udfpsController: UdfpsController? = null
    private var udfpsRadius: Float = -1f

    private val isRippleEnabled: Boolean
        get() = Settings.System.getIntForUser(context.contentResolver,
            Settings.System.ENABLE_RIPPLE_EFFECT, 1, UserHandle.USER_CURRENT) == 1

    private val unlockAnimation: Int
        get() {
            val legacy = Settings.System.getIntForUser(context.contentResolver,
                Settings.System.LOCKSCREEN_WATER_RIPPLE_ENABLED, 0, UserHandle.USER_CURRENT)
            val selected = Settings.System.getIntForUser(context.contentResolver,
                Settings.System.LOCKSCREEN_UNLOCK_ANIMATION, if (legacy == 1) 1 else 0,
                UserHandle.USER_CURRENT)
            return if (selected in 0..GlowRippleShader.EFFECT_RAINBOW_GLOW) selected else 0
        }

    private val isUnlockEffectHfr: Boolean
        get() = Settings.System.getIntForUser(context.contentResolver,
            Settings.System.LOCKSCREEN_UNLOCK_EFFECT_HFR, 0, UserHandle.USER_CURRENT) == 1

    private val isWaterRippleFullscreen: Boolean
        get() = Settings.System.getIntForUser(context.contentResolver,
            Settings.System.LOCKSCREEN_WATER_RIPPLE_FULLSCREEN, 0, UserHandle.USER_CURRENT) == 1

    override fun start() {
        // The command doesn't depend on the view, so it is always available for testing the ripple
        // with `adb shell cmd statusbar auth-ripple`, even if the view isn't attached.
        commandRegistry.registerCommand("auth-ripple") { AuthRippleCommand() }
        Log.d(
            TAG,
            "start: view=${Integer.toHexString(mView.hashCode())} viewAttached=$isAttachedToWindow",
        )
        init()

        // Listen for unlock events for as long as SystemUI runs. They used to be collected only
        // while the ripple view was attached, which silently stopped the ripple whenever that
        // lifecycle never started. The ripple view itself is only drawn on once it is attached.
        if (!SceneContainerFlag.isEnabled) {
            applicationScope.launch(mainDispatcher) {
                Log.d(TAG, "collecting unlock ripple events")
                authRippleInteractor.showUnlockRipple.collect { biometricUnlockSource ->
                    Log.d(TAG, "unlock ripple event: source=$biometricUnlockSource")
                    if (biometricUnlockSource == BiometricUnlockSource.FINGERPRINT_SENSOR) {
                        showUnlockRippleInternal(BiometricSourceType.FINGERPRINT)
                    } else {
                        showUnlockRippleInternal(BiometricSourceType.FACE)
                    }
                }
            }
        }
    }

    @VisibleForTesting
    public override fun onViewAttached() {
        Log.d(TAG, "onViewAttached")
        authController.addCallback(authControllerCallback)
        updateRippleColor()
        updateUdfpsDependentParams()
        udfpsController?.addCallback(udfpsControllerCallback)
        configurationController.addCallback(configurationChangedListener)
        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
    }

    @VisibleForTesting
    public override fun onViewDetached() {
        Log.d(TAG, "onViewDetached")
        udfpsController?.removeCallback(udfpsControllerCallback)
        authController.removeCallback(authControllerCallback)
        keyguardUpdateMonitor.removeCallback(keyguardUpdateMonitorCallback)
        configurationController.removeCallback(configurationChangedListener)

        mView.cancelUnlockedRipple()
        notificationShadeWindowController.setForcePluginOpen(false, this)
    }

    private fun showUnlockRippleInternal(biometricSourceType: BiometricSourceType) {
        val keyguardNotShowing = !keyguardStateController.isShowing
        val unlockNotAllowed =
            !keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(biometricSourceType)
        if (keyguardNotShowing || unlockNotAllowed || !isRippleEnabled) {
            Log.d(
                TAG,
                "not showing unlock ripple: keyguardNotShowing=$keyguardNotShowing " +
                    "unlockNotAllowed=$unlockNotAllowed rippleEnabled=$isRippleEnabled",
            )
            logger.notShowingUnlockRipple(keyguardNotShowing, unlockNotAllowed)
            return
        }

        updateSensorLocation()
        Log.d(
            TAG,
            "showing unlock ripple: source=$biometricSourceType animation=$unlockAnimation " +
                "fingerprintSensor=$fingerprintSensorLocation faceSensor=$faceSensorLocation",
        )
        if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
            fingerprintSensorLocation?.let {
                mView.setFingerprintSensorLocation(it, udfpsRadius)
                circleReveal =
                    CircleReveal(
                        it.x,
                        it.y,
                        0,
                        Math.max(
                            Math.max(it.x, displayMetrics.widthPixels - it.x),
                            Math.max(it.y, displayMetrics.heightPixels - it.y),
                        ),
                    )
                logger.showingUnlockRippleAt(it.x, it.y, "FP sensor radius: $udfpsRadius")
                showUnlockedRipple()
            }
        } else if (biometricSourceType == BiometricSourceType.FACE) {
            faceSensorLocation?.let {
                mView.setSensorLocation(it)
                circleReveal =
                    CircleReveal(
                        it.x,
                        it.y,
                        0,
                        Math.max(
                            Math.max(it.x, displayMetrics.widthPixels - it.x),
                            Math.max(it.y, displayMetrics.heightPixels - it.y),
                        ),
                    )
                logger.showingUnlockRippleAt(it.x, it.y, "Face unlock ripple")
                showUnlockedRipple()
            }
        }
    }

    private fun showUnlockedRipple(animation: Int = unlockAnimation) {
        if (!isRippleEnabled) return
        notificationShadeWindowController.setForcePluginOpen(true, this)

        Log.d(TAG, "playing unlock animation=$animation")
        val onEnd = Runnable { notificationShadeWindowController.setForcePluginOpen(false, this) }
        if (
            animation == WaterRippleView.EFFECT_WATER ||
                animation == WaterRippleView.EFFECT_WATERCOLOR
        ) {
            mView.startWaterUnlockedRipple(
                onEnd,
                animation,
                highFrameRate = isUnlockEffectHfr,
                fullscreen = isWaterRippleFullscreen,
            )
        } else if (GlowRippleShader.isGlowEffect(animation)) {
            mView.startGlowUnlockedRipple(
                onEnd,
                rainbow = animation == GlowRippleShader.EFFECT_RAINBOW_GLOW,
                highFrameRate = isUnlockEffectHfr,
            )
        } else {
            mView.startUnlockedRipple(onEnd)
        }
    }

    fun updateSensorLocation() {
        fingerprintSensorLocation = authController.fingerprintSensorLocation
        faceSensorLocation = facePropertyRepository.sensorLocation.value
    }

    private fun updateRippleColor() {
        mView.setLockScreenColor(
            Utils.getColorAttrDefaultColor(sysuiContext, R.attr.wallpaperTextColorAccent)
        )
    }

    private fun showDwellRipple() {
        updateSensorLocation()
        fingerprintSensorLocation?.let {
            mView.setFingerprintSensorLocation(it, udfpsRadius)
            mView.startDwellRipple(statusBarStateController.isDozing)
        }
    }

    private val keyguardUpdateMonitorCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onStartedGoingToSleep(why: Int) {
                // Don't leave an unlock effect running over the screen that turned off.
                mView.cancelUnlockedRipple()
            }

            override fun onKeyguardVisibilityChanged(visible: Boolean) {
                // Relocking must not leave the previous unlock effect above the keyguard.
                if (visible) mView.cancelUnlockedRipple()
            }

            override fun onUserSwitching(userId: Int) {
                mView.cancelUnlockedRipple()
            }

            override fun onBiometricAuthenticated(
                userId: Int,
                biometricSourceType: BiometricSourceType,
                isStrongBiometric: Boolean,
            ) {
                if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                    if (SceneContainerFlag.isEnabled) {
                        authRippleInteractor.sendAuthRippleEvent(
                            AuthRippleInteractor.AuthRippleEvent.FadeOut
                        )
                    } else {
                        mView.fadeDwellRipple()
                    }
                }
            }

            override fun onBiometricAuthFailed(biometricSourceType: BiometricSourceType) {
                if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                    if (SceneContainerFlag.isEnabled) {
                        authRippleInteractor.sendAuthRippleEvent(
                            AuthRippleInteractor.AuthRippleEvent.Retract
                        )
                    } else {
                        mView.retractDwellRipple()
                    }
                }
            }

            override fun onBiometricAcquired(
                biometricSourceType: BiometricSourceType,
                acquireInfo: Int,
            ) {
                if (
                    biometricSourceType == BiometricSourceType.FINGERPRINT &&
                        BiometricFingerprintConstants.shouldDisableUdfpsDisplayMode(acquireInfo) &&
                        acquireInfo != BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD
                ) {
                    if (SceneContainerFlag.isEnabled) {
                        authRippleInteractor.sendAuthRippleEvent(
                            AuthRippleInteractor.AuthRippleEvent.Retract
                        )
                    } else {
                        // received an 'acquiredBad' message, so immediately retract
                        mView.retractDwellRipple()
                    }
                }
            }

            override fun onKeyguardBouncerStateChanged(bouncerIsOrWillBeShowing: Boolean) {
                if (bouncerIsOrWillBeShowing) {
                    if (SceneContainerFlag.isEnabled) {
                        authRippleInteractor.sendAuthRippleEvent(
                            AuthRippleInteractor.AuthRippleEvent.FadeOut
                        )
                    } else {
                        mView.fadeDwellRipple()
                    }
                }
            }
        }

    private val configurationChangedListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onUiModeChanged() {
                updateRippleColor()
            }

            override fun onThemeChanged() {
                updateRippleColor()
            }
        }

    private val udfpsControllerCallback =
        object : UdfpsController.Callback {
            override fun onFingerDown() {
                // only show dwell ripple for device entry
                if (keyguardUpdateMonitor.isFingerprintDetectionRunning &&
                        udfpsController?.isAnimationEnabled() == false) {
                    if (SceneContainerFlag.isEnabled) {
                        authRippleInteractor.sendAuthRippleEvent(
                            AuthRippleInteractor.AuthRippleEvent.PulseOut
                        )
                    } else {
                        showDwellRipple()
                    }
                }
            }

            override fun onFingerUp() {
                if (SceneContainerFlag.isEnabled) {
                    authRippleInteractor.sendAuthRippleEvent(
                        AuthRippleInteractor.AuthRippleEvent.Retract
                    )
                } else if (udfpsController?.isAnimationEnabled() == false) {
                    mView.retractDwellRipple()
                }
            }
        }

    private val authControllerCallback =
        object : AuthController.Callback {
            override fun onAllAuthenticatorsRegistered(modality: Int) {
                updateUdfpsDependentParams()
            }

            override fun onUdfpsLocationChanged(udfpsOverlayParams: UdfpsOverlayParams) {
                updateUdfpsDependentParams()
            }
        }

    private fun updateUdfpsDependentParams() {
        authController.udfpsProps?.let {
            if (it.size > 0) {
                udfpsController = udfpsControllerProvider.get()
                udfpsRadius = authController.udfpsRadius

                if (mView.isAttachedToWindow) {
                    udfpsController?.addCallback(udfpsControllerCallback)
                }
            }
        }
    }

    inner class AuthRippleCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            if (args.isEmpty()) {
                invalidCommand(pw)
            } else {
                when (args[0]) {
                    "dwell" -> {
                        if (SceneContainerFlag.isEnabled) {
                            authRippleInteractor.sendAuthRippleEvent(
                                AuthRippleInteractor.AuthRippleEvent.PulseOut
                            )
                            pw.println(
                                "lock screen dwell ripple: " +
                                    "\n\tsensorLocation=${authRippleInteractor.udfpsLocation.value}" +
                                    "\n\tudfpsRadius=${authRippleInteractor.udfpsRadius.value}"
                            )
                        } else {
                            showDwellRipple()
                            pw.println(
                                "lock screen dwell ripple: " +
                                    "\n\tsensorLocation=$fingerprintSensorLocation" +
                                    "\n\tudfpsRadius=$udfpsRadius"
                            )
                        }
                    }

                    "fingerprint" -> {
                        if (SceneContainerFlag.isEnabled) {
                            authRippleInteractor.sendAdbCommand(BiometricSourceType.FINGERPRINT)
                            pw.println(
                                "fingerprint ripple sensorLocation=${authRippleInteractor.sensorOrigin.value}"
                            )
                        } else {
                            pw.println(
                                "fingerprint ripple sensorLocation=$fingerprintSensorLocation"
                            )
                            showUnlockRippleInternal(BiometricSourceType.FINGERPRINT)
                        }
                    }

                    "face" -> {
                        // note: only shows when about to proceed to the home screen
                        if (SceneContainerFlag.isEnabled) {
                            authRippleInteractor.sendAdbCommand(BiometricSourceType.FACE)
                            pw.println(
                                "face ripple sensorLocation=${authRippleInteractor.sensorOrigin.value}"
                            )
                        } else {
                            pw.println("face ripple sensorLocation=$faceSensorLocation")
                            showUnlockRippleInternal(BiometricSourceType.FACE)
                        }
                    }

                    "custom" -> {
                        if (
                            args.size != 3 ||
                                args[1].toFloatOrNull() == null ||
                                args[2].toFloatOrNull() == null
                        ) {
                            invalidCommand(pw)
                            return
                        }
                        pw.println("custom ripple sensorLocation=" + args[1] + ", " + args[2])
                        if (SceneContainerFlag.isEnabled) {
                            authRippleInteractor.setSensorLocation(
                                PointF(args[1].toFloat(), args[2].toFloat())
                            )
                            authRippleInteractor.sendAdbCommand(null)
                        } else {
                            mView.setSensorLocation(Point(args[1].toInt(), args[2].toInt()))
                            showUnlockedRipple()
                        }
                    }

                    "preview" -> {
                        val style = args.getOrNull(1)?.toIntOrNull()
                        val x = args.getOrNull(2)?.toIntOrNull()
                        val y = args.getOrNull(3)?.toIntOrNull()
                        if (
                            (args.size != 2 && args.size != 4) ||
                                style == null ||
                                style !in 0..GlowRippleShader.EFFECT_RAINBOW_GLOW ||
                                (args.size == 4 && (x == null || y == null))
                        ) {
                            invalidCommand(pw)
                            return
                        }
                        if (SceneContainerFlag.isEnabled) {
                            pw.println("preview is not supported with the scene container")
                            return
                        }
                        updateSensorLocation()
                        val location =
                            if (x != null && y != null) {
                                Point(x, y)
                            } else {
                                fingerprintSensorLocation
                                    ?: Point(
                                        displayMetrics.widthPixels / 2,
                                        displayMetrics.heightPixels * 4 / 5,
                                    )
                            }
                        mView.setSensorLocation(location)
                        pw.println("preview style=$style sensorLocation=$location")
                        showUnlockedRipple(style)
                    }

                    "status" -> dumpViewStatus(pw)

                    else -> invalidCommand(pw)
                }
            }
        }

        private fun dumpViewStatus(pw: PrintWriter) {
            pw.println("sceneContainer=${SceneContainerFlag.isEnabled}")
            var view: android.view.View? = mView
            var depth = 0
            while (view != null && depth < 12) {
                pw.println(
                    "  ".repeat(depth) +
                        "${view.javaClass.simpleName}@${Integer.toHexString(view.hashCode())} " +
                        "attached=${view.isAttachedToWindow} visibility=${view.visibility} " +
                        "size=${view.width}x${view.height} " +
                        "windowToken=${view.windowToken != null} " +
                        "hwAccelerated=${view.isHardwareAccelerated}"
                )
                view = view.parent as? android.view.View
                depth++
            }
            pw.println("rootView=${mView.rootView.javaClass.simpleName}")
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar auth-ripple <command>")
            pw.println("Available commands:")
            pw.println("  dwell")
            pw.println("  fingerprint")
            pw.println("  face")
            pw.println("  custom <x-location: int> <y-location: int>")
            pw.println("  preview <style: 0..4> [x-location: int y-location: int]")
            pw.println("    0=default, 1=water, 2=watercolor, 3=glow wave, 4=rainbow glow")
            pw.println("  status")
        }

        private fun invalidCommand(pw: PrintWriter) {
            pw.println("invalid command")
            help(pw)
        }
    }

    companion object {
        const val RIPPLE_ANIMATION_DURATION: Long = 800
        const val TAG = "AuthRippleController"
    }
}
