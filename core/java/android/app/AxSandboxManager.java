/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.internal.app.HiddenNotificationInfo;
import com.android.internal.app.IAppLockStateListener;
import com.android.internal.app.IAppSessionListener;
import com.android.internal.app.IHiddenNotificationListener;

import java.util.Collections;
import java.util.List;

/**
 * @hide
 */
@SystemService(Context.AX_SANDBOX_SERVICE)
public class AxSandboxManager {

    /** @hide */
    public static final int LOCK_BEHAVIOR_ON_LEAVE = 0;
    /** @hide */
    public static final int LOCK_BEHAVIOR_TIMEOUT = 1;
    /** @hide */
    public static final int LOCK_BEHAVIOR_ON_SCREEN_OFF = 2;
    /** @hide */
    public static final int LOCK_BEHAVIOR_ON_KILL = 3;

    /** @hide */
    public static final String SETTING_LOCK_BEHAVIOR = "sandbox_locked_app_behavior";
    /** @hide */
    public static final String SETTING_LOCK_TIMEOUT = "sandbox_locked_app_timeout";
    /** @hide */
    public static final String SETTING_SANDBOX_CONFIG = "sandbox_config";

    /** @hide */
    public static final String EXTRA_LOCKED_PACKAGE = "LOCKED_PACKAGE";
    /** @hide */
    public static final String EXTRA_LOCKED_UID = "LOCKED_UID";
    /** @hide */
    public static final String EXTRA_LOCKED_COMPONENT = "LOCKED_COMPONENT";
    /** @hide */
    public static final String EXTRA_NOTIFICATION_APP_LOCKED = "android.app.extra.AX_APP_LOCKED";

    /** @hide */
    public static final int DEFAULT_LOCK_TIMEOUT = 30;

    /** @hide */
    public enum AppLockState {
        NONE,
        UNLOCKED,
        LOCKED;

        public boolean hasAppLock() {
            return this != NONE;
        }

        public boolean needsAuth() {
            return this == LOCKED;
        }

        public static AppLockState fromOrdinal(int ordinal) {
            AppLockState[] values = values();
            if (ordinal < 0 || ordinal >= values.length) return NONE;
            return values[ordinal];
        }
    }

    /** @hide */
    public static final int GID_INET = 3003;
    /** @hide */
    public static final int GID_SDCARD_RW = 1015;
    /** @hide */
    public static final int GID_MEDIA_RW = 1023;
    /** @hide */
    public static final int GID_EXTERNAL_STORAGE = 1077;
    /** @hide */
    public static final int GID_EXT_DATA_RW = 1078;
    /** @hide */
    public static final int GID_EXT_OBB_RW = 1079;
    /** @hide */
    public static final int GID_SHARED_USER = 9997;
    /** @hide */
    public static final int GID_PACKAGE_INFO = 1032;

    /** @hide */
    public static final int[] STORAGE_GIDS = {
            GID_SDCARD_RW, GID_MEDIA_RW, GID_EXTERNAL_STORAGE,
            GID_EXT_DATA_RW, GID_EXT_OBB_RW
    };

    /** @hide */
    public AxSandboxManager(@NonNull Context context) {
    }

    /** @hide */
    public boolean isAppLocked(@NonNull String packageName, int userId) {
        return getAppLockState(packageName, userId).needsAuth();
    }

    /** @hide Same as above, for the calling user. */
    public boolean isAppLocked(@NonNull String packageName) {
        return isAppLocked(packageName, UserHandle.myUserId());
    }

