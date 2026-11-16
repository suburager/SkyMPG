package org.mpgram.droid;

import android.content.Context;

/** Число с правильным склонением слова ("1 участник", "3 участника", "5 участников"). */
public class Plural {

    /** Число с пробелами между тысячами: 12345 -> "12 345" (неразрывные). */
    public static String grouped(int n) {
        String s = String.valueOf(Math.abs(n));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) {
                sb.append('\u00A0');
            }
            sb.append(s.charAt(i));
        }
        return (n < 0 ? "-" : "") + sb;
    }

    /**
     * "n слово" по правилу текущего языка (ресурс plural_rule: 0 английское, 1 русское).
     * one, few, many: ресурсы слов для 1, 2-4 и остальных чисел.
     */
    public static String format(Context ctx, int n, int one, int few, int many) {
        int rule = ctx.getResources().getInteger(R.integer.plural_rule);
        int res;
        if (rule == 1) {
            int m10 = n % 10;
            int m100 = n % 100;
            if (m10 == 1 && m100 != 11) {
                res = one;
            } else if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) {
                res = few;
            } else {
                res = many;
            }
        } else {
            res = n == 1 ? one : many;
        }
        return grouped(n) + " " + ctx.getString(res);
    }
}
