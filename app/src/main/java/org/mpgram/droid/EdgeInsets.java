package org.mpgram.droid;

import android.app.Activity;
import android.view.View;
import android.view.WindowInsets;

/**
 * Android 15+ с targetSdk 35 принудительно рисует окно под системными панелями, и
 * adjustResize перестаёт поднимать строку ввода над клавиатурой. Здесь содержимое окна
 * отодвигается от панелей и клавиатуры вручную. Вызывать только на API 35+.
 */
public class EdgeInsets {

    public static void apply(Activity a) {
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
