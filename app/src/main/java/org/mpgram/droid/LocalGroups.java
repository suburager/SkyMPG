package org.mpgram.droid;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;

/**
 * Через API нельзя отправить альбом одним сообщением, поэтому несколько вложений уходят
 * отдельными сообщениями. Чтобы показывать их одним баблом, приложение запоминает, какие
 * сообщения чата были отправлены вместе. Это только вид в этом приложении: на сервере и в других
 * клиентах сообщения остаются отдельными.
 */
public class LocalGroups {

    private static final String PREFS = "mpgram_groups";
    private static final int KEEP = 40; // сколько последних групп помнить в одном чате

    /** peer -> (id сообщения -> ключ группы), загружается из настроек по требованию. */
    private static final HashMap<String, HashMap<Integer, Long>> cache = new HashMap<String, HashMap<Integer, Long>>();

    /** Ключ группы сообщения или 0, если оно не из пачки. Ключи отрицательные, чтобы не пересечься с настоящими. */
    public static synchronized long keyOf(Context ctx, String peer, int msgId) {
        Long k = load(ctx, peer).get(Integer.valueOf(msgId));
        return k == null ? 0 : k.longValue();
    }

    /** Запоминает, что эти сообщения отправлены вместе. Возвращает ключ группы. */
    public static synchronized long remember(Context ctx, String peer, int[] ids) {
        HashMap<Integer, Long> map = load(ctx, peer);
        long key = -(long) ids[0];
        for (int i = 0; i < ids.length; i++) {
            map.put(Integer.valueOf(ids[i]), Long.valueOf(key));
        }
        // Оставляем только последние KEEP групп: ключ это минус первый id, так что чем меньше ключ, тем новее
        if (distinctKeys(map) > KEEP) {
            long oldest = 0;
            for (Long v : map.values()) {
                if (v.longValue() > oldest) {
                    oldest = v.longValue();
                }
            }
            java.util.Iterator<Long> it = map.values().iterator();
            while (it.hasNext()) {
                if (it.next().longValue() == oldest) {
                    it.remove();
                }
            }
        }
        save(ctx, peer, map);
        return key;
    }

    private static int distinctKeys(HashMap<Integer, Long> map) {
        java.util.HashSet<Long> s = new java.util.HashSet<Long>(map.values());
        return s.size();
    }

    private static HashMap<Integer, Long> load(Context ctx, String peer) {
        HashMap<Integer, Long> map = cache.get(peer);
        if (map != null) {
            return map;
        }
        map = new HashMap<Integer, Long>();
        String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(peer, "");
        // Формат: "ключ:id,id,id;ключ:id,id"
        String[] groups = raw.split(";");
        for (int g = 0; g < groups.length; g++) {
            int colon = groups[g].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            try {
                Long key = Long.valueOf(groups[g].substring(0, colon));
                String[] ids = groups[g].substring(colon + 1).split(",");
                for (int i = 0; i < ids.length; i++) {
                    map.put(Integer.valueOf(ids[i]), key);
                }
            } catch (NumberFormatException e) {
                // Повреждённую запись пропускаем
            }
        }
        cache.put(peer, map);
        return map;
    }

    private static void save(Context ctx, String peer, HashMap<Integer, Long> map) {
        HashMap<Long, StringBuffer> byKey = new HashMap<Long, StringBuffer>();
        for (java.util.Map.Entry<Integer, Long> e : map.entrySet()) {
            StringBuffer sb = byKey.get(e.getValue());
            if (sb == null) {
                sb = new StringBuffer();
                byKey.put(e.getValue(), sb);
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey().intValue());
        }
        StringBuffer out = new StringBuffer();
        for (java.util.Map.Entry<Long, StringBuffer> e : byKey.entrySet()) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(e.getKey().longValue()).append(':').append(e.getValue());
        }
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putString(peer, out.toString());
        Api.apply(ed);
    }

    /** Забыть всё (при выходе из аккаунта). */
    public static synchronized void clear(Context ctx) {
        cache.clear();
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.clear();
        Api.apply(e);
    }
}
