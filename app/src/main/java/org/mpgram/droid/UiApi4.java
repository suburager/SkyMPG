package org.mpgram.droid;

import android.view.View;

/**
 * View.setContentDescription() (Android 1.6, API 4). Доступность, на 1.5 просто не нужна.
 * Отдельный класс: прямой вызов в ChatActivity валит её верификатор на 1.5, а этот класс
 * загружается только при SDK >= 4 (см. UiApi4 в вызывающих местах).
 */
class UiApi4 {

    static void cd(View v, CharSequence s) {
        v.setContentDescription(s);
    }
}
