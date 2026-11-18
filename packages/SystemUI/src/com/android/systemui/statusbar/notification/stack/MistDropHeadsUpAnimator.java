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

package com.android.systemui.statusbar.notification.stack;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;
import android.util.MathUtils;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewRootImpl;
import android.view.WindowInsets;
import android.view.animation.Interpolator;

import com.android.app.animation.Interpolators;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;

import java.util.function.BooleanSupplier;

final class MistDropHeadsUpAnimator {

    private static final String TAG = "MistDropHeadsUp";

    static final String KEY_ENABLED = "mist_heads_up_drop_enabled";
    static final String KEY_ORIGIN = "mist_heads_up_drop_origin";
    static final String KEY_CUSTOM_X = "mist_heads_up_drop_custom_x";
    static final String KEY_OFFSET_Y = "mist_heads_up_drop_offset_y";
    static final String KEY_EXIT = "mist_heads_up_drop_exit";
    static final String KEY_SPEED = "mist_heads_up_drop_speed";
    static final String KEY_STYLE = "mist_heads_up_drop_style";
    private static final String KEY_DYNAMIC_BAR = "ax_dynamic_bar_enabled";

    static final int ORIGIN_AUTO = 0;
    static final int ORIGIN_CUTOUT = 1;
    static final int ORIGIN_ISLAND = 2;
    static final int ORIGIN_LEFT = 3;
    static final int ORIGIN_CENTER = 4;
    static final int ORIGIN_RIGHT = 5;
    static final int ORIGIN_CUSTOM = 6;

    private static final float A_STATUS_OUT_END = 80f;
    private static final float A_SWELL_START = 40f;
    private static final float A_DRIP = 240f;
    private static final float A_BULGE_END = A_DRIP + 50f;
    private static final float A_IMPACT = A_DRIP + 130f;
    private static final float A_SQUASH_START = A_IMPACT - 30f;
    private static final float A_BREAK = A_IMPACT + 15f;
    private static final float A_GLASS_IN_END = A_IMPACT + 50f;
    private static final float A_GLOW_START = A_IMPACT + 40f;
    private static final float A_GLOW_PEAK = A_IMPACT + 110f;
    private static final float A_GLOW_END = A_IMPACT + 300f;
    private static final float A_STATUS_IN_START = A_IMPACT + 80f;
    private static final float A_STATUS_IN_END = A_IMPACT + 160f;
    private static final float A_REVEAL_START = A_IMPACT + 110f;
    private static final float A_REVEAL_ALPHA_END = A_IMPACT + 260f;
    private static final float A_REVEAL_BLUR_END = A_IMPACT + 330f;
    private static final float A_GLASS_OUT_START = A_IMPACT + 200f;
    private static final float A_GLASS_OUT_END = A_IMPACT + 360f;
    private static final float A_RADIUS_START = A_IMPACT + 250f;
    private static final float A_RADIUS_END = A_IMPACT + 420f;
    private static final float A_TIP_START = A_IMPACT - 10f;
    private static final float A_TIP_END = A_IMPACT + 70f;
    private static final float A_RIPPLE_END = A_IMPACT + 180f;
    private static final float A_SATELLITE_END = A_BREAK + 560f;
    private static final float APPEAR_TOTAL = A_IMPACT + 630f;
    private static final float A_SEED_OUT_START = APPEAR_TOTAL - 160f;

    private static final float A_SEED_SPRING = 260f;
    private static final float A_BULGE_SPRING = 160f;
    private static final float A_HANG_PERIOD = 90f;
    private static final float A_HANG_DECAY = 80f;
    private static final float A_RECOIL_PERIOD = 120f;
    private static final float A_RECOIL_DECAY = 110f;
    private static final float A_WIDTH_SPRING = 260f;
    private static final float A_HEIGHT_SPRING = 340f;
    private static final float A_JELLY_PERIOD = 170f;
    private static final float A_JELLY_DECAY = 190f;
    private static final float A_SURFACE_PERIOD = 220f;
    private static final float A_SURFACE_DECAY = 170f;
    private static final float A_SURFACE_LAG = 25f;
    private static final float A_SAT_ROUND = 45f;

    private static final float DISAPPEAR_TOTAL = 460f;
    private static final float D_SHRINK_END = 200f;
    private static final float D_ROW_FADE_START = 40f;
    private static final float D_ROW_FADE_END = 180f;
    private static final float D_BLOB_IN_END = 90f;
    private static final float D_COLOR_START = 60f;
    private static final float D_SEED_IN_START = 120f;
    private static final float D_SEED_IN_END = 240f;
    private static final float D_NECK_START = 250f;
    private static final float D_RISE_END = 360f;
    private static final float D_SEED_OUT_START = 380f;
    private static final float D_SHAPE_TIME = 90f;
    private static final float D_JIGGLE_PERIOD = 100f;
    private static final float D_JIGGLE_DECAY = 90f;
    private static final float D_GULP_PERIOD = 110f;
    private static final float D_GULP_DECAY = 90f;

    static final int STYLE_CLASSIC = 0;
    static final int STYLE_GLOSSY = 1;
    static final int STYLE_BLUR = 2;

    static final int DEFAULT_SPEED = 75;
    private static final int MIN_SPEED = 25;
    private static final int MAX_SPEED = 200;

    private static final int INK_COLOR = Color.BLACK;

    private final Context mContext;
    private final View mHost;
    private final BooleanSupplier mShadeExpanded;
    private final float mDensity;
    private final ArrayMap<View, Run> mRuns = new ArrayMap<>();
    private final ArgbEvaluator mArgb = new ArgbEvaluator();

    private final boolean mEnabled;
    private final int mOrigin;
    private final int mCustomX;
    private final float mOffsetY;
    private final boolean mExitEnabled;
    private final float mSpeed;
    private final int mStyle;

