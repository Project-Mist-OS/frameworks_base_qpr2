/*
 * Copyright (C) 2025-2026 AxionOS
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
package com.android.server.wm.sandbox;

import android.app.AxSandboxManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds the sandbox configuration (locked / hidden / sandboxed packages, restricted gids, data
 * isolation and spoofed settings). The configuration is kept per user and persisted as a JSON blob
 * in that user's {@link Settings.Secure#getUriFor(String) sandbox_config} setting.
 */
public class AppControlController {
    private static final String TAG = "AxSandbox.AppControl";

    public static final String SETTING_SANDBOX_CONFIG = AxSandboxManager.SETTING_SANDBOX_CONFIG;

    private static final String KEY_HIDDEN_PKGS = "hidden_pkgs";
    private static final String KEY_LAUNCHER_HIDDEN_PKGS = "launcher_hidden_pkgs";
    private static final String KEY_LOCKED_PKGS = "locked_pkgs";
    private static final String KEY_SANDBOXED_PKGS = "sandboxed_pkgs";
    private static final String KEY_HIDEDEVOPTS_PKGS = "hidedevopts_pkgs";
    private static final String KEY_GID_RESTRICTIONS = "gid_restrictions";
    private static final String KEY_SPOOF_SETTINGS_MAP = "spoof_settings_map";
    private static final String KEY_DATA_ISOLATION = "data_isolation_pkgs";

    private static final long PACKAGE_CHANGED_FLUSH_MS = 400;

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final LauncherApps mLauncherApps;
    private final Set<String> mBlacklistedPackages;
    private final Handler mHandler;

    /** The sandbox configuration of a single user. Guarded by {@code AppControlController.this}. */
    private static final class UserConfig {
        final Set<String> lockedPackages = new HashSet<>();
        final Set<String> hiddenPackages = new HashSet<>();
        final Set<String> launcherHiddenPackages = new HashSet<>();
        final Set<String> sandboxedPackages = new HashSet<>();
        final Set<String> hideDevOptsPackages = new HashSet<>();
        final Map<String, int[]> gidRestrictions = new HashMap<>();
        final Map<String, Set<String>> spoofSettingsMap = new HashMap<>();
        final Set<String> dataIsolationPackages = new HashSet<>();

        void clear() {
            lockedPackages.clear();
            hiddenPackages.clear();
            launcherHiddenPackages.clear();
            sandboxedPackages.clear();
            hideDevOptsPackages.clear();
            gidRestrictions.clear();
            spoofSettingsMap.clear();
            dataIsolationPackages.clear();
        }
    }

    private static final class PendingBroadcast {
        final String packageName;
        final int uid;

        PendingBroadcast(String packageName, int uid) {
            this.packageName = packageName;
            this.uid = uid;
        }
    }

    private final SparseArray<UserConfig> mConfigs = new SparseArray<>();

    // Launchers reload their whole model per broadcast; flush once per burst of changes.
    private final Map<String, PendingBroadcast> mPendingPackageBroadcasts = new LinkedHashMap<>();
    private final Runnable mFlushPackageBroadcasts = this::flushPackageBroadcasts;

    private ContentObserver mConfigObserver;

    public interface OnUpdateListener {
        void onSandboxUpdated();
    }

    private OnUpdateListener mUpdateListener;

