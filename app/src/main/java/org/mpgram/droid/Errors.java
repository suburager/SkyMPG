package org.mpgram.droid;

import android.content.Context;

import java.io.IOException;

/** Понятный пользователю текст ошибки. */
public class Errors {

    public static String text(Context c, Exception e) {
        if (e instanceof Api.ApiException) {
            return translate(c, e.getMessage());
        }
        if (e instanceof IOException) {
            return c.getString(R.string.err_network) + ": " + e;
        }
        return String.valueOf(e);
    }

    /** Ошибка означает, что сессия на сервере недействительна и нужно войти заново. */
    public static boolean isSessionError(Exception e) {
        if (!(e instanceof Api.ApiException)) {
            return false;
        }
        String m = e.getMessage();
        return "Invalid authorization".equals(m)
                || "Could not get user info".equals(m)
                || "Failed to load session".equals(m);
    }

    /** Сервер (PHP) упал на данных ответа: "Undefined array key ...". Это не сеть и не сессия. */
    public static boolean isServerArrayKey(Exception e) {
        if (!(e instanceof Api.ApiException)) {
            return false;
        }
        String m = e.getMessage();
        return m != null && m.indexOf("Undefined array key") >= 0;
    }

    private static String translate(Context c, String m) {
        if (m == null || m.length() == 0) {
            return c.getString(R.string.err_unknown);
        }
        if (m.equals("Instance password is required")) {
            return c.getString(R.string.err_ipw_required);
        }
        if (m.equals("Wrong instance password")) {
            return c.getString(R.string.err_ipw_wrong);
        }
        if (m.equals("API is disabled")) {
            return c.getString(R.string.err_api_disabled);
        }
        if (m.equals("Login API is disabled")) {
            return c.getString(R.string.err_login_disabled);
        }
        if (m.equals("Unsupported API version")) {
            return c.getString(R.string.err_version);
        }
        return m;
    }
}