    MistDropHeadsUpAnimator(Context context, View host, BooleanSupplier shadeExpanded) {
        mContext = context;
        mHost = host;
        mShadeExpanded = shadeExpanded;
        mDensity = context.getResources().getDisplayMetrics().density;
        ContentResolver cr = context.getContentResolver();
        mEnabled = readSecure(cr, KEY_ENABLED, 0) != 0;
        mOrigin = readSecure(cr, KEY_ORIGIN, ORIGIN_AUTO);
        mCustomX = MathUtils.constrain(readSecure(cr, KEY_CUSTOM_X, 50), 0, 100);
        mOffsetY = MathUtils.constrain(readSecure(cr, KEY_OFFSET_Y, 0), -20, 40) * mDensity;
        mExitEnabled = readSecure(cr, KEY_EXIT, 1) != 0;
        mSpeed = MathUtils.constrain(readSecure(cr, KEY_SPEED, DEFAULT_SPEED),
                MIN_SPEED, MAX_SPEED) / 100f;
        mStyle = MathUtils.constrain(readSecure(cr, KEY_STYLE, STYLE_CLASSIC),
                STYLE_CLASSIC, STYLE_BLUR);
    }

    private static int readSecure(ContentResolver cr, String key, int def) {
        try {
            return Settings.Secure.getIntForUser(cr, key, def, UserHandle.USER_CURRENT);
        } catch (Exception e) {
            return def;
        }
    }

    boolean canAnimateAppear(ExpandableView view, int targetHeight, boolean fromBottom,
            boolean hasChip) {
        return check(view, targetHeight, fromBottom, hasChip, true);
    }

    boolean canAnimateDisappear(ExpandableView view, boolean fromBottom, boolean hasChip) {
        return mExitEnabled
                && check(view, view.getActualHeight(), fromBottom, hasChip, false);
    }

    private boolean check(ExpandableView view, int height, boolean fromBottom, boolean hasChip,
            boolean appear) {
        if (!mEnabled) return false;
        String reason = null;
        if (mHost == null || !mHost.isAttachedToWindow()) {
            reason = "host detached";
        } else if (!ValueAnimator.areAnimatorsEnabled()) {
            reason = "animations off";
        } else if (!(view instanceof ExpandableNotificationRow)) {
            reason = "not a row";
        } else if (view.getWidth() <= 0 || height <= 0) {
            reason = "no size " + view.getWidth() + "x" + height;
        } else if (mShadeExpanded.getAsBoolean()) {
            reason = "shade expanded";
        } else if (fromBottom) {
            reason = "from bottom";
        } else if (hasChip) {
            reason = "status bar chip";
        }
        if (reason != null) {
            Log.d(TAG, (appear ? "appear" : "disappear") + " skipped: " + reason);
            return false;
        }
        return true;
    }

    long getDisappearDuration() {
        return Math.round(DISAPPEAR_TOTAL / mSpeed);
    }

    void finish(View view) {
        Run run = mRuns.get(view);
        if (run != null) {
            run.animator.end();
        }
    }

    void startAppear(ExpandableView view, Runnable onEnd) {
        finish(view);
        View root = mHost.getRootView();
        Run run = new Run(view, root, true);
        if (!run.prepare()) {
            if (onEnd != null) onEnd.run();
            return;
        }
        run.onEnd = onEnd;
        run.start(Math.round(APPEAR_TOTAL / mSpeed), null);
    }

    void startDisappear(ExpandableView view, Runnable onStart, Runnable onEnd,
            AnimatorListenerAdapter globalListener) {
        finish(view);
        View root = mHost.getRootView();
        Run run = new Run(view, root, false);
        if (!run.prepare()) {
            if (onStart != null) onStart.run();
            if (onEnd != null) onEnd.run();
            return;
        }
        run.onStart = onStart;
        run.onEnd = onEnd;
        run.start(getDisappearDuration(), globalListener);
    }

    private float dp(float v) {
        return v * mDensity;
    }

    private static float progress(float t, float start, float end) {
        if (end <= start) return t >= end ? 1f : 0f;
        return MathUtils.constrain((t - start) / (end - start), 0f, 1f);
    }

    private static float progress(float t, float start, float end, Interpolator interpolator) {
        return interpolator.getInterpolation(progress(t, start, end));
    }

    private static float spring(float ms, float damping, float responseMs) {
        if (ms <= 0f) return 0f;
        double omega = 2.0 * Math.PI / (responseMs / 1000.0);
        double wd = omega * Math.sqrt(1.0 - damping * damping);
        double t = ms / 1000.0;
        double decay = Math.exp(-damping * omega * t);
        return (float) (1.0 - decay * (Math.cos(wd * t) + (damping * omega / wd) * Math.sin(wd * t)));
    }

    private static float wobble(float ms, float periodMs, float decayMs) {
        if (ms <= 0f) return 0f;
        return (float) (Math.exp(-ms / decayMs) * Math.sin(2.0 * Math.PI * ms / periodMs));
    }

    private static float bell(float t, float start, float end) {
        float p = progress(t, start, end);
        return (float) Math.sin(Math.PI * p);
    }

    private boolean isIslandActive() {
        ContentResolver cr = mContext.getContentResolver();
        try {
            if (Settings.System.getIntForUser(cr, Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND,
                    0, UserHandle.USER_CURRENT) != 0) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return readSecure(cr, KEY_DYNAMIC_BAR, 0) != 0;
    }

    private RectF findTopCutout(View root) {
        WindowInsets insets = root.getRootWindowInsets();
        if (insets == null) return null;
        DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout == null) return null;
        Rect top = cutout.getBoundingRectTop();
        if (top == null || top.isEmpty()) return null;
        if (top.height() > root.getHeight() / 4 || top.width() > root.getWidth() / 2) return null;
        return new RectF(top);
    }

    private static final class Origin {
        float cx;
        float cy;
        float halfW;
        float halfH;
        boolean physical;

        float neckWidth() {
            return 2f * Math.min(halfW, halfH) * 0.8f;
        }
    }

