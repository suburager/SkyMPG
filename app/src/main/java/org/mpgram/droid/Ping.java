package org.mpgram.droid;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Замер задержки до инстанса: время от запроса до получения заголовков ответа. */
public class Ping {

    public static class Result {
        /** Задержка в мс, либо -1, если инстанс не ответил. */
        public int ms = -1;
        /** true, если инстанс по HTTP перенаправляет на HTTPS. */
        public boolean https;
    }

    /** Блокирующий вызов, запускать не из UI-потока. */
    public static Result run(String host) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            long start = System.currentTimeMillis();
            c = (HttpURLConnection) new URL("http://" + host + "/api.php?v=" + Api.VERSION)
                    .openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Connection", "close");
            int code = c.getResponseCode();
            r.ms = (int) (System.currentTimeMillis() - start);
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                r.https = loc != null && loc.startsWith("https://");
            }
        } catch (IOException e) {
            r.ms = -1;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return r;
    }
}
