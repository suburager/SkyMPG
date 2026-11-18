package org.mpgram.droid;

import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;

/**
 * Несколько файлов из одного выбора в галерее (Android 4.1+). Сам класс не ссылается на новые
 * API и безопасен на любой версии; ClipData живёт в MultiPickApi16, который грузится только
 * при SDK >= 16. На старых версиях всегда null: выбран один файл (data.getData()).
 */
public class MultiPick {

    /** Все выбранные Uri или null, если выбран один файл (он лежит в data.getData()). */
    public static ArrayList<Uri> uris(Intent data) {
        if (Api.sdkLevel() >= 16) {
            return MultiPickApi16.uris(data);
        }
        return null;
    }
}
