package org.mpgram.droid;

import android.app.Activity;
import android.view.View;
import android.view.WindowInsets;

/**
 * Работа с WindowInsets (API 20), Insets (API 29) и WindowInsets.Type (API 30).
 * Грузится только при SDK >= 30, поэтому на старых Android верификатор его не видит.
 */
class EdgeInsetsApi30 {

    static void apply(Activity a) {
        View root = a.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets w) {
                android.graphics.Insets bars = w.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = w.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            }
        });
        root.requestApplyInsets();
    }
}
