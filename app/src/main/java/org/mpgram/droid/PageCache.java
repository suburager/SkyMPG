package org.mpgram.droid;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Кэш последней страницы истории каждого недавно открытого чата: чат открывается сразу из файла,
 * пока сеть догружает свежее. Лежит в cache-каталоге (система может его очистить), хранит
 * не больше MAX_FILES чатов и стирается при выходе из аккаунта.
 */
public class PageCache {

    private static final int VERSION = 1;
    private static final int MAX_FILES = 16;

    private static File dir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "pages");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    private static String name(String peer, int topic) {
        StringBuffer sb = new StringBuffer("p");
        for (int i = 0; i < peer.length(); i++) {
            char ch = peer.charAt(i);
            if (ch >= '0' && ch <= '9') {
                sb.append(ch);
            } else if (ch == '-') {
                sb.append('m');
            } else {
                sb.append('_');
            }
        }
        sb.append('_').append(topic).append(".json");
        return sb.toString();
    }

    /** Ответ сервера (как его вернул getHistory) или null, если кэша нет или он от другой сессии. */
    public static JSONObject read(Context ctx, int sessionHash, String peer, int topic) {
        File f = new File(dir(ctx), name(peer, topic));
        if (!f.exists()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(readFile(f));
            if (root.optInt("v", 0) != VERSION || root.optInt("a", 0) != sessionHash) {
                return null;
            }
            return root.optJSONObject("res");
        } catch (Throwable e) {
            return null; // битый файл или нехватка памяти: чат откроется без кэша
        }
    }

    /** Блокирующая запись через временный файл; вызывать из фонового потока. */
    public static void write(Context ctx, int sessionHash, String peer, int topic, JSONObject res) {
        if (res == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("v", VERSION);
            root.put("a", sessionHash);
            root.put("res", res);
            File d = dir(ctx);
            File f = new File(d, name(peer, topic));
            File tmp = new File(f.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(root.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
            prune(d);
        } catch (Throwable e) {
            // Без кэша чат тоже открывается
        }
    }

    /** Оставляет только MAX_FILES самых свежих файлов. */
    private static void prune(File d) {
        File[] files = d.listFiles();
        if (files == null || files.length <= MAX_FILES) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                long x = a.lastModified();
                long y = b.lastModified();
                return x < y ? -1 : (x == y ? 0 : 1);
            }
        });
        for (int i = 0; i < files.length - MAX_FILES; i++) {
            files[i].delete();
        }
    }

    public static void clearAll(Context ctx) {
        File[] files = dir(ctx).listFiles();
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            files[i].delete();
        }
    }

    private static String readFile(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(f.length() + 16, 1 << 20));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
