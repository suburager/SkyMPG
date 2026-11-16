package org.mpgram.droid;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * Страховка для старых Android (в том числе 2.3): иногда adjustResize не срабатывает, окно не
 * уменьшается и строка ввода остаётся под клавиатурой. Если содержимое окна уходит под
 * клавиатуру, поднимаем его нижней границей на высоту перекрытия; когда клавиатура убрана,
 * отступ снимается. Если система сама уменьшила окно, ничего не меняется.
 */
public class KeyboardFix {

    public static void attach(Activity a) {
        final View root = a.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        final int min = Ui.px(a, 80);
        final Rect frame = new Rect();
        final int[] loc = new int[2];
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            private int pad;

            public void onGlobalLayout() {
                root.getWindowVisibleDisplayFrame(frame);
                root.getLocationOnScreen(loc);
                int covered = loc[1] + root.getHeight() - frame.bottom;
                int want = covered > min ? covered : 0;
                if (want != pad) {
                    pad = want;
                    root.setPadding(root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), want);
                }
            }
        });
    }
}
