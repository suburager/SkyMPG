package org.mpgram.droid;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * Запоминает, какие чаты являются форумами (с топиками), чтобы не проверять это при каждом открытии.
 * Форум помним без срока, "не форум" только на ограниченное время (чат может стать форумом).
 */
public class ForumCache {

    public static final int UNKNOWN = 0;
    public static final int FORUM = 1;
    public static final int NOT_FORUM = 2;

    public static final long TTL_LONG = 7L * 24 * 60 * 60 * 1000;
    public static final long TTL_SHORT = 30L * 60 * 1000;

    private static final String PREFS = "mpgram";
    private static final String PREFIX = "forum_";

    /** Форумы бывают только среди супергрупп (каналов): ID от -1000000000000 до -2000000000000. */
    public static boolean mayBeForum(String peerId) {
        try {
            long id = Long.parseLong(peerId);
            return id <= -1000000000000L && id > -2000000000000L;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static int get(Context ctx, String peerId) {
        String v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREFIX + peerId, null);
        if (v == null) {
            return UNKNOWN;
        }
        if ("f".equals(v)) {
            return FORUM;
        }
        if (v.startsWith("n")) {
            try {
                long until = Long.parseLong(v.substring(1));
                return System.currentTimeMillis() < until ? NOT_FORUM : UNKNOWN;
            } catch (NumberFormatException e) {
                return UNKNOWN;
            }
        }
        return UNKNOWN;
    }

    public static void setForum(Context ctx, String peerId) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString(PREFIX + peerId, "f");
        Api.apply(e);
    }

    public static void setNotForum(Context ctx, String peerId, long ttlMillis) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString(PREFIX + peerId, "n" + (System.currentTimeMillis() + ttlMillis));
        Api.apply(e);
    }

    /** При выходе из аккаунта. */
    public static void clear(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ArrayList<String> keys = new ArrayList<String>();
        Iterator<String> it = p.getAll().keySet().iterator();
        while (it.hasNext()) {
            String k = it.next();
            if (k.startsWith(PREFIX)) {
                keys.add(k);
            }
        }
        SharedPreferences.Editor e = p.edit();
        for (int i = 0; i < keys.size(); i++) {
            e.remove(keys.get(i));
        }
        Api.apply(e);
    }
}