    private Origin resolveOrigin(View root) {
        Origin o = new Origin();
        float width = root.getWidth();
        float statusBar = SystemBarUtils.getStatusBarHeight(mContext);
        RectF cutout = findTopCutout(root);
        int mode = mOrigin;
        if (mode == ORIGIN_AUTO) {
            if (isIslandActive()) {
                mode = ORIGIN_ISLAND;
            } else if (cutout != null) {
                mode = ORIGIN_CUTOUT;
            } else {
                mode = ORIGIN_CENTER;
            }
        }
        if (mode == ORIGIN_CUTOUT && cutout == null) {
            mode = ORIGIN_CENTER;
        }
        float seedRadius = dp(5.5f);
        switch (mode) {
            case ORIGIN_CUTOUT: {
                float r = Math.min(cutout.width(), cutout.height()) / 2f;
                o.cx = cutout.centerX();
                o.cy = cutout.bottom - r;
                o.halfW = r * 0.92f;
                o.halfH = r * 0.92f;
                o.physical = true;
                break;
            }
            case ORIGIN_ISLAND: {
                o.halfH = Math.max(dp(8f), statusBar * 0.34f);
                o.halfW = o.halfH * 2.6f;
                o.cx = cutout != null && Math.abs(cutout.centerX() - width / 2f) < width * 0.1f
                        ? cutout.centerX() : width / 2f;
                o.cy = cutout != null ? Math.max(cutout.centerY(), statusBar / 2f)
                        : statusBar / 2f;
                o.physical = false;
                break;
            }
            case ORIGIN_LEFT:
                o.cx = Math.max(statusBar * 0.9f, dp(24f));
                o.cy = statusBar / 2f;
                o.halfW = o.halfH = seedRadius;
                break;
            case ORIGIN_RIGHT:
                o.cx = width - Math.max(statusBar * 0.9f, dp(24f));
                o.cy = statusBar / 2f;
                o.halfW = o.halfH = seedRadius;
                break;
            case ORIGIN_CUSTOM: {
                float edge = Math.max(statusBar * 0.5f, dp(12f));
                o.cx = MathUtils.constrain(width * mCustomX / 100f, edge, width - edge);
                o.cy = statusBar / 2f;
                o.halfW = o.halfH = seedRadius;
                break;
            }
            case ORIGIN_CENTER:
            default:
                o.cx = width / 2f;
                o.cy = statusBar / 2f;
                o.halfW = o.halfH = seedRadius;
                break;
        }
        o.cy += mOffsetY;
        return o;
    }

    private final class Run extends AnimatorListenerAdapter
            implements ValueAnimator.AnimatorUpdateListener {
        final ExpandableView view;
        final View root;
        final boolean appear;
        final DropDrawable drawable;
        final Matrix matrix = new Matrix();
        final RectF rowBounds = new RectF();
        final RectF startBounds = new RectF();
        final RectF blob = new RectF();
        ValueAnimator animator;
        Origin origin;
        Runnable onStart;
        Runnable onEnd;
        int bgColor;
        float rowRadius;
        float dropRadius;
        float capsuleW;
        float capsuleH;
        float satX;
        float satY;
        float satR;
        boolean satSpawned;
        boolean finished;
        boolean endPending;
        boolean statusBarTouched;

        Run(ExpandableView view, View root, boolean appear) {
            this.view = view;
            this.root = root;
            this.appear = appear;
            this.drawable = new DropDrawable(mDensity, isNight(), mStyle);
        }

        private boolean isNight() {
            try {
                return mContext.getResources().getConfiguration().isNightModeActive();
            } catch (Exception e) {
                return true;
            }
        }

        boolean prepare() {
            if (root == null || root.getWidth() == 0 || !computeRowBounds(rowBounds)) {
                return false;
            }
            startBounds.set(rowBounds);
            origin = resolveOrigin(root);
            int tint = view instanceof ExpandableNotificationRow
                    ? ((ExpandableNotificationRow) view).getCurrentBackgroundTint() : 0;
            if (Color.alpha(tint) == 0 && view instanceof ExpandableNotificationRow) {
                tint = ((ExpandableNotificationRow) view).getBackgroundColorWithoutTint();
            }
            bgColor = Color.alpha(tint) == 0 ? Color.DKGRAY : (tint | 0xFF000000);
            rowRadius = Math.min(view.getTopCornerRadius(), rowBounds.height() / 2f);
            dropRadius = Math.max(Math.min(origin.halfW, origin.halfH) * 1.05f, dp(5f));
            capsuleH = Math.min(rowBounds.height() * 0.5f, dp(24f));
            capsuleW = Math.min(rowBounds.width(),
                    Math.max(capsuleH * 2.4f, rowBounds.width() * 0.34f));
            if (appear || mStyle == STYLE_BLUR) {
                try {
                    ViewRootImpl vri = root.getViewRootImpl();
                    if (vri != null) {
                        boolean liquidBlur = mStyle == STYLE_BLUR;
                        drawable.setBlurDrawables(vri.createBackgroundBlurDrawable(),
                                liquidBlur ? vri.createBackgroundBlurDrawable() : null,
                                liquidBlur ? vri.createBackgroundBlurDrawable() : null);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "no background blur", t);
                }
            }
            return true;
        }

        void start(long duration, AnimatorListenerAdapter globalListener) {
            mRuns.put(view, this);
            root.getOverlay().add(drawable);
            drawable.setBounds(0, 0, root.getWidth(), root.getHeight());
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(duration);
            animator.setInterpolator(Interpolators.LINEAR);
            animator.addUpdateListener(this);
            animator.addListener(this);
            if (globalListener != null) {
                animator.addListener(globalListener);
            }
            if (onStart != null) {
                onStart.run();
            }
            applyFrame(0f);
            animator.start();
        }

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            if ((!view.isAttachedToWindow() && view.getParent() == null)
                    || (appear && mShadeExpanded.getAsBoolean())) {
                if (!endPending) {
                    endPending = true;
                    mHost.post(() -> {
                        if (!finished) animation.end();
                    });
                }
                return;
            }
            applyFrame(animation.getAnimatedFraction());
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (finished) return;
            finished = true;
            if (mRuns.get(view) == this) {
                mRuns.remove(view);
            }
            drawable.release();
            root.getOverlay().remove(drawable);
            view.setAnimationMatrix(null);
            view.setRenderEffect(null);
            view.setTransitionAlpha(1f);
            if (statusBarTouched) {
                MistDropStatusBar.setAlpha(1f);
            }
            if (onEnd != null) {
                onEnd.run();
            }
        }