    /** @hide */
    public AppLockState getAppLockState(@NonNull String packageName, int userId) {
        try {
            return AppLockState.fromOrdinal(
                    getService().getSandboxAppLockState(packageName, userId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public AppLockState getAppLockState(@NonNull String packageName) {
        return getAppLockState(packageName, UserHandle.myUserId());
    }

    /** @hide */
    public void addLockedApp(@NonNull String packageName, int userId) {
        try {
            getService().addSandboxLockedApp(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public void addLockedApp(@NonNull String packageName) {
        addLockedApp(packageName, UserHandle.myUserId());
    }

    /** @hide */
    public void removeLockedApp(@NonNull String packageName, int userId) {
        try {
            getService().removeSandboxLockedApp(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public void removeLockedApp(@NonNull String packageName) {
        removeLockedApp(packageName, UserHandle.myUserId());
    }

    /** @hide */
    public boolean isPackageHidden(@NonNull String packageName, int userId) {
        try {
            return getService().isSandboxPackageHidden(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public boolean isPackageHidden(@NonNull String packageName) {
        return isPackageHidden(packageName, UserHandle.myUserId());
    }

    /** @hide */
    public boolean isPackageHiddenFromLauncher(@NonNull String packageName, int userId) {
        try {
            return getService().isSandboxPackageHiddenFromLauncher(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public boolean isPackageHiddenFromLauncher(@NonNull String packageName) {
        return isPackageHiddenFromLauncher(packageName, UserHandle.myUserId());
    }

    /** @hide */
    public void setPackageHidden(@NonNull String packageName, boolean hidden, int userId) {
        try {
            getService().setSandboxPackageHidden(packageName, hidden, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public void setPackageHidden(@NonNull String packageName, boolean hidden) {
        setPackageHidden(packageName, hidden, UserHandle.myUserId());
    }

    /** @hide */
    public void setPackageHiddenFromLauncher(@NonNull String packageName, boolean hidden,
            int userId) {
        try {
            getService().setSandboxPackageHiddenFromLauncher(packageName, hidden, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public void setPackageHiddenFromLauncher(@NonNull String packageName, boolean hidden) {
        setPackageHiddenFromLauncher(packageName, hidden, UserHandle.myUserId());
    }

    /** @hide */
    @NonNull
    public List<String> getLockedPackages(int userId) {
        try {
            List<String> result = getService().getSandboxLockedPackages(userId);
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    @NonNull
    public List<String> getLockedPackages() {
        return getLockedPackages(UserHandle.myUserId());
    }

    /** @hide */
    @NonNull
    public List<String> getHiddenPackages(int userId) {
        try {
            List<String> result = getService().getSandboxHiddenPackages(userId);
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    @NonNull
    public List<String> getHiddenPackages() {
        return getHiddenPackages(UserHandle.myUserId());
    }

    /** @hide */
    @NonNull
    public List<String> getHiddenFromLauncherPackages(int userId) {
        try {
            List<String> result = getService().getSandboxHiddenFromLauncherPackages(userId);
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    @NonNull
    public List<String> getHiddenFromLauncherPackages() {
        return getHiddenFromLauncherPackages(UserHandle.myUserId());
    }

    /** @hide */
    @NonNull
    public List<String> getLockablePackages(int userId) {
        try {
            List<String> result = getService().getSandboxLockablePackages(userId);
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    @NonNull
    public List<String> getLockablePackages() {
        return getLockablePackages(UserHandle.myUserId());
    }

    /** @hide */
    public boolean isPackageLockable(@NonNull String packageName, int userId) {
        try {
            return getService().isSandboxPackageLockable(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public boolean isPackageLockable(@NonNull String packageName) {
        return isPackageLockable(packageName, UserHandle.myUserId());
    }

    /** @hide */
    public boolean isPackageSandboxed(@NonNull String packageName, int userId) {
        try {
            return getService().isSandboxPackageSandboxed(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public boolean isPackageSandboxed(@NonNull String packageName) {
        return isPackageSandboxed(packageName, UserHandle.myUserId());
    }

    /** @hide */
    public void addSandboxedPackage(@NonNull String packageName, int userId) {
        try {
            getService().addSandboxPackage(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public void addSandboxedPackage(@NonNull String packageName) {
        addSandboxedPackage(packageName, UserHandle.myUserId());
    }

    /** @hide */
    public void removeSandboxedPackage(@NonNull String packageName, int userId) {
        try {
            getService().removeSandboxPackage(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public void removeSandboxedPackage(@NonNull String packageName) {
        removeSandboxedPackage(packageName, UserHandle.myUserId());
    }

    /** @hide */
    @NonNull
    public List<String> getSandboxedPackages(int userId) {
        try {
            List<String> result = getService().getSandboxPackages(userId);
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    @NonNull
    public List<String> getSandboxedPackages() {
        return getSandboxedPackages(UserHandle.myUserId());
    }

    /** @hide */
    public void setRestrictedGids(@NonNull String packageName, int[] gids, int userId) {
        try {
            getService().setSandboxRestrictedGids(packageName, gids, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public void setRestrictedGids(@NonNull String packageName, int[] gids) {
        setRestrictedGids(packageName, gids, UserHandle.myUserId());
    }

    /** @hide */
    public int[] getRestrictedGids(@NonNull String packageName, int userId) {
        try {
            return getService().getSandboxRestrictedGids(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public int[] getRestrictedGids(@NonNull String packageName) {
        return getRestrictedGids(packageName, UserHandle.myUserId());
    }

    /** @hide */
    public boolean isSandboxDataIsolationEnabled(@NonNull String packageName, int userId) {
        try {
            return getService().isSandboxDataIsolationEnabled(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public boolean isSandboxDataIsolationEnabled(@NonNull String packageName) {
        return isSandboxDataIsolationEnabled(packageName, UserHandle.myUserId());
    }

    /** @hide */
    public void setSandboxDataIsolationEnabled(@NonNull String packageName, boolean enabled,
            int userId) {
        try {
            getService().setSandboxDataIsolationEnabled(packageName, enabled, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public void setSandboxDataIsolationEnabled(@NonNull String packageName, boolean enabled) {
        setSandboxDataIsolationEnabled(packageName, enabled, UserHandle.myUserId());
    }

    /** @hide */
    public boolean isSpoofSettingEnabled(@NonNull String packageName, @NonNull String settingKey,
            int userId) {
        try {
            return getService().isSandboxSpoofSettingEnabled(packageName, settingKey, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public boolean isSpoofSettingEnabled(@NonNull String packageName, @NonNull String settingKey) {
        return isSpoofSettingEnabled(packageName, settingKey, UserHandle.myUserId());
    }

    /** @hide */
    public void setSpoofSettingEnabled(@NonNull String packageName, @NonNull String settingKey,
            boolean enabled, int userId) {
        try {
            getService().setSandboxSpoofSettingEnabled(packageName, settingKey, enabled, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    public void setSpoofSettingEnabled(@NonNull String packageName, @NonNull String settingKey,
            boolean enabled) {
        setSpoofSettingEnabled(packageName, settingKey, enabled, UserHandle.myUserId());
    }

    /** @hide */
    @NonNull
    public List<String> getEnabledSpoofSettings(@NonNull String packageName, int userId) {
        try {
            List<String> result = getService().getSandboxEnabledSpoofSettings(packageName, userId);
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    @NonNull
    public List<String> getEnabledSpoofSettings(@NonNull String packageName) {
        return getEnabledSpoofSettings(packageName, UserHandle.myUserId());
    }

    /** @hide */
    @Nullable
    public String getSpoofedSetting(@NonNull String callingPackage, @NonNull String settingName,
            int userId) {
        try {
            return getService().getSandboxSpoofedSetting(callingPackage, settingName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide Same as above, for the calling user. */
    @Nullable
    public String getSpoofedSetting(@NonNull String callingPackage, @NonNull String settingName) {
        return getSpoofedSetting(callingPackage, settingName, UserHandle.myUserId());
    }

    /** @hide */
    public void unlockApp(@NonNull String packageName, int userId) {
        try {
            getService().unlockSandboxApp(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void promptUnlock(@NonNull String packageName, int userId) {
        try {
            getService().promptSandboxUnlock(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void registerAppLockStateListener(IAppLockStateListener listener) {
        try {
            getService().registerSandboxAppLockStateListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void unregisterAppLockStateListener(IAppLockStateListener listener) {
        try {
            getService().unregisterSandboxAppLockStateListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void registerAppSessionListener(IAppSessionListener listener) {
        try {
            getService().registerSandboxAppSessionListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void unregisterAppSessionListener(IAppSessionListener listener) {
        try {
            getService().unregisterSandboxAppSessionListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void registerHiddenNotificationListener(IHiddenNotificationListener listener) {
        try {
            getService().registerSandboxHiddenNotificationListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void unregisterHiddenNotificationListener(IHiddenNotificationListener listener) {
        try {
            getService().unregisterSandboxHiddenNotificationListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public List<HiddenNotificationInfo> getHiddenNotifications() {
        try {
            return getService().getSandboxHiddenNotifications();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void onHiddenNotificationPosted(HiddenNotificationInfo info) {
        try {
            getService().onSandboxHiddenNotificationPosted(info);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void onHiddenNotificationRemoved(String key) {
        try {
            getService().onSandboxHiddenNotificationRemoved(key);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private IActivityManager getService() {
        return ActivityManager.getService();
    }
}
