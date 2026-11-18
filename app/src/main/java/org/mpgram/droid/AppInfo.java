package org.mpgram.droid;

import android.content.Context;
import android.content.pm.ApplicationInfo;
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

    /**
     * ApplicationInfo этого приложения. Context.getApplicationInfo() есть только с API 4 и на
     * Android 1.5 валит верификатор вызывающего класса, поэтому берём через PackageManager (API 1).
     */
    public static ApplicationInfo applicationInfo(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).applicationInfo;
        } catch (PackageManager.NameNotFoundException e) {
            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = ctx.getPackageName();
            return ai;
        }
    }
}
