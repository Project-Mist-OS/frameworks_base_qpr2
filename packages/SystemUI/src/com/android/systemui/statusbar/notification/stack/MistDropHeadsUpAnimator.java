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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
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
import android.util.MathUtils;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.animation.Interpolator;

import com.android.app.animation.Interpolators;
import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;

import java.util.function.BooleanSupplier;

final class MistDropHeadsUpAnimator {

    static final String KEY_ENABLED = "mist_heads_up_drop_enabled";
    static final String KEY_ORIGIN = "mist_heads_up_drop_origin";
    static final String KEY_CUSTOM_X = "mist_heads_up_drop_custom_x";
    static final String KEY_OFFSET_Y = "mist_heads_up_drop_offset_y";
    static final String KEY_EXIT = "mist_heads_up_drop_exit";
    static final String KEY_SPEED = "mist_heads_up_drop_speed";
    private static final String KEY_DYNAMIC_BAR = "ax_dynamic_bar_enabled";

    static final int ORIGIN_AUTO = 0;
    static final int ORIGIN_CUTOUT = 1;
    static final int ORIGIN_ISLAND = 2;
    static final int ORIGIN_LEFT = 3;
    static final int ORIGIN_CENTER = 4;
    static final int ORIGIN_RIGHT = 5;
    static final int ORIGIN_CUSTOM = 6;

    private static final float APPEAR_TOTAL = 760f;
    private static final float A_BULGE_END = 90f;
    private static final float A_IMPACT = 200f;
    private static final float A_BREAK = 215f;
    private static final float A_REVEAL_START = 230f;
    private static final float A_REVEAL_ALPHA_END = 400f;
    private static final float A_REVEAL_BLUR_END = 470f;
    private static final float A_COLOR_END = 300f;
    private static final float A_BLOB_FADE_START = 290f;
    private static final float A_BLOB_FADE_END = 430f;
    private static final float A_SATELLITE_END = 700f;
    private static final float A_SEED_OUT_START = 600f;

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

