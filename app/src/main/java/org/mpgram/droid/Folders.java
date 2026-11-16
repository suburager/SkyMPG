package org.mpgram.droid;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Папки чатов (getFolders). Список кэшируется, чтобы вкладки появлялись сразу при запуске. */
public class Folders {

    public static class Folder {
        public String id = "";
        public String title = "";
    }

    public static class Data {
        public ArrayList<Folder> list = new ArrayList<Folder>();
        /** У аккаунта есть архив (папка с ID 1). */
        public boolean archive;
    }

    private static final String PREFS = "mpgram";
    private static final String KEY = "folders";

    /** Разбор ответа getFolders: {"res": [{"id":2,"t":"Работа"}] или null, "archive": true}. */
    public static Data parse(JSONObject o) {
        Data d = new Data();
        d.archive = o.optBoolean("archive", false);
        JSONArray arr = o.optJSONArray("res");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject f = arr.optJSONObject(i);
                if (f == null) {
                    continue;
                }
                String id = Api.str(f, "id");
                // 0 это основной список, 1 архив (показывается отдельной вкладкой)
                if (id.length() == 0 || "0".equals(id) || "1".equals(id)) {
                    continue;
                }
                Folder folder = new Folder();
                folder.id = id;
                folder.title = Api.str(f, "t");
                d.list.add(folder);
            }
        }
        return d;
    }

    public static void save(Context ctx, int sessionHash, Data d) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < d.list.size(); i++) {
                JSONObject o = new JSONObject();
                o.put("id", d.list.get(i).id);
                o.put("t", d.list.get(i).title);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("a", sessionHash);
            root.put("arch", d.archive);
            root.put("res", arr);
            SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            e.putString(KEY, root.toString());
            Api.apply(e);
        } catch (Exception ex) {
            // Кэш необязателен
        }
    }

    /** Пустые данные, если кэша нет или он от другой сессии. */
    public static Data load(Context ctx, int sessionHash) {
        Data d = new Data();
        String s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
        if (s == null) {
            return d;
        }
        try {
            JSONObject root = new JSONObject(s);
            if (root.optInt("a", 0) != sessionHash) {
                return d;
            }
            d.archive = root.optBoolean("arch", false);
            JSONArray arr = root.optJSONArray("res");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Folder f = new Folder();
                    f.id = Api.str(o, "id");
                    f.title = Api.str(o, "t");
                    if (f.id.length() > 0) {
                        d.list.add(f);
                    }
                }
            }
        } catch (Exception e) {
            return new Data();
        }
        return d;
    }

    public static void clear(Context ctx) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.remove(KEY);
        Api.apply(e);
    }
}