    public AppControlController(Context context, Set<String> blacklistedPackages) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mLauncherApps = context.getSystemService(LauncherApps.class);
        mBlacklistedPackages = blacklistedPackages;
        mHandler = new Handler(Looper.getMainLooper());
    }

    public void setUpdateListener(OnUpdateListener listener) {
        mUpdateListener = listener;
    }

    public void init() {
        registerSettingsObserver();
        loadAllUsers();
    }

    private void registerSettingsObserver() {
        mConfigObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                reloadConfigAndBroadcastChanges();
                notifyUpdate();
            }
        };

        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_SANDBOX_CONFIG),
                false, mConfigObserver, UserHandle.USER_ALL);

        Slog.d(TAG, "Registered Settings observer for sandbox_config");
    }

    private void loadAllUsers() {
        try {
            final UserManager um = mContext.getSystemService(UserManager.class);
            if (um != null) {
                for (android.content.pm.UserInfo user : um.getAliveUsers()) {
                    loadConfigFromSettings(user.id);
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to preload sandbox config for all users", e);
        }
        // The system user always exists, even if the lookup above failed.
        getConfig(UserHandle.USER_SYSTEM);
    }

    /** Loads (or reloads) the configuration of {@code userId} from its sandbox_config setting. */
    public void loadUser(int userId) {
        loadConfigFromSettings(userId);
    }

    private UserConfig getConfig(int userId) {
        synchronized (this) {
            UserConfig config = mConfigs.get(userId);
            if (config != null) return config;
        }
        // Not loaded yet (e.g. a user that started after init): read it outside our lock.
        return loadConfigFromSettings(userId);
    }

    private UserConfig loadConfigFromSettings(int userId) {
        String jsonStr = null;
        try {
            jsonStr = Settings.Secure.getStringForUser(
                    mContentResolver, SETTING_SANDBOX_CONFIG, userId);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to read sandbox_config for user " + userId, e);
        }

        UserConfig config;
        synchronized (this) {
            config = mConfigs.get(userId);
            if (config == null) {
                config = new UserConfig();
                mConfigs.put(userId, config);
            }
            config.clear();

            if (!TextUtils.isEmpty(jsonStr)) {
                try {
                    JSONObject json = new JSONObject(jsonStr);

                    loadPackageSet(json, KEY_LOCKED_PKGS, config.lockedPackages);
                    loadPackageSet(json, KEY_HIDDEN_PKGS, config.hiddenPackages);
                    loadPackageSet(json, KEY_LAUNCHER_HIDDEN_PKGS, config.launcherHiddenPackages);
                    loadPackageSet(json, KEY_SANDBOXED_PKGS, config.sandboxedPackages);
                    loadPackageSet(json, KEY_HIDEDEVOPTS_PKGS, config.hideDevOptsPackages);
                    loadSpoofSettingsMap(json, config);
                    loadPackageSet(json, KEY_DATA_ISOLATION, config.dataIsolationPackages);
                    loadGidRestrictions(json, config);

                } catch (JSONException e) {
                    Slog.e(TAG, "Failed to parse sandbox_config JSON for user " + userId, e);
                }
            }

            Slog.i(TAG, "Loaded config for user " + userId
                    + ": locked=" + config.lockedPackages.size()
                    + " hidden=" + config.hiddenPackages.size()
                    + " launcherHidden=" + config.launcherHiddenPackages.size()
                    + " sandboxed=" + config.sandboxedPackages.size()
                    + " hideDevOpts=" + config.hideDevOptsPackages.size());
        }
        return config;
    }

    private void loadPackageSet(JSONObject config, String key, Set<String> target) {
        JSONArray arr = config.optJSONArray(key);
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String pkg = arr.optString(i);
                if (!TextUtils.isEmpty(pkg) && !mBlacklistedPackages.contains(pkg)) {
                    target.add(pkg);
                }
            }
        }
    }

    private void saveConfigToSettings(int userId) {
        try {
            JSONObject json = new JSONObject();

            synchronized (this) {
                final UserConfig config = getConfig(userId);
                json.put(KEY_LOCKED_PKGS, new JSONArray(config.lockedPackages));
                json.put(KEY_HIDDEN_PKGS, new JSONArray(config.hiddenPackages));
                json.put(KEY_LAUNCHER_HIDDEN_PKGS, new JSONArray(config.launcherHiddenPackages));
                json.put(KEY_SANDBOXED_PKGS, new JSONArray(config.sandboxedPackages));
                json.put(KEY_HIDEDEVOPTS_PKGS, new JSONArray(config.hideDevOptsPackages));
                saveSpoofSettingsMap(json, config);
                json.put(KEY_DATA_ISOLATION, new JSONArray(config.dataIsolationPackages));
                saveGidRestrictions(json, config);
            }

            Settings.Secure.putStringForUser(
                    mContentResolver, SETTING_SANDBOX_CONFIG, json.toString(), userId);

        } catch (JSONException e) {
            Slog.e(TAG, "Failed to save sandbox_config JSON for user " + userId, e);
        }
    }

    private void reloadConfigAndBroadcastChanges() {
        final List<Integer> userIds = new ArrayList<>();
        synchronized (this) {
            for (int i = 0; i < mConfigs.size(); i++) {
                userIds.add(mConfigs.keyAt(i));
            }
        }
        for (int userId : userIds) {
            final Set<String> before = new HashSet<>();
            synchronized (this) {
                final UserConfig config = mConfigs.get(userId);
                if (config != null) {
                    before.addAll(config.hiddenPackages);
                    before.addAll(config.launcherHiddenPackages);
                }
            }
            final UserConfig config = loadConfigFromSettings(userId);
            final Set<String> changed = new HashSet<>(before);
            synchronized (this) {
                for (String pkg : config.hiddenPackages) {
                    if (!changed.remove(pkg)) changed.add(pkg);
                }
                for (String pkg : config.launcherHiddenPackages) {
                    if (!changed.remove(pkg)) changed.add(pkg);
                }
            }
            for (String pkg : changed) {
                int uid = getPackageUid(pkg, userId);
                if (uid >= 0) {
                    queuePackageChangedBroadcast(pkg, uid);
                }
            }
        }
    }

    private void queuePackageChangedBroadcast(String packageName, int uid) {
        synchronized (this) {
            mPendingPackageBroadcasts.put(UserHandle.getUserId(uid) + ":" + packageName,
                    new PendingBroadcast(packageName, uid));
        }
        mHandler.removeCallbacks(mFlushPackageBroadcasts);
        mHandler.postDelayed(mFlushPackageBroadcasts, PACKAGE_CHANGED_FLUSH_MS);
    }

    private void flushPackageBroadcasts() {
        final Map<String, PendingBroadcast> pending;
        synchronized (this) {
            if (mPendingPackageBroadcasts.isEmpty()) return;
            pending = new LinkedHashMap<>(mPendingPackageBroadcasts);
            mPendingPackageBroadcasts.clear();
        }
        for (PendingBroadcast e : pending.values()) {
            broadcastPackageChange(e.packageName, e.uid);
        }
    }

    private void broadcastPackageChange(String packageName, int uid) {
        try {
            Intent intent = new Intent(Intent.ACTION_PACKAGE_CHANGED);
            intent.setData(Uri.fromParts("package", packageName, null));
            intent.putExtra(Intent.EXTRA_UID, uid);
            intent.putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(uid));
            intent.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, new String[]{packageName});
            intent.putExtra(Intent.EXTRA_DONT_KILL_APP, true);
            mContext.sendBroadcastAsUser(intent, UserHandle.of(UserHandle.getUserId(uid)));
        } catch (Exception e) {
            Slog.w(TAG, "Failed to broadcast package change for " + packageName, e);
        }
    }

    private void notifyUpdate() {
        if (mUpdateListener != null) {
            mUpdateListener.onSandboxUpdated();
        }
    }

    public boolean isAppLocked(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return false;
        if (mBlacklistedPackages.contains(packageName)) return false;
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return config.lockedPackages.contains(packageName);
        }
    }

    /** Whether any loaded user has a locked package. */
    public boolean hasLockedPackages() {
        synchronized (this) {
            for (int i = 0; i < mConfigs.size(); i++) {
                if (!mConfigs.valueAt(i).lockedPackages.isEmpty()) return true;
            }
            return false;
        }
    }

    public boolean hasLockedPackages(int userId) {
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return !config.lockedPackages.isEmpty();
        }
    }

    public boolean isPackageHidden(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return false;
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return config.hiddenPackages.contains(packageName);
        }
    }

    public boolean isPackageHiddenFromLauncher(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return false;
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return config.launcherHiddenPackages.contains(packageName);
        }
    }

    public boolean isPackageSandboxed(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return false;
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return config.sandboxedPackages.contains(packageName);
        }
    }

    public boolean isDevOptionsHidden(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return false;
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return config.hideDevOptsPackages.contains(packageName);
        }
    }

    public void addLockedApp(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return;
        if (!isPackageLockable(packageName, userId)) {
            Slog.w(TAG, "Cannot lock package - not lockable: " + packageName
                    + " userId=" + userId);
            return;
        }
        int uid = getPackageUid(packageName, userId);
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            if (config.lockedPackages.add(packageName)) {
                saveConfigToSettings(userId);
                if (uid >= 0) {
                    queuePackageChangedBroadcast(packageName, uid);
                }
            }
        }
        Slog.d(TAG, "addLockedApp: " + packageName + " userId=" + userId);
    }

    public void removeLockedApp(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return;
        int uid = getPackageUid(packageName, userId);
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            if (config.lockedPackages.remove(packageName)) {
                saveConfigToSettings(userId);
                if (uid >= 0) {
                    queuePackageChangedBroadcast(packageName, uid);
                }
            }
        }
        Slog.d(TAG, "removeLockedApp: " + packageName + " userId=" + userId);
    }

    public void setPackageHidden(String packageName, boolean hidden, int userId) {
        if (TextUtils.isEmpty(packageName)) return;
        if (hidden && !isPackageLockable(packageName, userId)) {
            Slog.w(TAG, "Cannot hide package - not lockable: " + packageName
                    + " userId=" + userId);
            return;
        }
        int uid = getPackageUid(packageName, userId);
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            boolean changed = hidden ? config.hiddenPackages.add(packageName)
                                     : config.hiddenPackages.remove(packageName);
            if (changed) {
                saveConfigToSettings(userId);
                if (uid >= 0) {
                    queuePackageChangedBroadcast(packageName, uid);
                }
            }
        }
        Slog.d(TAG, "setPackageHidden: " + packageName + " hidden=" + hidden
                + " userId=" + userId);
    }

    public void setPackageHiddenFromLauncher(String packageName, boolean hidden, int userId) {
        if (TextUtils.isEmpty(packageName)) return;
        if (hidden && !isPackageLockable(packageName, userId)) {
            Slog.w(TAG, "Cannot hide package from launcher - not lockable: " + packageName
                    + " userId=" + userId);
            return;
        }
        int uid = getPackageUid(packageName, userId);
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            boolean changed = hidden ? config.launcherHiddenPackages.add(packageName)
                                     : config.launcherHiddenPackages.remove(packageName);
            if (changed) {
                saveConfigToSettings(userId);
                if (uid >= 0) {
                    queuePackageChangedBroadcast(packageName, uid);
                }
            }
        }
        Slog.d(TAG, "setPackageHiddenFromLauncher: " + packageName + " hidden=" + hidden
                + " userId=" + userId);
    }

    public void setPackageSandboxed(String packageName, boolean sandboxed, int userId) {
        if (TextUtils.isEmpty(packageName)) return;
        int uid = getPackageUid(packageName, userId);
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            boolean changed = sandboxed ? config.sandboxedPackages.add(packageName)
                                        : config.sandboxedPackages.remove(packageName);
            if (changed) {
                saveConfigToSettings(userId);
                if (uid >= 0) {
                    queuePackageChangedBroadcast(packageName, uid);
                }
            }
        }
        Slog.d(TAG, "setPackageSandboxed: " + packageName + " sandboxed=" + sandboxed
                + " userId=" + userId);
    }

    public void setDevOptionsHidden(String packageName, boolean hidden, int userId) {
        if (TextUtils.isEmpty(packageName)) return;
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            boolean changed = hidden ? config.hideDevOptsPackages.add(packageName)
                                     : config.hideDevOptsPackages.remove(packageName);
            if (changed) {
                saveConfigToSettings(userId);
            }
        }
        Slog.d(TAG, "setDevOptionsHidden: " + packageName + " hidden=" + hidden
                + " userId=" + userId);
    }

    public List<String> getLockedPackages(int userId) {
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return new ArrayList<>(config.lockedPackages);
        }
    }

    public List<String> getHiddenPackages(int userId) {
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return new ArrayList<>(config.hiddenPackages);
        }
    }

    public List<String> getHiddenFromLauncherPackages(int userId) {
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return new ArrayList<>(config.launcherHiddenPackages);
        }
    }

    public List<String> getSandboxedPackages(int userId) {
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return new ArrayList<>(config.sandboxedPackages);
        }
    }

    public List<String> getDevOptionsHiddenPackages(int userId) {
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return new ArrayList<>(config.hideDevOptsPackages);
        }
    }

    public boolean isPackageLockable(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return false;
        if (mBlacklistedPackages.contains(packageName)) return false;

        if (mLauncherApps == null) return false;

        try {
            List<LauncherActivityInfo> activities = mLauncherApps.getActivityList(
                    packageName, UserHandle.of(userId));
            return activities != null && !activities.isEmpty();
        } catch (Exception e) {
            Slog.w(TAG, "Failed to check if package is lockable: " + packageName
                    + " userId=" + userId, e);
            return false;
        }
    }

    public List<String> getLockablePackages(int userId) {
        List<String> result = new ArrayList<>();

        if (mLauncherApps != null) {
            try {
                List<LauncherActivityInfo> activities = mLauncherApps.getActivityList(
                        null, UserHandle.of(userId));

                Set<String> seen = new HashSet<>();
                for (LauncherActivityInfo info : activities) {
                    String pkgName = info.getApplicationInfo().packageName;
                    if (!mBlacklistedPackages.contains(pkgName) && !seen.contains(pkgName)) {
                        result.add(pkgName);
                        seen.add(pkgName);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to get lockable packages from LauncherApps", e);
            }
        }

        if (result.isEmpty()) {
            Slog.w(TAG, "LauncherApps returned empty, using PackageManager fallback");
            try {
                PackageManager pm = mContext.getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplicationsAsUser(0, userId);
                for (ApplicationInfo appInfo : apps) {
                    if (mBlacklistedPackages.contains(appInfo.packageName)) continue;
                    if (isSystemUid(appInfo.uid)) continue;
                    Intent launch = new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setPackage(appInfo.packageName);
                    List<ResolveInfo> launchers = pm.queryIntentActivitiesAsUser(launch, 0, userId);
                    if (launchers != null && !launchers.isEmpty()) {
                        result.add(appInfo.packageName);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to get lockable packages from PackageManager", e);
            }
        }

        return result;
    }

    private int getPackageUid(String packageName, int userId) {
        try {
            return mContext.getPackageManager()
                    .getApplicationInfoAsUser(packageName, 0, userId).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private static boolean isSystemUid(int uid) {
        int appId = UserHandle.getAppId(uid);
        return appId == android.os.Process.ROOT_UID || appId == android.os.Process.SYSTEM_UID;
    }

    private void loadGidRestrictions(JSONObject json, UserConfig config) {
        JSONObject gidObj = json.optJSONObject(KEY_GID_RESTRICTIONS);
        if (gidObj == null) return;
        Iterator<String> keys = gidObj.keys();
        while (keys.hasNext()) {
            String pkg = keys.next();
            JSONArray arr = gidObj.optJSONArray(pkg);
            if (arr != null && arr.length() > 0) {
                int[] gids = new int[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    gids[i] = arr.optInt(i);
                }
                config.gidRestrictions.put(pkg, gids);
            }
        }
    }

    private void saveGidRestrictions(JSONObject json, UserConfig config) throws JSONException {
        JSONObject gidObj = new JSONObject();
        for (Map.Entry<String, int[]> entry : config.gidRestrictions.entrySet()) {
            JSONArray arr = new JSONArray();
            for (int gid : entry.getValue()) {
                arr.put(gid);
            }
            gidObj.put(entry.getKey(), arr);
        }
        json.put(KEY_GID_RESTRICTIONS, gidObj);
    }

    public void setRestrictedGids(String packageName, int[] gids, int userId) {
        if (TextUtils.isEmpty(packageName)) return;
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            if (gids == null || gids.length == 0) {
                config.gidRestrictions.remove(packageName);
            } else {
                config.gidRestrictions.put(packageName, gids);
            }
            saveConfigToSettings(userId);
        }
    }

    public int[] getRestrictedGids(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return null;
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return config.gidRestrictions.get(packageName);
        }
    }

    public boolean isSettingsSpoofEnabled(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return false;
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return config.spoofSettingsMap.containsKey(packageName);
        }
    }

    public boolean isSpoofSettingEnabled(String packageName, String settingKey, int userId) {
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(settingKey)) return false;
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            Set<String> settings = config.spoofSettingsMap.get(packageName);
            return settings != null && settings.contains(settingKey);
        }
    }

    public void setSpoofSettingEnabled(String packageName, String settingKey, boolean enabled,
            int userId) {
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(settingKey)) return;
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            Set<String> settings = config.spoofSettingsMap.get(packageName);
            boolean changed;
            if (enabled) {
                if (settings == null) {
                    settings = new HashSet<>();
                    config.spoofSettingsMap.put(packageName, settings);
                }
                changed = settings.add(settingKey);
            } else {
                if (settings == null) return;
                changed = settings.remove(settingKey);
                if (settings.isEmpty()) {
                    config.spoofSettingsMap.remove(packageName);
                }
            }
            if (changed) saveConfigToSettings(userId);
        }
    }

    public List<String> getEnabledSpoofSettings(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return Collections.emptyList();
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            Set<String> settings = config.spoofSettingsMap.get(packageName);
            if (settings == null || settings.isEmpty()) return Collections.emptyList();
            return new ArrayList<>(settings);
        }
    }

    private void loadSpoofSettingsMap(JSONObject json, UserConfig config) {
        JSONObject mapObj = json.optJSONObject(KEY_SPOOF_SETTINGS_MAP);
        if (mapObj == null) return;
        Iterator<String> keys = mapObj.keys();
        while (keys.hasNext()) {
            String pkg = keys.next();
            JSONArray arr = mapObj.optJSONArray(pkg);
            if (arr != null && arr.length() > 0) {
                Set<String> settings = new HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i);
                    if (!TextUtils.isEmpty(s)) settings.add(s);
                }
                if (!settings.isEmpty()) {
                    config.spoofSettingsMap.put(pkg, settings);
                }
            }
        }
    }

    private void saveSpoofSettingsMap(JSONObject json, UserConfig config) throws JSONException {
        JSONObject mapObj = new JSONObject();
        for (Map.Entry<String, Set<String>> entry : config.spoofSettingsMap.entrySet()) {
            mapObj.put(entry.getKey(), new JSONArray(entry.getValue()));
        }
        json.put(KEY_SPOOF_SETTINGS_MAP, mapObj);
    }

    public boolean isDataIsolationEnabled(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return false;
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            return config.dataIsolationPackages.contains(packageName);
        }
    }

    public void setDataIsolationEnabled(String packageName, boolean enabled, int userId) {
        if (TextUtils.isEmpty(packageName)) return;
        final UserConfig config = getConfig(userId);
        synchronized (this) {
            boolean changed = enabled ? config.dataIsolationPackages.add(packageName)
                                      : config.dataIsolationPackages.remove(packageName);
            if (changed) saveConfigToSettings(userId);
        }
    }
}
