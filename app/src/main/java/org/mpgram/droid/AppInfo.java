package org.mpgram.droid;

import android.content.Context;
import android.content.pm.PackageManager;

/** Сведения о приложении. Версия берётся из app/build.gradle (versionName). */
public class AppInfo {

    private static String cached;

    /** Версия не меняется за время жизни процесса, а getPackageInfo это IPC: спрашиваем один раз. */
    public static synchronized String versionName(Context ctx) {
        if (cached != null) {
            return cached;
        }
        try {
            String v = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            cached = v == null ? "?" : v;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
        return cached;
    }
}
