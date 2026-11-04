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

package com.android.settingslib.collapsingtoolbar;

import static android.text.Layout.HYPHENATION_FREQUENCY_NORMAL_FAST;

import android.animation.TimeInterpolator;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.text.LineBreakConfig;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toolbar;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.android.settingslib.collapsingtoolbar.widget.ScrollableToolbarItemLayout;
import com.android.settingslib.widget.SettingsThemeHelper;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingtoolbar.FloatingToolbarLayout;

import java.util.List;

/**
 * A delegate that allows to use the collapsing toolbar layout in hosts that
 * doesn't want/need to extend from {@link CollapsingToolbarBaseActivity} or from
 * {@link CollapsingToolbarBaseFragment}.
 *
 * <p>When running on SDK 36+ with the Expressive theme, this delegate adds Samsung One UI-style
 * behavior on top of the Material CollapsingToolbarLayout:
 * <ul>
 *   <li>A large title centered in the extended header, cross-fading (Material "fade" title mode)
 *       to a compact start-aligned toolbar title.</li>
 *   <li>The back button (bottom-start) and toolbar buttons (primary / secondary / action,
 *       bottom-end) start low in the extended header and glide up into the pinned toolbar row
 *       using the One UI ease-out curve (see {@code updateToolbarButtonsGlide()}). Toolbar menu
 *       items stay pinned in the toolbar row.</li>
 *   <li>App bar elevation applied once the header starts collapsing.</li>
 * </ul>
 */
public class CollapsingToolbarDelegate {
    private static final String TAG = "CTBdelegate";
    private static final float EXPANDED_FADE_END = 0.45f;
    private static final float COLLAPSED_FADE_START = 0.45f;
    private static final float MAX_ELEVATION_DP = 4f;
    private static final float EXPANDED_TITLE_PARALLAX_DP = 10f;

    /** Interface to be implemented by the host of the Collapsing Toolbar. */
    public interface HostCallback {
        /**
         * Called when a Toolbar should be set on the host.
         *
         * <p>If the host wants action bar to be modified, it should return it.
         */
        @Nullable
        ActionBar setActionBar(Toolbar toolbar);

        /** Sets support tool bar and return support action bar, this is for AppCompatActivity. */
        @Nullable
        default androidx.appcompat.app.ActionBar setActionBar(
                androidx.appcompat.widget.Toolbar toolbar) {
            return null;
        }

        /** Sets a title on the host. */
        void setOuterTitle(CharSequence title);
    }

    private static final class OneUiInterpolator implements TimeInterpolator {
        private static final float P1X = 0.25f;
        private static final float P1Y = 0.46f;
        private static final float P2X = 0.45f;
        private static final float P2Y = 0.94f;

        @Override
        public float getInterpolation(float t) {
            float u = t;
            for (int i = 0; i < 8; i++) {
                float ux2 = u * u;
                float ux3 = ux2 * u;
                float bx = 3f * P1X * u * (1f - u) * (1f - u)
                        + 3f * P2X * ux2 * (1f - u)
                        + ux3;
                float dbx = 3f * P1X * (1f - 4f * u + 3f * ux2)
                        + 3f * P2X * (2f * u - 3f * ux2)
                        + 3f * ux2;
                if (Math.abs(dbx) < 1e-6f) break;
                u = u - (bx - t) / dbx;
                u = Math.max(0f, Math.min(1f, u));
            }
            float ux2 = u * u;
            float ux3 = ux2 * u;
            return 3f * P1Y * u * (1f - u) * (1f - u)
                    + 3f * P2Y * ux2 * (1f - u)
                    + ux3;
        }
    }

    private class OneUiOffsetListener implements AppBarLayout.OnOffsetChangedListener {
        private final float mMaxElevationPx;
        private final float mParallaxPx;

        OneUiOffsetListener(@NonNull Context context) {
            float density = context.getResources().getDisplayMetrics().density;
            mMaxElevationPx = MAX_ELEVATION_DP * density;
            mParallaxPx = EXPANDED_TITLE_PARALLAX_DP * density;
        }

        @Override
        public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
            mLastVerticalOffset = verticalOffset;

            if (verticalOffset < 0) {
                appBarLayout.setElevation(mMaxElevationPx);
            } else {
                appBarLayout.setElevation(0f);
            }

