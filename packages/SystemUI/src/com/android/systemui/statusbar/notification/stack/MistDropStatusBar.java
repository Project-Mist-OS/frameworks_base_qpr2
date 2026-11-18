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

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.view.View;

import java.lang.ref.WeakReference;

public final class MistDropStatusBar {

    private static WeakReference<View> sView;
    private static float sAlpha = 1f;

    private MistDropStatusBar() {
    }

    public static void attach(View view) {
        sView = new WeakReference<>(view);
        sAlpha = 1f;
    }

    public static void detach(View view) {
        if (sView != null && sView.get() == view) {
            view.setRenderEffect(null);
            sView = null;
            sAlpha = 1f;
        }
    }

    static void setAlpha(float alpha) {
        View view = sView != null ? sView.get() : null;
        if (view == null) return;
        float a = Math.max(0f, Math.min(1f, alpha));
        if (Math.abs(a - sAlpha) < 0.004f) return;
        sAlpha = a;
        if (a >= 0.996f) {
            sAlpha = 1f;
            view.setRenderEffect(null);
            return;
        }
        ColorMatrix matrix = new ColorMatrix();
        matrix.setScale(1f, 1f, 1f, a);
        view.setRenderEffect(RenderEffect.createColorFilterEffect(
                new ColorMatrixColorFilter(matrix)));
    }
}