        private void applyFrame(float fraction) {
            float total = appear ? APPEAR_TOTAL : DISAPPEAR_TOTAL;
            float t = fraction * total;
            if (!computeRowBounds(rowBounds)) rowBounds.set(startBounds);
            drawable.reset();
            drawable.origin = origin;
            if (appear) {
                appearFrame(t);
            } else {
                disappearFrame(t);
            }
            drawable.invalidateSelf();
        }

        private void setStatusBarAlpha(float alpha) {
            statusBarTouched = true;
            MistDropStatusBar.setAlpha(alpha);
        }

        private void appearFrame(float t) {
            DropDrawable d = drawable;
            Origin o = origin;
            RectF r = rowBounds;
            float impactCy = r.top + capsuleH / 2f;
            float impactCx = impactX(r);

            setStatusBarAlpha(Math.max(1f - progress(t, 0f, A_STATUS_OUT_END,
                    Interpolators.FAST_OUT_SLOW_IN),
                    progress(t, A_STATUS_IN_START, A_STATUS_IN_END,
                            Interpolators.LINEAR_OUT_SLOW_IN)));

            float swell = progress(t, A_SWELL_START, A_DRIP, Interpolators.FAST_OUT_SLOW_IN)
                    * (1f - progress(t, A_DRIP, A_IMPACT, Interpolators.FAST_OUT_SLOW_IN));
            float recoil = wobble(t - A_BREAK, A_RECOIL_PERIOD, A_RECOIL_DECAY);
            float grow = o.physical ? 1f : MathUtils.constrain(spring(t, 0.5f, A_SEED_SPRING), 0f, 1.3f)
                    * (1f - progress(t, A_SEED_OUT_START, APPEAR_TOTAL,
                            Interpolators.FAST_OUT_SLOW_IN));
            d.originScaleX = grow * (1f + 0.10f * swell - 0.06f * recoil);
            d.originScaleY = grow * (1f + 0.20f * swell + 0.10f * recoil);

            if (t < A_DRIP) {
                hideRow();
                return;
            }

            if (t < A_IMPACT) {
                float local = t - A_DRIP;
                float bulge = MathUtils.constrain(spring(local, 0.45f, A_BULGE_SPRING), 0f, 1.25f);
                float rd = dropRadius * bulge;
                float hangY = o.cy + o.halfH * d.originScaleY + dropRadius * 0.9f;
                float fall = progress(t, A_BULGE_END, A_IMPACT, Interpolators.FAST_OUT_LINEAR_IN);
                float cy = MathUtils.lerp(MathUtils.lerp(o.cy, hangY,
                        Math.min(bulge, 1f)), impactCy, fall);
                float jiggle = 0.18f * wobble(local, A_HANG_PERIOD, A_HANG_DECAY);
                float stretch = 1f + 0.30f * fall + jiggle;
                float squash = progress(t, A_SQUASH_START, A_IMPACT,
                        Interpolators.FAST_OUT_SLOW_IN);
                float halfW = MathUtils.lerp(rd * (1f - 0.12f * fall - jiggle * 0.5f),
                        capsuleW / 2f, squash);
                float halfH = MathUtils.lerp(rd * stretch, capsuleH / 2f, squash);
                float bx = MathUtils.lerp(o.cx, impactCx, squash);
                blob.set(bx - halfW, cy - halfH, bx + halfW, cy + halfH);
                d.setShape(blob, halfH, 0f, halfH * 0.25f * squash, 0f);
                d.inkAlpha = 1f;
                float waistStart = Math.min(o.neckWidth(), 2f * rd * 0.85f) * 0.75f;
                float waist = waistStart * (float) Math.pow(1f - fall, 1.6f);
                d.setNeck(o.cx, o.cy, o.neckWidth() * Math.max(d.originScaleX, 0.35f),
                        bx, cy, Math.min(2f * halfH, 2f * halfW) * 0.8f,
                        Math.max(waist, dp(0.8f)));
                d.tip = capsuleH * 0.9f * bell(t, A_TIP_START, A_TIP_END);
                hideRow();
                return;
            }

            float local = t - A_IMPACT;
            float pw = spring(local, 0.72f, A_WIDTH_SPRING);
            float ph = spring(local, 0.48f, A_HEIGHT_SPRING);
            float jelly = 0.045f * wobble(local, A_JELLY_PERIOD, A_JELLY_DECAY);
            float cx = MathUtils.lerp(impactCx, r.centerX(), pw);
            float cy = MathUtils.lerp(impactCy, r.centerY(), ph);
            float maxHalfW = Math.min(cx, root.getWidth() - cx) - dp(3f);
            float halfW = Math.min(MathUtils.lerp(capsuleW / 2f, r.width() / 2f, pw)
                    * (1f + jelly), Math.max(maxHalfW, r.width() / 2f));
            float halfH = Math.max(dp(1f),
                    MathUtils.lerp(capsuleH / 2f, r.height() / 2f, ph) * (1f - jelly));
            blob.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);

            float radiusMix = progress(t, A_RADIUS_START, A_RADIUS_END,
                    Interpolators.FAST_OUT_SLOW_IN);
            float radius = MathUtils.lerp(halfH, Math.min(rowRadius, halfH), radiusMix);
            float liquid = 1f - radiusMix;
            float bulgeTop = 2f * halfH * 0.09f * wobble(local, A_SURFACE_PERIOD, A_SURFACE_DECAY) * liquid;
            float bulgeBottom = 2f * halfH * 0.12f * wobble(local - A_SURFACE_LAG, A_SURFACE_PERIOD, A_SURFACE_DECAY) * liquid;
            d.setShape(blob, radius, bulgeTop, bulgeBottom, 0f);
            d.tip = capsuleH * 0.9f * bell(t, A_TIP_START, A_TIP_END);