            updateToolbarButtonsGlide();
        }
    }

    /**
     * One UI style "glide up" of the toolbar buttons (primary / secondary / action).
     *
     * <p>Expanded: the back button sits low at the bottom-start of the extended header and the
     * buttons at its bottom-end (within easy reach, clear of the centered title). Collapsed: they
     * rest in the pinned toolbar row. In between they all glide up together along the One UI
     * ease-out curve, tracking the header as it scrolls away.
     *
     * <p>The container is a pinned child ({@code layout_collapseMode="pin"}), so within the
     * scroll range its layout position is {@code base - verticalOffset}; the translation below is
     * therefore measured from the toolbar-row position and is 0 when fully collapsed.
     */
    private void updateToolbarButtonsGlide() {
        final View container = mToolbarButtonsContainer;
        final View back = mBackButton;
        final AppBarLayout appBarLayout = mAppBarLayout;
        final CollapsingToolbarLayout collapsingToolbar = mCollapsingToolbarLayout;
        // Only the Expressive (One UI style) header uses the glide; other layouts keep the
        // buttons in the toolbar row.
        if (mOneUiOffsetListener == null || appBarLayout == null || collapsingToolbar == null) {
            return;
        }

        // Back button and buttons container share the same top and height (actionBarSize), so
        // either one can be the reference. The back button is always visible; the container is
        // GONE when no button is enabled.
        View reference = null;
        if (back != null && back.getVisibility() == View.VISIBLE && back.getHeight() > 0) {
            reference = back;
        } else if (container != null && container.getVisibility() == View.VISIBLE
                && container.getHeight() > 0) {
            reference = container;
        }

        final int totalScrollRange = appBarLayout.getTotalScrollRange();
        float translationY = 0f;
        if (reference != null && totalScrollRange > 0 && collapsingToolbar.getHeight() > 0) {
            final float collapseRatio = Math.max(0f, Math.min(1f,
                    Math.abs(mLastVerticalOffset) / (float) totalScrollRange));
            // Bottom when resting in the toolbar row (undo the pin counter-offset).
            final float restingBottom = reference.getBottom() + mLastVerticalOffset;
            final float expandedBottom =
                    collapsingToolbar.getHeight() - mExpandedButtonsBottomMarginPx;
            final float maxTranslationY = Math.max(0f, expandedBottom - restingBottom);
            translationY = maxTranslationY
                    * (1f - mButtonsInterpolator.getInterpolation(collapseRatio));
        }

        if (back != null) {
            back.setTranslationY(translationY);
        }
        if (container != null) {
            container.setTranslationY(translationY);
        }
    }

    /**
     * Replaces the Toolbar's navigation icon with {@link #mBackButton}, a sibling view in the
     * collapsing header that glides with the other toolbar buttons.
     *
     * <p>The Toolbar's own navigation view is kept (the ActionBar still owns its click handling:
     * up navigation, back stack, etc.) but made invisible and non-touchable; our back button
     * forwards its click to it. The Toolbar is pinned and only {@code actionBarSize} tall, so the
     * navigation view itself can't move through the header without losing touches.
     */
    private void initGlidingBackButton(@NonNull ViewGroup toolbarView) {
        final CollapsingToolbarLayout collapsingToolbar = mCollapsingToolbarLayout;
        if (!mIsExpressiveTheme || collapsingToolbar == null) {
            return;
        }

        if (mBackButton == null || mBackButton.getParent() != collapsingToolbar) {
            if (mBackButton != null && mButtonsLayoutListener != null) {
                mBackButton.removeOnLayoutChangeListener(mButtonsLayoutListener);
            }
            mBackButton = (ImageButton) LayoutInflater.from(collapsingToolbar.getContext())
                    .inflate(R.layout.settingslib_expressive_toolbar_back_button,
                            collapsingToolbar, false);
            collapsingToolbar.addView(mBackButton);
            if (mButtonsLayoutListener == null) {
                mButtonsLayoutListener =
                        (v, l, t, r, b, ol, ot, or, ob) -> updateToolbarButtonsGlide();
            }
            mBackButton.addOnLayoutChangeListener(mButtonsLayoutListener);
        }

        mBackButton.setOnClickListener(v -> {
            final View navigationButton = findNavigationButton(toolbarView);
            if (navigationButton != null) {
                navigationButton.performClick();
                return;
            }
            // Fallback so the button is never dead if the Toolbar has no navigation view.
            Context context = v.getContext();
            while (context instanceof ContextWrapper && !(context instanceof Activity)) {
                context = ((ContextWrapper) context).getBaseContext();
            }
            if (context instanceof Activity) {
                ((Activity) context).onBackPressed();
            }
        });
        hideToolbarNavigationButton(toolbarView);
        // The navigation view may be created after the ActionBar is attached; retry once laid out.
        toolbarView.post(() -> hideToolbarNavigationButton(toolbarView));
    }

    private void hideToolbarNavigationButton(@NonNull ViewGroup toolbarView) {
        final View navigationButton = findNavigationButton(toolbarView);
        if (navigationButton == null) {
            return;
        }
        if (mBackButton != null && navigationButton.getContentDescription() != null) {
            mBackButton.setContentDescription(navigationButton.getContentDescription());
        }
        navigationButton.setAlpha(0f);
        navigationButton.setClickable(false);
        navigationButton.setLongClickable(false);
        navigationButton.setFocusable(false);
        navigationButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The Toolbar's navigation (home-as-up) view is its first direct ImageButton child. */
    @Nullable
    private static View findNavigationButton(@NonNull ViewGroup toolbarView) {
        for (int i = 0; i < toolbarView.getChildCount(); i++) {
            final View child = toolbarView.getChildAt(i);
            if (child instanceof ImageButton) {
                return child;
            }
        }
        return null;
    }

    private static final float TOOLBAR_LINE_SPACING_MULTIPLIER = 1.1f;

    @Nullable
    private CoordinatorLayout mCoordinatorLayout;
    @Nullable
    private CollapsingToolbarLayout mCollapsingToolbarLayout;
    @Nullable
    private AppBarLayout mAppBarLayout;
    @NonNull
    private Toolbar mToolbar;
    @Nullable
    private View mToolbarButtonsContainer;
    @Nullable
    private MaterialButton mPrimaryButton;
    @Nullable
    private MaterialButton mSecondaryButton;
    @Nullable
    private MaterialButton mActionButton;
    @Nullable
    private MaterialButton mActionIconOnlyButton;
    @NonNull
    private FrameLayout mContentFrameLayout;
    @NonNull
    private final HostCallback mHostCallback;

    private boolean mUseCollapsingToolbar;
    private boolean mIsExpressiveTheme;

    @Nullable
    private FloatingToolbarLayout mFloatingToolbarLayout;

    @Nullable
    private OneUiOffsetListener mOneUiOffsetListener;

    private final OneUiInterpolator mButtonsInterpolator = new OneUiInterpolator();
    private int mLastVerticalOffset;
    private float mExpandedButtonsBottomMarginPx;
    @Nullable
    private View.OnLayoutChangeListener mButtonsLayoutListener;
    @Nullable
    private ImageButton mBackButton;

    public CollapsingToolbarDelegate(@NonNull HostCallback hostCallback) {
        this(hostCallback, /* useCollapsingToolbar= */ true);
    }

    public CollapsingToolbarDelegate(@NonNull HostCallback hostCallback,
            boolean useCollapsingToolbar) {
        mHostCallback = hostCallback;
        mUseCollapsingToolbar = useCollapsingToolbar;
    }

    /** Method to call that creates the root view of the collapsing toolbar. */
    @SuppressWarnings("RestrictTo")
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return onCreateView(inflater, container, null);
    }

    /** Method to call that creates the root view of the collapsing toolbar. */
    @SuppressWarnings("RestrictTo")
    View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            Activity activity) {
        int layoutId;
        boolean useCollapsingToolbar =
                mUseCollapsingToolbar || Build.VERSION.SDK_INT < Build.VERSION_CODES.S;
        Context context = (activity != null) ? activity : inflater.getContext();
        mIsExpressiveTheme = SettingsThemeHelper.isExpressiveTheme(context);
        if (useCollapsingToolbar) {
            if (mIsExpressiveTheme) {
                if (activity instanceof AppCompatActivity) {
                    layoutId = R.layout.settingslib_expressive_collapsing_toolbar_appcompat_layout;
                } else {
                    layoutId = R.layout.settingslib_expressive_collapsing_toolbar_base_layout;
                }
            } else {
                layoutId = R.layout.collapsing_toolbar_base_layout;
            }
        } else {
            layoutId = R.layout.non_collapsing_toolbar_base_layout;
        }

        final View view = inflater.inflate(layoutId, container, false);
        if (view instanceof CoordinatorLayout) {
            mCoordinatorLayout = (CoordinatorLayout) view;
        }
        mCollapsingToolbarLayout = view.findViewById(R.id.collapsing_toolbar);
        mAppBarLayout = view.findViewById(R.id.app_bar);

        if (!useCollapsingToolbar) {
            // In the non-collapsing toolbar layout, set the background of the app bar to the same
            // as the activity background so that it covers items extending above the bounds of the
            // list for edge-to-edge.
            TypedArray ta = container.getContext().obtainStyledAttributes(new int[]{
                    android.R.attr.windowBackground});
            Drawable background = ta.getDrawable(0);
            ta.recycle();
            mAppBarLayout.setBackground(background);
        }

        initCollapsingToolbar(mCollapsingToolbarLayout, mAppBarLayout);
        mContentFrameLayout = view.findViewById(R.id.content_frame);

        if (activity instanceof AppCompatActivity) {
            Log.d(TAG, "onCreateView: from AppCompatActivity and sub-class.");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                initSupportActionBar(inflater);
            } else {
                initRSupportActionBar(view);
            }
        } else {
            Log.d(TAG, "onCreateView: from NonAppCompatActivity.");
            mToolbar = view.findViewById(R.id.action_bar);
            final ActionBar actionBar = mHostCallback.setActionBar(mToolbar);
            // Enable title and home button by default
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(true);
                actionBar.setHomeButtonEnabled(true);
                if (useCollapsingToolbar && mIsExpressiveTheme) {
                    actionBar.setHomeAsUpIndicator(R.drawable.settingslib_expressive_icon_back);
                }
            }
            if (useCollapsingToolbar && mIsExpressiveTheme) {
                initGlidingBackButton(mToolbar);
            }
        }

        initToolbarButtonsContainer(view.findViewById(R.id.toolbar_buttons_container));
        initToolbarPrimaryButton(view.findViewById(R.id.primary_button));
        initToolbarSecondaryButton(view.findViewById(R.id.secondary_button));
        initToolbarActionButton(view.findViewById(R.id.action_button));
        initToolbarActionIconOnlyButton(view.findViewById(R.id.action_icon_only_button));

        FloatingToolbarLayout floatingToolbar = view.findViewById(R.id.floating_toolbar);
        if (floatingToolbar != null) {
            initFloatingToolbar(context, floatingToolbar);
        }
        return view;
    }

    /**
     * Initialize the collapsing toolbar layout.
     *
     * <p>On SDK 36+ with the Expressive theme, this method also:
     * <ul>
     *   <li>Disables Material's native title animations.</li>
     *   <li>Injects the One UI custom expanded and collapsed title views.</li>
     *   <li>Attaches the {@link OneUiOffsetListener}.</li>
     * </ul>
     */
    public void initCollapsingToolbar(CollapsingToolbarLayout collapsingToolbarLayout,
            AppBarLayout appBarLayout) {
        if (collapsingToolbarLayout != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            collapsingToolbarLayout.setLineSpacingMultiplier(TOOLBAR_LINE_SPACING_MULTIPLIER);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                collapsingToolbarLayout.setHyphenationFrequency(HYPHENATION_FREQUENCY_NORMAL_FAST);
                collapsingToolbarLayout.setStaticLayoutBuilderConfigurer(
                        builder -> builder.setLineBreakConfig(
                                new LineBreakConfig.Builder()
                                        .setLineBreakWordStyle(
                                                LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
                                        .build()));
            }
        }

        if (mIsExpressiveTheme && collapsingToolbarLayout != null && appBarLayout != null) {
            setupOneUiTitleAnimation(collapsingToolbarLayout, appBarLayout);
        }

        autoSetCollapsingToolbarLayoutScrolling(appBarLayout);
    }

    private void setupOneUiTitleAnimation(
            @NonNull CollapsingToolbarLayout collapsingToolbarLayout,
            @NonNull AppBarLayout appBarLayout) {
        appBarLayout.setElevation(0f);

        if (mOneUiOffsetListener != null) {
            appBarLayout.removeOnOffsetChangedListener(mOneUiOffsetListener);
        }
        mOneUiOffsetListener = new OneUiOffsetListener(appBarLayout.getContext());
        appBarLayout.addOnOffsetChangedListener(mOneUiOffsetListener);
    }

    private float getExpandedTitleSizeSp(@NonNull Context context) {
        try {
            return context.getResources()
                    .getDimension(R.dimen.settingslib_oneui_expanded_title_size)
                    / context.getResources().getDisplayMetrics().scaledDensity;
        } catch (android.content.res.Resources.NotFoundException e) {
            return 34f;
        }
    }

    private float getCollapsedTitleSizeSp(@NonNull Context context) {
        try {
            return context.getResources()
                    .getDimension(R.dimen.settingslib_oneui_collapsed_title_size)
                    / context.getResources().getDisplayMetrics().scaledDensity;
        } catch (android.content.res.Resources.NotFoundException e) {
            return 20f;
        }
    }

    private int resolveColorOnSurface(@NonNull Context context) {
        TypedArray ta = context.obtainStyledAttributes(
                new int[]{android.R.attr.textColorPrimary});
        int color = ta.getColor(0, 0xFF000000 /* fallback black */);
        ta.recycle();
        return color;
    }

    /** Initialize toolbar buttons container. */
    public void initToolbarButtonsContainer(View toolbarButtonsContainer) {
        if (mToolbarButtonsContainer != null && mButtonsLayoutListener != null) {
            mToolbarButtonsContainer.removeOnLayoutChangeListener(mButtonsLayoutListener);
        }
        mToolbarButtonsContainer = toolbarButtonsContainer;
        if (toolbarButtonsContainer == null) {
            return;
        }
        mExpandedButtonsBottomMarginPx = getExpandedButtonsBottomMarginPx(
                toolbarButtonsContainer.getContext());
        // The container is GONE until a button is enabled and is laid out after the last offset
        // callback, so re-apply the glide whenever it is (re)laid out.
        if (mButtonsLayoutListener == null) {
            mButtonsLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> updateToolbarButtonsGlide();
        }
        toolbarButtonsContainer.addOnLayoutChangeListener(mButtonsLayoutListener);
    }

    private float getExpandedButtonsBottomMarginPx(@NonNull Context context) {
        try {
            return context.getResources()
                    .getDimension(R.dimen.settingslib_oneui_toolbar_buttons_expanded_margin_bottom);
        } catch (android.content.res.Resources.NotFoundException e) {
            return 16f * context.getResources().getDisplayMetrics().density;
        }
    }

    /** Initialize toolbar's primary button. */
    public void initToolbarPrimaryButton(MaterialButton primaryButton) {
        mPrimaryButton = primaryButton;
    }

    /** Initialize toolbar's secondary button. */
    public void initToolbarSecondaryButton(MaterialButton secondaryButton) {
        mSecondaryButton = secondaryButton;
    }

    /** Initialize toolbar's action button. */
    public void initToolbarActionButton(MaterialButton actionButton) {
        mActionButton = actionButton;
    }

    /** Initialize toolbar's action icon only button. */
    public void initToolbarActionIconOnlyButton(MaterialButton actionButtonIconOnly) {
        mActionIconOnlyButton = actionButtonIconOnly;
    }

    /**
     * Initialize the floating toolbar.
     *
     * @param context
     * @param floatingToolbarLayout may be null on layouts that don't include the floating toolbar
     */
    public void initFloatingToolbar(@NonNull Context context,
            @Nullable FloatingToolbarLayout floatingToolbarLayout) {
        mFloatingToolbarLayout = floatingToolbarLayout;
    }

    /** Return an instance of CoordinatorLayout. */
    @Nullable
    public CoordinatorLayout getCoordinatorLayout() {
        return mCoordinatorLayout;
    }

    /** Sets the title on the collapsing layout and delegates to host. */
    public void setTitle(CharSequence title) {
        if (mCollapsingToolbarLayout != null) {
            mCollapsingToolbarLayout.setTitle(title);
        }
        mHostCallback.setOuterTitle(title);
    }

    /** Returns an instance of collapsing toolbar. */
    @Nullable
    public CollapsingToolbarLayout getCollapsingToolbarLayout() {
        return mCollapsingToolbarLayout;
    }

    /** Return the content frame layout. */
    @NonNull
    public FrameLayout getContentFrameLayout() {
        return mContentFrameLayout;
    }

    public Toolbar getToolbar() {
        return mToolbar;
    }

    /** Return an instance of app bar. */
    @Nullable
    public AppBarLayout getAppBarLayout() {
        return mAppBarLayout;
    }

    /**
     * Sets the visibility of the floating toolbar.
     * @param visible
     */
    public void setFloatingToolbarVisibility(boolean visible) {
        if (mFloatingToolbarLayout == null) {
            return;
        }
        mFloatingToolbarLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Sets the toolbar items  for the floating toolbar.
     * @param itemList
     */
    public void setToolbarItems(List<ScrollableToolbarItemLayout.ToolbarItem> itemList) {
        if (mFloatingToolbarLayout == null) {
            return;
        }

        ScrollableToolbarItemLayout layout = mFloatingToolbarLayout.findViewById(
                R.id.floating_toolbar_items);
        if (layout == null) {
            return;
        }

        layout.onItemSelected(itemList);
    }

    private @Nullable ScrollableToolbarItemLayout getToolbarItemsLayout() {
        if (mFloatingToolbarLayout == null) {
            return null;
        }
        return mFloatingToolbarLayout.findViewById(R.id.floating_toolbar_items);
    }

    /**
     * Sets the item selected listener for the floating toolbar.
     */
    public void setOnItemSelectedListener(
            ScrollableToolbarItemLayout.OnItemSelectedListener listener) {
        var layout = getToolbarItemsLayout();
        if (layout != null) {
            layout.setOnItemSelectedListener(listener);
        }
    }

    /**
     * Removes the item selected listener for the floating toolbar.
     */
    public void removeOnItemSelectedListener() {
        var layout = getToolbarItemsLayout();
        if (layout != null) {
            layout.removeOnItemSelectedListener();
        }
    }

    /**
     * Sets the selected toolbar item by its zero-based index.
     */
    public void setSelectedItem(int position) {
        var layout = getToolbarItemsLayout();
        if (layout != null) {
            layout.setSelectedItem(position);
        }
    }

    private void initSupportActionBar(@NonNull LayoutInflater inflater) {
        if (mCollapsingToolbarLayout == null) {
            return;
        }

        if (!SettingsThemeHelper.isExpressiveTheme(inflater.getContext())) {
            mCollapsingToolbarLayout.removeAllViews();
            inflater.inflate(R.layout.support_toolbar, mCollapsingToolbarLayout);
        }

        final androidx.appcompat.widget.Toolbar supportToolbar =
                mCollapsingToolbarLayout.findViewById(R.id.support_action_bar);
        final androidx.appcompat.app.ActionBar actionBar =
                mHostCallback.setActionBar(supportToolbar);
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            if (mIsExpressiveTheme) {
                actionBar.setHomeAsUpIndicator(R.drawable.settingslib_expressive_icon_back);
            }
            actionBar.setDisplayShowTitleEnabled(!mIsExpressiveTheme);
        }

        if (mIsExpressiveTheme && mCollapsingToolbarLayout != null) {
            View buttonsContainer = mCollapsingToolbarLayout.findViewById(R.id.toolbar_buttons_container);
            if (buttonsContainer != null) {
                initToolbarButtonsContainer(buttonsContainer);
            }
            initGlidingBackButton(supportToolbar);
        }
    }

    private void initRSupportActionBar(View view) {
        view.findViewById(R.id.action_bar).setVisibility(View.GONE);
        final androidx.appcompat.widget.Toolbar supportToolbar =
                view.findViewById(R.id.support_action_bar);
        supportToolbar.setVisibility(View.VISIBLE);
        final androidx.appcompat.app.ActionBar actionBar =
                mHostCallback.setActionBar(supportToolbar);
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            if (mIsExpressiveTheme) {
                actionBar.setHomeAsUpIndicator(R.drawable.settingslib_expressive_icon_back);
            }
            actionBar.setDisplayShowTitleEnabled(!mIsExpressiveTheme);
        }

        if (mIsExpressiveTheme && mCollapsingToolbarLayout != null) {
            View buttonsContainer = mCollapsingToolbarLayout.findViewById(R.id.toolbar_buttons_container);
            if (buttonsContainer != null) {
                initToolbarButtonsContainer(buttonsContainer);
            }
            initGlidingBackButton(supportToolbar);
        }
    }

    public void registerToolbarCollapseBehavior(@NonNull Activity activity) {
        if (!(activity instanceof FragmentActivity)) {
            return;
        }
        FragmentManager fragmentManager = ((FragmentActivity) activity).getSupportFragmentManager();
        fragmentManager.registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(@NonNull FragmentManager fm,
                            @NonNull Fragment f, @NonNull View v,
                            @Nullable Bundle savedInstanceState) {
                        super.onFragmentViewCreated(fm, f, v, savedInstanceState);
                        if (!SettingsThemeHelper.isExpressiveTheme(activity)) {
                            return;
                        }
                        if (fm.getBackStackEntryCount() > 0) {
                            AppBarLayout appBarLayout = getAppBarLayout();
                            if (appBarLayout != null) {
                                appBarLayout.post(() -> appBarLayout.setExpanded(false, true));
                            } else {
                                Log.e(TAG, "AppBarLayout is null, can't collapse toolbar.");
                            }
                        }
                    }
                }, false);
    }

    private void autoSetCollapsingToolbarLayoutScrolling(AppBarLayout appBarLayout) {
        if (appBarLayout == null) {
            return;
        }
        final CoordinatorLayout.LayoutParams params =
                (CoordinatorLayout.LayoutParams) appBarLayout.getLayoutParams();
        final AppBarLayout.Behavior behavior = new IgnoreNonTouchScrollBehavior();
        behavior.setDragCallback(
                new AppBarLayout.Behavior.DragCallback() {
                    @Override
                    public boolean canDrag(@NonNull AppBarLayout appBarLayout) {
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU
                                || SettingsThemeHelper.isExpressiveTheme(
                                        appBarLayout.getContext())) {
                            return false;
                        } else {
                            return appBarLayout.getResources()
                                    .getConfiguration().orientation
                                    == Configuration.ORIENTATION_LANDSCAPE;
                        }
                    }
                });
        params.setBehavior(behavior);
    }

    /**
     * Show/Hide the primary button on the Toolbar.
     * @param enabled true to show the button, otherwise it's hidden.
     */
    public void setPrimaryButtonEnabled(boolean enabled) {
        if (mPrimaryButton == null) {
            return;
        }
        int visibility = enabled ? View.VISIBLE : View.GONE;
        mPrimaryButton.setVisibility(visibility);
        showOrHideToolbarButtonsContainer();
    }

    /** Set the icon to the primary button */
    public void setPrimaryButtonIcon(@NonNull Context context, @DrawableRes int drawableRes) {
        if (mPrimaryButton == null) {
            return;
        }
        mPrimaryButton.setIcon(
                context.getResources().getDrawable(drawableRes, context.getTheme()));
    }

    /** Set the OnClick listener to the primary button */
    public void setPrimaryButtonOnClickListener(@Nullable View.OnClickListener listener) {
        if (mPrimaryButton == null) {
            return;
        }
        mPrimaryButton.setOnClickListener(listener);
    }

    /** Set the content description to the primary button */
    public void setPrimaryButtonContentDescription(
            @Nullable CharSequence contentDescription) {
        if (mPrimaryButton == null) {
            return;
        }
        mPrimaryButton.setContentDescription(contentDescription);
    }

    /**
     * Show/Hide the secondary button on the Toolbar.
     * @param enabled true to show the button, otherwise it's hidden.
     */
    public void setSecondaryButtonEnabled(boolean enabled) {
        if (mSecondaryButton == null) {
            return;
        }
        int visibility = enabled ? View.VISIBLE : View.GONE;
        mSecondaryButton.setVisibility(visibility);
        showOrHideToolbarButtonsContainer();
    }

    /** Set the icon to the secondary button */
    public void setSecondaryButtonIcon(@NonNull Context context, @DrawableRes int drawableRes) {
        if (mSecondaryButton == null) {
            return;
        }
        mSecondaryButton.setIcon(
                context.getResources().getDrawable(drawableRes, context.getTheme()));
    }

    /** Set the OnClick listener to the secondary button */
    public void setSecondaryButtonOnClickListener(@Nullable View.OnClickListener listener) {
        if (mSecondaryButton == null) {
            return;
        }
        mSecondaryButton.setOnClickListener(listener);
    }

    /** Set the content description to the secondary button */
    public void setSecondaryButtonContentDescription(
            @Nullable CharSequence contentDescription) {
        if (mSecondaryButton == null) {
            return;
        }
        mSecondaryButton.setContentDescription(contentDescription);
    }

    /**
     * Show/Hide the action button on the Toolbar.
     * @param enabled true to show the button, otherwise it's hidden.
     */
    public void setActionButtonEnabled(boolean enabled) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }
        int visibility = enabled ? View.VISIBLE : View.GONE;

        updateActionButton(visibility);
        showOrHideToolbarButtonsContainer();
    }

    /**
     * Enable/Disable the action button on the Toolbar (being clickable or not).
     * @param clickable true to enable the button, otherwise it's disabled.
     */
    public void setActionButtonClickable(boolean clickable) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }
        mActionButton.setEnabled(clickable);
        mActionIconOnlyButton.setEnabled(clickable);
    }

    /** Set the icon to the action button */
    public void setActionButtonIcon(@NonNull Context context, @DrawableRes int drawableRes) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }
        mActionButton.setIcon(
                context.getResources().getDrawable(drawableRes, context.getTheme()));
        mActionIconOnlyButton.setIcon(
                context.getResources().getDrawable(drawableRes, context.getTheme()));
    }

    /** Set the text to the action button */
    public void setActionButtonText(@Nullable CharSequence text) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }

        boolean isTextNull = text == null;

        if (!isTextNull) {
            mActionButton.setText(text);
        }

        int visibility;
        if (mActionButton.getVisibility() == View.VISIBLE
                || mActionIconOnlyButton.getVisibility() == View.VISIBLE) {
            visibility = View.VISIBLE;
        } else {
            visibility = View.GONE;
        }

        updateActionButton(visibility);
    }

    /** Set the OnClick listener to the action button */
    public void setActionButtonOnClickListener(@Nullable View.OnClickListener listener) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }
        mActionButton.setOnClickListener(listener);
        mActionIconOnlyButton.setOnClickListener(listener);
    }

    /** Set the content description to the action button */
    public void setActionButtonContentDescription(
            @Nullable CharSequence contentDescription) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }
        mActionButton.setContentDescription(contentDescription);
        mActionIconOnlyButton.setContentDescription(contentDescription);
    }

    private void updateActionButton(int visibility) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }

        if (mActionButton.getText().length() == 0) {
            mActionButton.setVisibility(View.GONE);
            mActionIconOnlyButton.setVisibility(visibility);
        } else {
            mActionIconOnlyButton.setVisibility(View.GONE);
            mActionButton.setVisibility(visibility);
        }
    }

    private void showOrHideToolbarButtonsContainer() {
        if (mToolbarButtonsContainer == null) {
            return;
        }

        boolean enabled = false;

        // If at least one button inside toolbar buttons container is visible, make the container
        // visible, otherwise it should be invisible to remove the custom padding it requires
        if (mPrimaryButton != null) {
            enabled |= mPrimaryButton.getVisibility() == View.VISIBLE;
        }

        if (mSecondaryButton != null) {
            enabled |= mSecondaryButton.getVisibility() == View.VISIBLE;
        }

        if (mActionButton != null) {
            enabled |= mActionButton.getVisibility() == View.VISIBLE;
        }

        if (mActionIconOnlyButton != null) {
            enabled |= mActionIconOnlyButton.getVisibility() == View.VISIBLE;
        }

        int visibility = enabled ? View.VISIBLE : View.GONE;
        mToolbarButtonsContainer.setVisibility(visibility);
        updateToolbarButtonsGlide();
    }
}
