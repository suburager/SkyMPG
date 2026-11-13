package org.mpgram.droid;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/** Кэш списка чатов на диске: при запуске список появляется сразу, пока идёт обновление. */
public class ChatCache {

    private static final int VERSION = 1;
    private static final int MAX_ITEMS = 100;

    /** Основной список лежит в chats.json, остальные папки в chats_ID.json. */
    private static File file(Context ctx, String folder) {
        String name = (folder == null || folder.length() == 0) ? "chats.json" : "chats_" + folder + ".json";
        return new File(ctx.getFilesDir(), name);
    }

    /** Возвращает пустой список, если кэша нет, он повреждён или от другой сессии. */
    public static ArrayList<ChatItem> load(Context ctx, int sessionHash, String folder) {
        ArrayList<ChatItem> out = new ArrayList<ChatItem>();
        File f = file(ctx, folder);
        if (!f.exists()) {
            return out;
        }
        try {
            JSONObject root = new JSONObject(readFile(f));
            if (root.optInt("v", 0) != VERSION || root.optInt("a", 0) != sessionHash) {
                return out;
            }
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                ChatItem c = new ChatItem();
                c.id = Api.str(o, "id");
                if (c.id.length() == 0) {
                    continue;
                }
                c.title = Api.str(o, "t");
                c.previewPrefix = Api.str(o, "pp");
                c.preview = Api.str(o, "p");
                c.time = Api.str(o, "tm");
                c.setUnread(o.optInt("u", 0));
                c.pinned = o.optBoolean("pin", false);
                c.muted = o.optBoolean("mute", false);
                c.hasPhoto = o.optBoolean("ph", false);
                c.hasMsg = o.optBoolean("m", false);
                c.buildLine();
                out.add(c);
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<ChatItem>();
        }
    }

    public static String toJson(ArrayList<ChatItem> items, int sessionHash) throws JSONException {
        JSONArray arr = new JSONArray();
        int n = Math.min(items.size(), MAX_ITEMS);
        for (int i = 0; i < n; i++) {
            ChatItem c = items.get(i);
            JSONObject o = new JSONObject();
            o.put("id", c.id);
            o.put("t", c.title);
            o.put("pp", c.previewPrefix);
            o.put("p", c.preview);
            o.put("tm", c.time);
            o.put("u", c.unread);
            o.put("pin", c.pinned);
            o.put("mute", c.muted);
            o.put("ph", c.hasPhoto);
            o.put("m", c.hasMsg);
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("v", VERSION);
        root.put("a", sessionHash);
        root.put("items", arr);
        return root.toString();
    }

    /** Блокирующая запись: сначала во временный файл, потом переименование, чтобы не получить обрезанный кэш. */
    public static void write(Context ctx, String json, String folder) {
        try {
            File f = file(ctx, folder);
            File tmp = new File(f.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(json.getBytes("UTF-8"));
            out.close();
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Exception e) {
            // Без кэша приложение тоже работает
        }
    }

    /** Удаляет кэш всех папок. */
    public static void clear(Context ctx) {
        File[] files = ctx.getFilesDir().listFiles();
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            String n = files[i].getName();
            if (n.startsWith("chats") && (n.endsWith(".json") || n.endsWith(".json.tmp"))) {
                files[i].delete();
            }
        }
    }

    private static String readFile(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