            float glassIn = progress(t, A_IMPACT, A_GLASS_IN_END, Interpolators.FAST_OUT_SLOW_IN);
            float glassOut = 1f - progress(t, A_GLASS_OUT_START, A_GLASS_OUT_END,
                    Interpolators.FAST_OUT_SLOW_IN);
            d.inkAlpha = 1f - glassIn;
            d.glassAlpha = glassIn * glassOut;
            d.rimAlpha = glassIn * glassOut;
            d.glowAlpha = progress(t, A_GLOW_START, A_GLOW_PEAK,
                    Interpolators.FAST_OUT_SLOW_IN)
                    * (1f - progress(t, A_GLOW_PEAK, A_GLOW_END, Interpolators.FAST_OUT_SLOW_IN));

            float ripple = progress(t, A_IMPACT, A_RIPPLE_END, Interpolators.LINEAR_OUT_SLOW_IN);
            if (ripple > 0f && ripple < 1f) {
                d.rippleAlpha = 0.35f * (1f - ripple);
                d.rippleGrow = dp(14f) * ripple;
            }

            if (t < A_BREAK) {
                d.setNeck(o.cx, o.cy, o.neckWidth() * Math.max(d.originScaleX, 0.35f),
                        impactCx, impactCy, capsuleH * 0.8f, dp(0.8f));
            } else if (!satSpawned) {
                satSpawned = true;
                satX = MathUtils.lerp(o.cx, impactCx, 0.45f);
                satY = MathUtils.lerp(o.cy + o.halfH, impactCy - capsuleH / 2f, 0.45f);
                satR = Math.max(dp(2.2f), Math.min(o.halfW, o.halfH) * 0.42f);
            }

            if (satSpawned) {
                float s = progress(t, A_BREAK, A_SATELLITE_END);
                if (s < 1f) {
                    float move = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(s);
                    float size = satR * (1f - Interpolators.FAST_OUT_LINEAR_IN.getInterpolation(s));
                    float elongate = 1f - progress(t, A_BREAK, A_BREAK + A_SAT_ROUND,
                            Interpolators.FAST_OUT_SLOW_IN);
                    d.setSatellite(MathUtils.lerp(satX, o.cx, move),
                            MathUtils.lerp(satY, o.cy + o.halfH * 0.3f, move),
                            size * (1f - 0.25f * elongate), size * (1f + 0.6f * elongate));
                }
            }

