package org.mpgram.droid;

import android.app.Activity;

/**
 * Android 15+ с targetSdk 35 принудительно рисует окно под системными панелями, и
 * adjustResize перестаёт поднимать строку ввода над клавиатурой. Здесь содержимое окна
 * отодвигается от панелей и клавиатуры вручную. На старых Android инсеты не нужны:
 * класс ничего не делает и не ссылается на новые API (вся работа в EdgeInsetsApi30).
 */
public class EdgeInsets {

    public static void apply(Activity a) {
        if (Api.sdkLevel() >= 30) {
            EdgeInsetsApi30.apply(a);
        }
    }
}
