package org.mpgram.droid;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * ПИН-код на вход в приложение (4-8 цифр). В настройках хранится только соль и SHA-256 от соли
 * и кода. Это защита от случайного просмотра, а не шифрование данных.
 */
public class Pin {

    public static final int MIN = 4;
    public static final int MAX = 8;

    private static final String PREFS = "mpgram";
    private static final String KEY_HASH = "pin_hash";
    private static final String KEY_SALT = "pin_salt";
    private static final String KEY_FAILS = "pin_fails";
    private static final String KEY_BLOCK = "pin_block";

    /** Код введён в этом запуске и приложение с тех пор не уходило надолго в фон. */
    public static boolean unlocked;

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HASH, null) != null;
    }

    public static boolean check(Context ctx, String pin) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String hash = sp.getString(KEY_HASH, null);
        String salt = sp.getString(KEY_SALT, "");
        if (hash == null) {
            return false;
        }
        try {
            // isEqual сравнивает за постоянное время, не выдавая по времени ответа длину совпадения
            return MessageDigest.isEqual(hash.getBytes("UTF-8"), hash(salt, pin).getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            return false;
        }
    }

    public static void set(Context ctx, String pin) {
        byte[] raw = new byte[8];
        new SecureRandom().nextBytes(raw);
        String salt = hex(raw);
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString(KEY_SALT, salt);
        e.putString(KEY_HASH, hash(salt, pin));
        Api.apply(e);
    }

    public static void clear(Context ctx) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.remove(KEY_HASH);
        e.remove(KEY_SALT);
        e.remove(KEY_FAILS);
        e.remove(KEY_BLOCK);
        Api.apply(e);
    }

    /** До какого времени (мс) ввод заблокирован после череды неверных попыток; 0 или прошлое: не заблокирован. */
    public static long blockedUntil(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_BLOCK, 0);
    }

    /**
     * Учитывает неверную попытку. После maxTries подряд включает блокировку на blockMs.
     * Счётчик хранится в настройках, поэтому перезапуск приложения его не сбрасывает.
     * Пишем через commit(), чтобы убийство процесса сразу после попытки не стёрло запись.
     * Возвращает true, если ввод теперь заблокирован.
     */
    public static boolean registerFailure(Context ctx, int maxTries, long blockMs) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int fails = sp.getInt(KEY_FAILS, 0) + 1;
        SharedPreferences.Editor e = sp.edit();
        boolean blocked = fails >= maxTries;
        if (blocked) {
            e.putInt(KEY_FAILS, 0);
            e.putLong(KEY_BLOCK, System.currentTimeMillis() + blockMs);
        } else {
            e.putInt(KEY_FAILS, fails);
        }
        e.commit();
        return blocked;
    }

    public static void resetFailures(Context ctx) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.remove(KEY_FAILS);
        e.remove(KEY_BLOCK);
        Api.apply(e);
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest((salt + ":" + pin).getBytes("UTF-8")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            sb.append(Character.forDigit(v >> 4, 16)).append(Character.forDigit(v & 15, 16));
        }
        return sb.toString();
    }
}