    private static final float SPRING_DAMPING = 0.62f;
    private static final float SPRING_RESPONSE_MS = 430f;

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
        mSpeed = MathUtils.constrain(readSecure(cr, KEY_SPEED, 100), 50, 200) / 100f;
    }

    private static int readSecure(ContentResolver cr, String key, int def) {
        try {
            return Settings.Secure.getIntForUser(cr, key, def, UserHandle.USER_CURRENT);
        } catch (Exception e) {
            return def;
        }
    }

    boolean canAnimateAppear(ExpandableView view, boolean fromBottom, boolean hasChip) {
        return isUsable(view) && !fromBottom && !hasChip;
    }

    boolean canAnimateDisappear(ExpandableView view, boolean fromBottom, boolean hasChip) {
        return mExitEnabled && isUsable(view) && !fromBottom && !hasChip;
    }

    long getDisappearDuration() {
        return Math.round(DISAPPEAR_TOTAL / mSpeed);
    }

    private boolean isUsable(ExpandableView view) {
        return mEnabled
                && mHost != null
                && ValueAnimator.areAnimatorsEnabled()
                && view instanceof ExpandableNotificationRow
                && view.getWidth() > 0
                && view.getActualHeight() > 0
                && mHost.isAttachedToWindow()
                && !mShadeExpanded.getAsBoolean();
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

    private static float spring(float ms) {
        if (ms <= 0f) return 0f;
        double omega = 2.0 * Math.PI / (SPRING_RESPONSE_MS / 1000.0);
        double zeta = SPRING_DAMPING;
        double wd = omega * Math.sqrt(1.0 - zeta * zeta);
        double t = ms / 1000.0;
        double decay = Math.exp(-zeta * omega * t);
        return (float) (1.0 - decay * (Math.cos(wd * t) + (zeta * omega / wd) * Math.sin(wd * t)));
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
        final DropDrawable drawable = new DropDrawable();
        final Matrix matrix = new Matrix();
        final RectF rowBounds = new RectF();
        final RectF startBounds = new RectF();
        final RectF blob = new RectF();
        final ColorMatrix colorMatrix = new ColorMatrix();
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

        Run(ExpandableView view, View root, boolean appear) {
            this.view = view;
            this.root = root;
            this.appear = appear;
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
            capsuleH = Math.min(rowBounds.height() * 0.6f, dp(28f));
            capsuleW = Math.min(rowBounds.width(),
                    Math.max(capsuleH * 2f, rowBounds.width() * 0.36f));
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
            root.getOverlay().remove(drawable);
            view.setAnimationMatrix(null);
            view.setRenderEffect(null);
            if (onEnd != null) {
                onEnd.run();
            }
        }

        private void applyFrame(float fraction) {
            float total = appear ? APPEAR_TOTAL : DISAPPEAR_TOTAL;
            float t = fraction * total;
            if (!computeRowBounds(rowBounds)) rowBounds.set(startBounds);
            if (appear) {
                appearFrame(t);
            } else {
                disappearFrame(t);
            }
            drawable.invalidateSelf();
        }

        private void appearFrame(float t) {
            DropDrawable d = drawable;
            Origin o = origin;
            RectF r = rowBounds;
            float impactCy = r.top + capsuleH / 2f;
            float impactCx = impactX(r);

            d.originScale = o.physical ? 1f
                    : progress(t, 0f, A_BULGE_END, Interpolators.FAST_OUT_SLOW_IN)
                            * (1f - progress(t, A_SEED_OUT_START, APPEAR_TOTAL,
                                    Interpolators.FAST_OUT_SLOW_IN));

            if (t < A_IMPACT) {
                float bulge = progress(t, 0f, A_BULGE_END, Interpolators.FAST_OUT_SLOW_IN);
                float rd = dropRadius * bulge;
                float hangY = o.cy + o.halfH + dropRadius * 0.9f;
                float fall = progress(t, A_BULGE_END, A_IMPACT, Interpolators.FAST_OUT_LINEAR_IN);
                float cy = MathUtils.lerp(MathUtils.lerp(o.cy, hangY, bulge), impactCy, fall);
                float stretch = 1f + 0.28f * fall;
                float squash = progress(t, A_IMPACT - 40f, A_IMPACT,
                        Interpolators.FAST_OUT_SLOW_IN);
                float halfW = MathUtils.lerp(rd * (1f - 0.1f * fall), capsuleW / 2f, squash);
                float halfH = MathUtils.lerp(rd * stretch, capsuleH / 2f, squash);
                float bx = MathUtils.lerp(o.cx, impactCx, squash);
                blob.set(bx - halfW, cy - halfH, bx + halfW, cy + halfH);
                d.setBlob(blob, halfH, INK_COLOR, 1f);
                float waistStart = Math.min(o.neckWidth(), 2f * rd * 0.85f) * 0.75f;
                float waist = waistStart * (float) Math.pow(1f - fall, 1.6f);
                d.setNeck(o.cx, o.cy, o.neckWidth() * d.originScaleForNeck(o.physical),
                        bx, cy, Math.min(2f * halfH, 2f * halfW) * 0.8f,
                        Math.max(waist, dp(0.8f)));
                hideRow();
            } else {
                float p = spring(t - A_IMPACT);
                float cx = MathUtils.lerp(impactCx, r.centerX(), p);
                float halfW = MathUtils.lerp(capsuleW / 2f, r.width() / 2f, p);
                float top = r.top;
                float bottom = MathUtils.lerp(r.top + capsuleH, r.bottom, p);
                blob.set(cx - halfW, top, cx + halfW, Math.max(bottom, top + dp(2f)));
                float radiusMix = progress(t, A_IMPACT, A_REVEAL_BLUR_END,
                        Interpolators.FAST_OUT_SLOW_IN);
                float radius = Math.min(blob.height() / 2f,
                        MathUtils.lerp(capsuleH / 2f, Math.max(rowRadius, capsuleH / 2f),
                                radiusMix));
                radius = MathUtils.lerp(radius, Math.min(rowRadius, blob.height() / 2f),
                        progress(t, A_BLOB_FADE_START, A_BLOB_FADE_END));
                int color = (int) mArgb.evaluate(progress(t, A_IMPACT, A_COLOR_END,
                        Interpolators.FAST_OUT_SLOW_IN), INK_COLOR, bgColor);
                float blobAlpha = 1f - progress(t, A_BLOB_FADE_START, A_BLOB_FADE_END,
                        Interpolators.FAST_OUT_SLOW_IN);
                d.setBlob(blob, radius, color, blobAlpha);

                if (t < A_BREAK) {
                    d.setNeck(o.cx, o.cy, o.neckWidth() * d.originScaleForNeck(o.physical),
                            cx, impactCy, capsuleH * 0.8f, dp(0.8f));
                } else {
                    d.neckVisible = false;
                    if (!satSpawned) {
                        satSpawned = true;
                        satX = MathUtils.lerp(o.cx, impactCx, 0.45f);
                        satY = MathUtils.lerp(o.cy + o.halfH, impactCy - capsuleH / 2f, 0.45f);
                        satR = Math.max(dp(2.2f), Math.min(o.halfW, o.halfH) * 0.42f);
                    }
                }

                float revealAlpha = progress(t, A_REVEAL_START, A_REVEAL_ALPHA_END,
                        Interpolators.LINEAR_OUT_SLOW_IN);
                float blur = dp(22f) * (1f - progress(t, A_REVEAL_START, A_REVEAL_BLUR_END,
                        Interpolators.FAST_OUT_SLOW_IN));
                transformRow(blob, revealAlpha, blur);
            }

            if (satSpawned) {
                float s = progress(t, A_BREAK, A_SATELLITE_END);
                d.satVisible = s < 1f;
                d.satX = MathUtils.lerp(satX, o.cx, Interpolators.FAST_OUT_SLOW_IN
                        .getInterpolation(s));
                d.satY = MathUtils.lerp(satY, o.cy + o.halfH * 0.3f,
                        Interpolators.FAST_OUT_SLOW_IN.getInterpolation(s));
                d.satR = satR * (1f - Interpolators.FAST_OUT_LINEAR_IN.getInterpolation(s));
            } else {
                d.satVisible = false;
            }
            d.origin = o;
        }

        private void disappearFrame(float t) {
            DropDrawable d = drawable;
            Origin o = origin;
            RectF s = startBounds;
            d.origin = o;
            d.satVisible = false;

            float seedIn = progress(t, D_SEED_IN_START, D_SEED_IN_END,
                    Interpolators.FAST_OUT_SLOW_IN);
            float seedOut = progress(t, D_SEED_OUT_START, DISAPPEAR_TOTAL,
                    Interpolators.FAST_OUT_SLOW_IN);
            d.originScale = o.physical ? 1f : seedIn * (1f - seedOut);

            if (t < D_SHRINK_END) {
                float q = progress(t, 0f, D_SHRINK_END, Interpolators.FAST_OUT_SLOW_IN);
                float cx = MathUtils.lerp(s.centerX(), impactX(s), q);
                float halfW = MathUtils.lerp(s.width() / 2f, capsuleW / 2f, q);
                float bottom = MathUtils.lerp(s.bottom, s.top + capsuleH, q);
                blob.set(cx - halfW, s.top, cx + halfW, bottom);
                float radius = MathUtils.lerp(Math.min(rowRadius, blob.height() / 2f),
                        blob.height() / 2f, q);
                int color = (int) mArgb.evaluate(progress(t, D_COLOR_START, D_SHRINK_END,
                        Interpolators.FAST_OUT_SLOW_IN), bgColor, INK_COLOR);
                float blobAlpha = progress(t, 0f, D_BLOB_IN_END, Interpolators.LINEAR_OUT_SLOW_IN);
                d.setBlob(blob, radius, color, blobAlpha);
                d.neckVisible = false;
                float rowAlpha = 1f - progress(t, D_ROW_FADE_START, D_ROW_FADE_END,
                        Interpolators.FAST_OUT_SLOW_IN);
                float blur = dp(22f) * progress(t, 0f, D_ROW_FADE_END,
                        Interpolators.FAST_OUT_SLOW_IN);
                transformRow(blob, rowAlpha, blur);
            } else {
                hideRow();
                float rise = progress(t, D_SHRINK_END, D_RISE_END, Interpolators.FAST_OUT_LINEAR_IN);
                float absorb = progress(t, D_RISE_END, DISAPPEAR_TOTAL,
                        Interpolators.FAST_OUT_SLOW_IN);
                float startCy = s.top + capsuleH / 2f;
                float hangY = o.cy + o.halfH + dropRadius * 0.9f;
                float cy = MathUtils.lerp(MathUtils.lerp(startCy, hangY, rise), o.cy, absorb);
                float cx = MathUtils.lerp(impactX(s), o.cx, rise);
                float halfW = MathUtils.lerp(capsuleW / 2f, dropRadius * 0.92f,
                        Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                                progress(t, D_SHRINK_END, D_SHRINK_END + 90f)));
                float halfH = MathUtils.lerp(capsuleH / 2f, dropRadius * 1.15f,
                        Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                                progress(t, D_SHRINK_END, D_SHRINK_END + 90f)));
                halfW *= 1f - absorb;
                halfH *= 1f - absorb;
                blob.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
                d.setBlob(blob, Math.min(halfW, halfH), INK_COLOR,
                        halfW > 0.5f ? 1f : 0f);
                if (t >= D_NECK_START && halfW > 0.5f) {
                    float neck = progress(t, D_NECK_START, D_RISE_END,
                            Interpolators.FAST_OUT_SLOW_IN);
                    float waist = Math.min(o.neckWidth(), 2f * halfW) * 0.75f * neck;
                    d.setNeck(o.cx, o.cy, o.neckWidth() * d.originScaleForNeck(o.physical),
                            cx, cy, Math.min(2f * halfH, 2f * halfW) * 0.8f,
                            Math.max(waist, dp(0.8f)));
                } else {
                    d.neckVisible = false;
                }
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
            colorMatrix.setScale(1f, 1f, 1f, MathUtils.constrain(alpha, 0f, 1f));
            RenderEffect effect = RenderEffect.createColorFilterEffect(
                    new ColorMatrixColorFilter(colorMatrix));
            if (blur > 0.5f) {
                effect = RenderEffect.createChainEffect(effect,
                        RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL));
            }
            view.setRenderEffect(effect);
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
            if (view.getWidth() <= 0 || height <= 0) return false;
            out.set(x, y, x + view.getWidth(), y + height);
            return out.height() > 0f;
        }
    }

    private static final class DropDrawable extends Drawable {
        private final Paint mInk = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBlobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mNeck = new Path();
        private final RectF mTmp = new RectF();
        final RectF blob = new RectF();

        Origin origin;
        float originScale;
        float blobRadius;
        int blobColor;
        float blobAlpha;
        boolean neckVisible;
        boolean satVisible;
        float satX;
        float satY;
        float satR;

        DropDrawable() {
            mInk.setStyle(Paint.Style.FILL);
            mInk.setColor(INK_COLOR);
            mBlobPaint.setStyle(Paint.Style.FILL);
        }

        float originScaleForNeck(boolean physical) {
            return physical ? 1f : Math.max(originScale, 0.35f);
        }

        void setBlob(RectF rect, float radius, int color, float alpha) {
            blob.set(rect);
            blobRadius = radius;
            blobColor = color;
            blobAlpha = alpha;
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

        @Override
        public void draw(Canvas canvas) {
            Origin o = origin;
            if (o == null) return;
            if (originScale > 0.01f) {
                float hw = o.halfW * originScale;
                float hh = o.halfH * originScale;
                mTmp.set(o.cx - hw, o.cy - hh, o.cx + hw, o.cy + hh);
                canvas.drawRoundRect(mTmp, hh, hh, mInk);
            }
            if (neckVisible) {
                canvas.drawPath(mNeck, mInk);
            }
            if (satVisible && satR > 0.3f) {
                canvas.drawCircle(satX, satY, satR, mInk);
            }
            if (blobAlpha > 0.003f && !blob.isEmpty()) {
                mBlobPaint.setColor(blobColor);
                mBlobPaint.setAlpha(Math.round(255 * blobAlpha));
                canvas.drawRoundRect(blob, blobRadius, blobRadius, mBlobPaint);
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
