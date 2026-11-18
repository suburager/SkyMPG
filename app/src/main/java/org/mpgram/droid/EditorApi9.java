package org.mpgram.droid;

import android.content.SharedPreferences;

/**
 * Editor.apply() (Android 2.3, API 9). Вынесено в отдельный класс: на API 4 верификатор dalvik
 * отвергает весь класс с неразрешимой ссылкой, а этот класс на старых версиях не загружается
 * (Api вызывает его только при SDK >= 9).
 */
class EditorApi9 {

    static void apply(SharedPreferences.Editor e) {
        e.apply();
    }
}
