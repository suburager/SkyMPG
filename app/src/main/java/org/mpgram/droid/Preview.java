package org.mpgram.droid;

import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/** Подпись последнего сообщения для строк списков: "Имя: текст", тип вложения и время. */
public class Preview {

    private static final SimpleDateFormat FMT_TIME = new SimpleDateFormat("HH:mm");
    private static final SimpleDateFormat FMT_DAY = new SimpleDateFormat("dd.MM");
    private static final SimpleDateFormat FMT_FULL = new SimpleDateFormat("dd.MM.yy");

    private static String senderName(JSONObject users, String fromId) {
        JSONObject u = users == null ? null : users.optJSONObject(fromId);
        if (u == null) {
            return "";
        }
        String name = Api.str(u, "fn");
        if (name.length() == 0) {
            name = Api.str(u, "ln");
        }
        if (name.length() == 0 && Api.str(u, "name").length() > 0) {
            name = "@" + Api.str(u, "name");
        }
        return name;
    }

    /** Цветная часть: "Вы: " для своих сообщений, "Имя: " для чужих в группах. */
    public static String prefix(Context c, boolean group, JSONObject msg, JSONObject users) {
        if (msg == null) {
            return "";
        }
        if (msg.optBoolean("out", false)) {
            return c.getString(R.string.preview_you) + " ";
        }
        if (group) {
            String from = Api.str(msg, "from_id");
            if (from.length() > 0 && !from.startsWith("-")) {
                String name = senderName(users, from);
                if (name.length() > 0) {
                    return name + ": ";
                }
            }
        }
        return "";
    }

    /** Серая часть: текст сообщения или тип вложения. */
    public static String text(Context c, JSONObject msg) {
        if (msg == null) {
            return "";
        }
        String text = Api.str(msg, "text").replace('\n', ' ').trim();
        if (text.length() > 0) {
            return text;
        }
        JSONObject media = msg.optJSONObject("media");
        if (media != null) {
            String type = Api.str(media, "type");
            int res;
            if ("photo".equals(type)) {
                res = R.string.preview_photo;
            } else if ("document".equals(type)) {
                if (media.optBoolean("voice", false)) {
                    res = R.string.preview_voice;
                } else if (media.optBoolean("audio", false)) {
                    res = R.string.preview_audio;
                } else {
                    res = R.string.preview_file;
                }
            } else if ("webpage".equals(type)) {
                res = R.string.preview_link;
            } else if ("geo".equals(type)) {
                res = R.string.preview_location;
            } else if ("poll".equals(type)) {
                res = R.string.preview_poll;
            } else {
                res = R.string.preview_media;
            }
            return c.getString(res);
        }
        if (msg.has("act")) {
            return c.getString(R.string.preview_service);
        }
        return c.getString(R.string.preview_media);
    }

    /** Время сегодняшнего сообщения, дата для остальных. date в секундах Unix. */
    public static synchronized String time(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        Date date = new Date(seconds * 1000L);
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTime(date);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            if (now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
                return FMT_TIME.format(date);
            }
            return FMT_DAY.format(date);
        }
        return FMT_FULL.format(date);
    }
}