            float revealAlpha = progress(t, A_REVEAL_START, A_REVEAL_ALPHA_END,
                    Interpolators.LINEAR_OUT_SLOW_IN);
            float blur = dp(20f) * (1f - progress(t, A_REVEAL_START, A_REVEAL_BLUR_END,
                    Interpolators.FAST_OUT_SLOW_IN));
            transformRow(blob, revealAlpha, blur);
        }

        private void disappearFrame(float t) {
            DropDrawable d = drawable;
            Origin o = origin;
            RectF s = startBounds;

            float seedIn = progress(t, D_SEED_IN_START, D_SEED_IN_END,
                    Interpolators.FAST_OUT_SLOW_IN);
            float seedOut = progress(t, D_SEED_OUT_START, DISAPPEAR_TOTAL,
                    Interpolators.FAST_OUT_SLOW_IN);
            float grow = o.physical ? 1f : seedIn * (1f - seedOut);
            float gulp = wobble(t - D_RISE_END, D_GULP_PERIOD, D_GULP_DECAY);
            d.originScaleX = grow * (1f + 0.08f * gulp);
            d.originScaleY = grow * (1f + 0.14f * gulp);

            if (t < D_SHRINK_END) {
                float q = progress(t, 0f, D_SHRINK_END, Interpolators.FAST_OUT_SLOW_IN);
                float cx = MathUtils.lerp(s.centerX(), impactX(s), q);
                float halfW = MathUtils.lerp(s.width() / 2f, capsuleW / 2f, q);
                float bottom = MathUtils.lerp(s.bottom, s.top + capsuleH, q);
                blob.set(cx - halfW, s.top, cx + halfW, bottom);
                float radius = MathUtils.lerp(Math.min(rowRadius, blob.height() / 2f),
                        blob.height() / 2f, q);
                d.setShape(blob, radius, 0f, 0f, 0f);
                d.fillColor = (int) mArgb.evaluate(progress(t, D_COLOR_START, D_SHRINK_END,
                        Interpolators.FAST_OUT_SLOW_IN), bgColor, INK_COLOR);
                d.fillAlpha = progress(t, 0f, D_BLOB_IN_END, Interpolators.LINEAR_OUT_SLOW_IN);
                float rowAlpha = 1f - progress(t, D_ROW_FADE_START, D_ROW_FADE_END,
                        Interpolators.FAST_OUT_SLOW_IN);
                float blur = dp(22f) * progress(t, 0f, D_ROW_FADE_END,
                        Interpolators.FAST_OUT_SLOW_IN);
                transformRow(blob, rowAlpha, blur);
                return;
            }

            hideRow();
            float rise = progress(t, D_SHRINK_END, D_RISE_END, Interpolators.FAST_OUT_LINEAR_IN);
            float absorb = progress(t, D_RISE_END, DISAPPEAR_TOTAL,
                    Interpolators.FAST_OUT_SLOW_IN);
            float startCy = s.top + capsuleH / 2f;
            float hangY = o.cy + o.halfH + dropRadius * 0.9f;
            float cy = MathUtils.lerp(MathUtils.lerp(startCy, hangY, rise), o.cy, absorb);
            float cx = MathUtils.lerp(impactX(s), o.cx, rise);
            float shape = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                    progress(t, D_SHRINK_END, D_SHRINK_END + D_SHAPE_TIME));
            float jiggle = 0.16f * wobble(t - D_SHRINK_END, D_JIGGLE_PERIOD, D_JIGGLE_DECAY);
            float halfW = MathUtils.lerp(capsuleW / 2f, dropRadius * 0.92f, shape)
                    * (1f - jiggle) * (1f - absorb);
            float halfH = MathUtils.lerp(capsuleH / 2f, dropRadius * 1.15f, shape)
                    * (1f + jiggle) * (1f - absorb);
            blob.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
            d.setShape(blob, Math.min(halfW, halfH), 0f, 0f, 0f);
            d.inkAlpha = halfW > 0.5f ? 1f : 0f;
            if (t >= D_NECK_START && halfW > 0.5f) {
                float neck = progress(t, D_NECK_START, D_RISE_END,
                        Interpolators.FAST_OUT_SLOW_IN);
                float waist = Math.min(o.neckWidth(), 2f * halfW) * 0.75f * neck;
                d.setNeck(o.cx, o.cy, o.neckWidth() * Math.max(d.originScaleX, 0.35f),
                        cx, cy, Math.min(2f * halfH, 2f * halfW) * 0.8f,
                        Math.max(waist, dp(0.8f)));
            }
        }

        private float impactX(RectF bounds) {
            float half = capsuleW / 2f;
            if (bounds.width() <= capsuleW) return bounds.centerX();
            return MathUtils.constrain(origin.cx, bounds.left + half, bounds.right - half);
        }

        private void hideRow() {
            transformRow(rowBounds, 0f, 0f);
        }

        private void transformRow(RectF target, float alpha, float blur) {
            RectF r = rowBounds;
            if (r.width() <= 0f || r.height() <= 0f) return;
            matrix.reset();
            matrix.setScale(target.width() / r.width(), target.height() / r.height());
            matrix.postTranslate(target.left - r.left, target.top - r.top);
            view.setAnimationMatrix(matrix);
            view.setTransitionAlpha(MathUtils.constrain(alpha, 0f, 1f));
            view.setRenderEffect(blur > 0.5f
                    ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL)
                    : null);
        }

        private boolean computeRowBounds(RectF out) {
            float x = 0f;
            float y = 0f;
            View v = view;
            while (v != root) {
                x += v.getLeft() + v.getTranslationX();
                y += v.getTop() + v.getTranslationY();
                ViewParent parent = v.getParent();
                if (!(parent instanceof View)) return false;
                View pv = (View) parent;
                x -= pv.getScrollX();
                y -= pv.getScrollY();
                v = pv;
            }
            int height = view.getActualHeight();
            if (height <= 0 && view.getViewState() != null) height = view.getViewState().height;
            if (view.getWidth() <= 0 || height <= 0) return false;
            out.set(x, y, x + view.getWidth(), y + height);
            return out.height() > 0f;
        }
    }

    private static final class DropDrawable extends Drawable {
        private static final float K = 0.5523f;

        private final Paint mInk = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlass = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRipple = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mNeck = new Path();
        private final Path mShape = new Path();
        private final Path mUnion = new Path();
        private final Path mPart = new Path();
        private final RectF mTmp = new RectF();
        private final RectF mOriginRect = new RectF();
        private final Rect mTmpInt = new Rect();
        private final float mDensity;
        private final int mStyle;
        private final int mGlassColor;
        private final int mGlassBlurColor;
        private final float mGlassBlurRadius;
        private final float mLiquidBlurRadius;
        private final float mGlossAlpha;
        private BackgroundBlurDrawable mBlur;
        private BackgroundBlurDrawable mOriginBlur;
        private BackgroundBlurDrawable mSatBlur;

        final RectF shape = new RectF();
        Origin origin;
        float originScaleX;
        float originScaleY;
        float radius;
        float bulgeTop;
        float bulgeBottom;
        float tip;
        float inkAlpha;
        float fillAlpha;
        int fillColor;
        float glassAlpha;
        float rimAlpha;
        float glowAlpha;
        float rippleAlpha;
        float rippleGrow;
        boolean neckVisible;
        boolean satVisible;
        float satX;
        float satY;
        float satRx;
        float satRy;

        DropDrawable(float density, boolean night, int style) {
            mDensity = density;
            mStyle = style;
            mInk.setStyle(Paint.Style.FILL);
            if (style == STYLE_BLUR) {
                mInk.setColor(night ? Color.argb(90, 255, 255, 255)
                        : Color.argb(80, 24, 26, 34));
            } else {
                mInk.setColor(INK_COLOR);
            }
            mFill.setStyle(Paint.Style.FILL);
            mGlass.setStyle(Paint.Style.FILL);
            mGlassColor = night ? Color.argb(92, 255, 255, 255) : Color.argb(190, 255, 255, 255);
            mGlassBlurColor = night ? Color.argb(40, 255, 255, 255) : Color.argb(70, 255, 255, 255);
            mGlassBlurRadius = 30f * density;
            mLiquidBlurRadius = 18f * density;
            mGlossAlpha = style == STYLE_BLUR ? 0.75f : 0.6f;
            mRim.setStyle(Paint.Style.STROKE);
            mRim.setStrokeWidth(1.6f * density);
            mRim.setColor(Color.WHITE);
            mGlow.setStyle(Paint.Style.FILL);
            mGlow.setColor(Color.WHITE);
            mGlow.setMaskFilter(new BlurMaskFilter(16f * density, BlurMaskFilter.Blur.NORMAL));
            mRipple.setStyle(Paint.Style.STROKE);
            mRipple.setStrokeWidth(1.2f * density);
            mRipple.setColor(Color.WHITE);
            mGloss.setStyle(Paint.Style.STROKE);
            mGloss.setStrokeWidth(1.3f * density);
            mGloss.setColor(Color.WHITE);
            mShine.setStyle(Paint.Style.FILL);
            mShine.setColor(Color.WHITE);
            mShine.setMaskFilter(new BlurMaskFilter(1.5f * density, BlurMaskFilter.Blur.NORMAL));
        }

        private static void prepareBlur(BackgroundBlurDrawable blur) {
            if (blur == null) return;
            blur.setXfermode(null);
            blur.setBlurRadius(0);
            blur.setColor(Color.TRANSPARENT);
        }

        private static void clearBlur(BackgroundBlurDrawable blur) {
            if (blur == null) return;
            blur.setBlurRadius(0);
            blur.setColor(Color.TRANSPARENT);
            blur.setVisible(false, false);
        }

        void setBlurDrawables(BackgroundBlurDrawable shapeBlur, BackgroundBlurDrawable originBlur,
                BackgroundBlurDrawable satBlur) {
            mBlur = shapeBlur;
            mOriginBlur = originBlur;
            mSatBlur = satBlur;
            prepareBlur(mBlur);
            prepareBlur(mOriginBlur);
            prepareBlur(mSatBlur);
        }

        void release() {
            clearBlur(mBlur);
            clearBlur(mOriginBlur);
            clearBlur(mSatBlur);
        }

        void reset() {
            originScaleX = 0f;
            originScaleY = 0f;
            shape.setEmpty();
            radius = 0f;
            bulgeTop = 0f;
            bulgeBottom = 0f;
            tip = 0f;
            inkAlpha = 0f;
            fillAlpha = 0f;
            glassAlpha = 0f;
            rimAlpha = 0f;
            glowAlpha = 0f;
            rippleAlpha = 0f;
            rippleGrow = 0f;
            neckVisible = false;
            satVisible = false;
        }

        void setShape(RectF rect, float r, float top, float bottom, float tipLength) {
            shape.set(rect);
            radius = r;
            bulgeTop = top;
            bulgeBottom = bottom;
            tip = tipLength;
        }

        void setSatellite(float x, float y, float rx, float ry) {
            satVisible = rx > 0.3f && ry > 0.3f;
            satX = x;
            satY = y;
            satRx = rx;
            satRy = ry;
        }

        void setNeck(float ax, float ay, float topWidth, float bx, float by, float bottomWidth,
                float waist) {
            neckVisible = true;
            float my = MathUtils.lerp(ay, by, 0.55f);
            float mx = MathUtils.lerp(ax, bx, 0.55f);
            float ta = topWidth / 2f;
            float tb = bottomWidth / 2f;
            float tw = waist / 2f;
            float k1 = (my - ay) * 0.55f;
            float k2 = (by - my) * 0.55f;
            mNeck.rewind();
            mNeck.moveTo(ax - ta, ay);
            mNeck.cubicTo(ax - ta, ay + k1, mx - tw, my - k1, mx - tw, my);
            mNeck.cubicTo(mx - tw, my + k2, bx - tb, by - k2, bx - tb, by);
            mNeck.lineTo(bx + tb, by);
            mNeck.cubicTo(bx + tb, by - k2, mx + tw, my + k2, mx + tw, my);
            mNeck.cubicTo(mx + tw, my - k1, ax + ta, ay + k1, ax + ta, ay);
            mNeck.close();
        }

        private void buildShape() {
            RectF r = shape;
            float h = r.height();
            float rr = Math.max(0f, Math.min(radius, Math.min(h, r.width()) / 2f));
            float l = r.left;
            float t = r.top;
            float ri = r.right;
            float b = r.bottom;
            float cy = r.centerY();
            float span = (ri - l) - 2f * rr;
            float c = 4f / 3f;
            mShape.rewind();
            mShape.moveTo(l + rr, t);
            mShape.cubicTo(l + rr + span / 3f, t - bulgeTop * c,
                    ri - rr - span / 3f, t - bulgeTop * c, ri - rr, t);
            if (tip > 0.5f) {
                mShape.cubicTo(ri - rr + rr * K, t, ri + tip * 0.35f, cy - rr * 0.45f,
                        ri + tip, cy);
                mShape.cubicTo(ri + tip * 0.35f, cy + rr * 0.45f, ri - rr + rr * K, b,
                        ri - rr, b);
            } else {
                mShape.cubicTo(ri - rr + rr * K, t, ri, t + rr - rr * K, ri, t + rr);
                mShape.lineTo(ri, b - rr);
                mShape.cubicTo(ri, b - rr + rr * K, ri - rr + rr * K, b, ri - rr, b);
            }
            mShape.cubicTo(ri - rr - span / 3f, b + bulgeBottom * c,
                    l + rr + span / 3f, b + bulgeBottom * c, l + rr, b);
            if (tip > 0.5f) {
                mShape.cubicTo(l + rr - rr * K, b, l - tip * 0.35f, cy + rr * 0.45f,
                        l - tip, cy);
                mShape.cubicTo(l - tip * 0.35f, cy - rr * 0.45f, l + rr - rr * K, t,
                        l + rr, t);
            } else {
                mShape.cubicTo(l + rr - rr * K, b, l, b - rr + rr * K, l, b - rr);
                mShape.lineTo(l, t + rr);
                mShape.cubicTo(l, t + rr - rr * K, l + rr - rr * K, t, l + rr, t);
            }
            mShape.close();
        }

        private static int alpha(float a) {
            return Math.round(255f * MathUtils.constrain(a, 0f, 1f));
        }

        private void updateBlur(BackgroundBlurDrawable blur, RectF bounds, float corner,
                float blurRadius, int color, Canvas canvas) {
            if (blur == null) return;
            if (bounds != null && blurRadius >= 1f) {
                bounds.round(mTmpInt);
                blur.setBounds(mTmpInt);
                blur.setCornerRadius(corner);
                blur.setBlurRadius(Math.round(blurRadius));
                blur.setColor(color);
            } else {
                blur.setBlurRadius(0);
                blur.setColor(Color.TRANSPARENT);
            }
            blur.draw(canvas);
        }

        private void drawShine(Canvas canvas, RectF body, float strength) {
            if (strength <= 0.01f || body.isEmpty()) return;
            float w = body.width();
            float h = body.height();
            float ry = Math.max(h * 0.14f, 0.6f * mDensity);
            float rx = Math.max(Math.min(w * 0.16f, h * 0.38f), ry);
            float cx = body.left + Math.min(w * 0.3f, h * 0.55f);
            float cy = body.top + h * 0.3f;
            mTmp.set(cx - rx, cy - ry, cx + rx, cy + ry);
            mShine.setAlpha(alpha(0.8f * strength));
            canvas.drawOval(mTmp, mShine);
        }

        @Override
        public void draw(Canvas canvas) {
            Origin o = origin;
            if (o == null) return;

            boolean hasShape = !shape.isEmpty();
            if (hasShape) buildShape();
            boolean liquid = mStyle != STYLE_CLASSIC;
            boolean blurStyle = mStyle == STYLE_BLUR;

            boolean originVisible = originScaleX > 0.01f && originScaleY > 0.01f;
            float originCorner = 0f;
            if (originVisible) {
                float hw = o.halfW * originScaleX;
                float hh = o.halfH * originScaleY;
                mOriginRect.set(o.cx - hw, o.cy - hh, o.cx + hw, o.cy + hh);
                originCorner = Math.min(hw, hh);
            }
            boolean originMoving = !o.physical || neckVisible
                    || Math.abs(originScaleX - 1f) > 0.005f
                    || Math.abs(originScaleY - 1f) > 0.005f;

            if (hasShape && glowAlpha > 0.003f) {
                mGlow.setAlpha(alpha(glowAlpha * 0.55f));
                canvas.drawPath(mShape, mGlow);
            }

            float shapeCorner = hasShape
                    ? Math.min(radius, Math.min(shape.height(), shape.width()) / 2f) : 0f;
            float liquidShape = blurStyle && hasShape ? inkAlpha : 0f;
            float shapeBlur = Math.max(mGlassBlurRadius * glassAlpha,
                    mLiquidBlurRadius * liquidShape);
            int shapeTint = Color.argb(Math.round(Color.alpha(mGlassBlurColor) * glassAlpha),
                    255, 255, 255);
            updateBlur(mBlur, hasShape ? shape : null, shapeCorner, shapeBlur, shapeTint, canvas);
            if (blurStyle) {
                updateBlur(mOriginBlur, originVisible && originMoving ? mOriginRect : null,
                        originCorner, mLiquidBlurRadius, Color.TRANSPARENT, canvas);
                if (satVisible) {
                    mTmp.set(satX - satRx, satY - satRy, satX + satRx, satY + satRy);
                }
                updateBlur(mSatBlur, satVisible ? mTmp : null, Math.min(satRx, satRy),
                        mLiquidBlurRadius * 0.6f, Color.TRANSPARENT, canvas);
            }

            if (originVisible && (!blurStyle || originMoving)) {
                canvas.drawRoundRect(mOriginRect, originCorner, originCorner, mInk);
            }
            if (neckVisible) {
                canvas.drawPath(mNeck, mInk);
            }
            if (satVisible) {
                mTmp.set(satX - satRx, satY - satRy, satX + satRx, satY + satRy);
                canvas.drawOval(mTmp, mInk);
            }
            if (hasShape && inkAlpha > 0.003f) {
                int base = mInk.getAlpha();
                mInk.setAlpha(Math.round(base * MathUtils.constrain(inkAlpha, 0f, 1f)));
                canvas.drawPath(mShape, mInk);
                mInk.setAlpha(base);
            }

            if (liquid) {
                mUnion.rewind();
                if (originVisible && originMoving) {
                    mPart.rewind();
                    mPart.addRoundRect(mOriginRect, originCorner, originCorner,
                            Path.Direction.CW);
                    mUnion.op(mPart, Path.Op.UNION);
                }
                if (neckVisible) {
                    mUnion.op(mNeck, Path.Op.UNION);
                }
                boolean shapeIsLiquid = hasShape && inkAlpha > 0.3f;
                if (shapeIsLiquid) {
                    mUnion.op(mShape, Path.Op.UNION);
                }
                if (satVisible) {
                    mPart.rewind();
                    mPart.addOval(satX - satRx, satY - satRy, satX + satRx, satY + satRy,
                            Path.Direction.CW);
                    mUnion.op(mPart, Path.Op.UNION);
                }
                if (!mUnion.isEmpty()) {
                    mGloss.setAlpha(alpha(mGlossAlpha));
                    canvas.drawPath(mUnion, mGloss);
                }
                if (shapeIsLiquid) {
                    drawShine(canvas, shape, inkAlpha);
                }
                if (originVisible && originMoving) {
                    drawShine(canvas, mOriginRect, 0.8f);
                }
                if (satVisible) {
                    mTmp.set(satX - satRx, satY - satRy, satX + satRx, satY + satRy);
                    drawShine(canvas, mTmp, 0.7f);
                }
            }

            if (hasShape && fillAlpha > 0.003f) {
                mFill.setColor(fillColor);
                mFill.setAlpha(alpha(fillAlpha));
                canvas.drawPath(mShape, mFill);
            }
            if (hasShape && glassAlpha > 0.003f) {
                mGlass.setColor(mGlassColor);
                mGlass.setAlpha(alpha(glassAlpha * Color.alpha(mGlassColor) / 255f));
                canvas.drawPath(mShape, mGlass);
            }
            if (hasShape && rimAlpha > 0.003f) {
                mRim.setAlpha(alpha(rimAlpha * 0.7f));
                canvas.drawPath(mShape, mRim);
            }
            if (hasShape && rippleAlpha > 0.003f) {
                mTmp.set(shape);
                mTmp.inset(-rippleGrow, -rippleGrow);
                float rr = Math.min(radius + rippleGrow, mTmp.height() / 2f);
                mRipple.setAlpha(alpha(rippleAlpha));
                canvas.drawRoundRect(mTmp, rr, rr, mRipple);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
