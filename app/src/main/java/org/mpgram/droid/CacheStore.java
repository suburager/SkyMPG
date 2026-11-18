package org.mpgram.droid;

import android.content.Context;

import java.io.File;

/**
 * Кэш приложения на диске: картинки и превью, аватарки, страницы чатов и список чатов.
 * Настройки, папки, ПИН-код и сама сессия сюда не входят и при очистке остаются.
 */
public class CacheStore {

    /** Размер всего кэша в байтах. Читает диск, вызывать не из UI-потока. */
    public static long size(Context ctx) {
        long total = dirSize(new File(ctx.getFilesDir(), "img"));
        total += dirSize(new File(ctx.getFilesDir(), "ava"));
        total += dirSize(new File(ctx.getCacheDir(), "pages"));
        File[] files = ctx.getFilesDir().listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                String n = files[i].getName();
                if (n.startsWith("chats") && (n.endsWith(".json") || n.endsWith(".json.tmp"))) {
                    total += files[i].length();
                }
            }
        }
        return total;
    }

    /** Удаляет весь кэш. Читает и пишет диск, вызывать не из UI-потока. */
    public static void clear(Context ctx) {
        ImageLoader.clearDisk(ctx);
        AvatarLoader.clearDisk(ctx);
        PageCache.clearAll(ctx);
        ChatCache.clear(ctx);
        ImageLoader.clearMemory();
    }

    /** "512 КБ" или "3,4 МБ" на языке интерфейса. */
    public static String format(Context ctx, long bytes) {
        if (bytes < 1024L * 1024L) {
            long kb = bytes == 0 ? 0 : Math.max(1, (bytes + 512) / 1024);
            return ctx.getString(R.string.cache_size_kb, String.valueOf(kb));
        }
        long tenths = (bytes * 10 + 524288L) / 1048576L;
        String lang = ctx.getResources().getConfiguration().locale.getLanguage();
        String sep = ("ru".equals(lang) || "uk".equals(lang)) ? "," : ".";
        return ctx.getString(R.string.cache_size_mb, (tenths / 10) + sep + (tenths % 10));
    }

    private static long dirSize(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        long total = 0;
        for (int i = 0; i < files.length; i++) {
            total += files[i].isDirectory() ? dirSize(files[i]) : files[i].length();
        }
        return total;
    }
}
