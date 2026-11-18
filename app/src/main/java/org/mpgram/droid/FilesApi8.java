package org.mpgram.droid;

import android.content.Context;

import java.io.File;

/**
 * Context.getExternalFilesDir() (Android 2.2, API 8). Отдельный класс по той же причине, что
 * и EditorApi9: на API < 8 он не загружается, а Downloader остаётся верифицируемым.
 */
class FilesApi8 {

    static File get(Context ctx, String type) {
        return ctx.getExternalFilesDir(type);
    }
}
