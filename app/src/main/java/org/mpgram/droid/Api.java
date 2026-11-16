package org.mpgram.droid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.zip.GZIPInputStream;

/**
 * Тонкий клиент MPGram API (shinovon/mpgram-web, API.md).
 * Только API уровня Android 1.5 (API 3): никаких лямбд, String.isEmpty() и т.п.
 */
public class Api {

    public static final int VERSION = 12;

    private static final String PREFS = "mpgram";

    /** Предел размера ответа, читаемого целиком в память: JSON и картинки (превью, аватарки, QR). */
    private static final int MAX_JSON = 6 * 1024 * 1024;
    private static final int MAX_BINARY = 8 * 1024 * 1024;

    /** Номер API системы. Build.VERSION.SDK (строка) есть с API 1, в отличие от SDK_INT. */
    private static final int SDK = sdk();

    /** Пул соединений HttpURLConnection сломан только до Froyo (API 8). */
    private static final boolean KEEP_ALIVE = SDK >= 8;

    static {
        if (!KEEP_ALIVE) {
            System.setProperty("http.keepAlive", "false");
        }
    }

    private static int sdk() {
        try {
            return Integer.parseInt(Build.VERSION.SDK);
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /** Номер версии Android (API level). */
    public static int sdkLevel() {
        return SDK;
    }

    /** Прогресс скачивания файла. */
    public interface Progress {
        /** Возвращает false, чтобы прервать скачивание. total = -1, если размер неизвестен. */
        boolean update(long done, long total);
    }

    public static class ApiException extends Exception {
        public ApiException(String message) {
            super(message);
        }
    }

    private String baseUrl = "";
    private volatile String user = "";
    private String instancePassword = "";
    private String appVersion = "unknown";

    // ---------- Сессия ----------

    public static Api load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Api api = new Api();
        api.appVersion = AppInfo.versionName(ctx);
        api.baseUrl = p.getString("base", "");
        api.user = p.getString("user", "");
        api.instancePassword = p.getString("ipw", "");
        return api;
    }

    public void save(Context ctx) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString("base", baseUrl);
        e.putString("user", user);
        e.putString("ipw", instancePassword);
        apply(e);
    }

    /** Удаляет токен авторизации, адрес инстанса и пароль инстанса остаются. */
    public static void clearSession(Context ctx) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.remove("user");
        apply(e);
    }

    public boolean isLoggedIn() {
        return user.length() > 0 && baseUrl.length() > 0;
    }

    /**
     * Сохраняет настройки асинхронно: apply() появился в Android 2.1 (API 9) и не задерживает
     * поток, тогда как commit() синхронно пишет на диск. На совсем старых Android пишем сразу.
     */
    public static void apply(SharedPreferences.Editor e) {
        if (SDK >= 9) {
            e.apply();
        } else {
            e.commit();
        }
    }

    /** Хэш токена сессии: по нему кэш чатов отличает один аккаунт от другого. */
    public int sessionHash() {
        return user.hashCode();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getInstancePassword() {
        return instancePassword;
    }

    public void setUser(String u) {
        user = u == null ? "" : u;
    }

    public void setInstancePassword(String p) {
        instancePassword = p == null ? "" : p.trim();
    }

    /** Приводит адрес к виду "http://host/path/" либо пустой строке. */
    public void setBaseUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        String rest = s;
        if (rest.startsWith("http://")) {
            rest = rest.substring(7);
        } else if (rest.startsWith("https://")) {
            rest = rest.substring(8);
        } else {
            s = "http://" + s;
        }
        if (rest.length() == 0) {
            baseUrl = "";
            return;
        }
        if (!s.endsWith("/")) {
            s = s + "/";
        }
        baseUrl = s;
    }

    // ---------- Запросы ----------

    /**
     * Вызов метода API. params: пары "имя", "значение", значения null пропускаются.
     * Параметры идут в теле POST, чтобы пароли не попадали в URL.
     */
    public JSONObject call(String method, String... params) throws IOException, ApiException {
        return callIo(method, params);
    }

    private JSONObject callIo(String method, String[] params) throws IOException, ApiException {
        try {
            return callOnce(method, false, params);
        } catch (IOException e) {
            // Соединение из пула могло быть закрыто сервером: повторяем один раз на новом.
            // Повтор безопасен: запросы чтения идемпотентны, а sendMessage несёт одно и то же число r.
            if (KEEP_ALIVE && isStale(e)) {
                return callOnce(method, true, params);
            }
            throw e;
        }
    }

    /** Ошибка похожа на обрыв старого соединения, а не на отсутствие сети или таймаут. */
    private static boolean isStale(IOException e) {
        return !(e instanceof java.net.SocketTimeoutException)
                && !(e instanceof java.net.UnknownHostException)
                && !(e instanceof java.net.ConnectException);
    }

    private JSONObject callOnce(String method, boolean forceClose, String[] params)
            throws IOException, ApiException {
        String url = baseUrl + "api.php?v=" + VERSION + "&method=" + enc(method);

        StringBuffer body = new StringBuffer();
        for (int i = 0; i + 1 < params.length; i += 2) {
            if (params[i + 1] == null) {
                continue;
            }
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(enc(params[i])).append('=').append(enc(params[i + 1]));
        }

        HttpURLConnection c = open(url, forceClose);
        boolean ok = false;
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            // JSON сжимается в разы; если сервер не умеет, он просто ответит без сжатия
            c.setRequestProperty("Accept-Encoding", "gzip");
            byte[] data = body.toString().getBytes("UTF-8");
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.close();

            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null && "gzip".equalsIgnoreCase(c.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            byte[] raw = in == null ? new byte[0] : readAll(in, MAX_JSON);
            String text = new String(raw, "UTF-8");

            JSONObject o;
            try {
                o = new JSONObject(text);
            } catch (JSONException e) {
                throw new ApiException("HTTP " + code + ": response is not JSON");
            }
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                throw new ApiException(str(err, "message"));
            }
            ok = !forceClose;
            return o;
        } finally {
            release(c, ok);
        }
    }

    /** GET бинарного ресурса (капча, QR, аватарки, файлы). path относительно адреса инстанса. */
    public byte[] fetch(String path) throws IOException, ApiException {
        try {
            return fetchOnce(path, false);
        } catch (IOException e) {
            if (KEEP_ALIVE && isStale(e)) {
                return fetchOnce(path, true);
            }
            throw e;
        }
    }

    /**
     * Скачивает ресурс в файл потоком, не держа его целиком в памяти. Пишет в dest + ".part"
     * и переименовывает по завершении; при ошибке или отмене временный файл удаляется.
     * Возвращает false, если скачивание прервали через Progress.
     */
    public boolean download(String path, File dest, Progress progress) throws IOException, ApiException {
        HttpURLConnection c = open(baseUrl + path, true);
        File part = new File(dest.getPath() + ".part");
        boolean done = false;
        try {
            int code = c.getResponseCode();
            if (code >= 400) {
                throw new ApiException("HTTP " + code);
            }
            long total = -1;
            try {
                total = Long.parseLong(c.getHeaderField("Content-Length"));
            } catch (Exception e) {
                // Размер неизвестен
            }
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(part);
            try {
                byte[] buf = new byte[16384];
                long read = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    read += n;
                    if (progress != null && !progress.update(read, total)) {
                        return false;
                    }
                }
            } finally {
                out.close();
                in.close();
            }
            if (dest.exists()) {
                dest.delete();
            }
            if (!part.renameTo(dest)) {
                throw new IOException("rename failed");
            }
            done = true;
            return true;
        } finally {
            if (!done) {
                part.delete();
            }
            c.disconnect();
        }
    }

    /**
     * Отправка файла методом API через multipart/form-data. Файл читается из in и пишется в
     * соединение потоком (длина известна: fixed-length, иначе chunked), поэтому память не
     * зависит от размера файла. fields: пары "имя", "значение", null пропускаются.
     * length < 0 если размер неизвестен. Прерывание через Progress даёт IOException("cancelled").
     */
    public JSONObject upload(String method, String[] fields, String fileField, String fileName,
                             String mime, InputStream in, long length, Progress progress)
            throws IOException, ApiException {
        String boundary = "----skympg" + System.currentTimeMillis();
        StringBuffer head = new StringBuffer();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            if (fields[i + 1] == null) {
                continue;
            }
            head.append("--").append(boundary).append("\r\n");
            head.append("Content-Disposition: form-data; name=\"").append(fields[i]).append("\"\r\n\r\n");
            head.append(fields[i + 1]).append("\r\n");
        }
        String safeName = fileName.replace('"', '_').replace('\r', '_').replace('\n', '_');
        head.append("--").append(boundary).append("\r\n");
        head.append("Content-Disposition: form-data; name=\"").append(fileField)
                .append("\"; filename=\"").append(safeName).append("\"\r\n");
        head.append("Content-Type: ").append(mime).append("\r\n\r\n");
        byte[] headBytes = head.toString().getBytes("UTF-8");
        byte[] tailBytes = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");

        HttpURLConnection c = open(baseUrl + "api.php?v=" + VERSION + "&method=" + enc(method), true);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setReadTimeout(300000); // после загрузки сервер ещё передаёт файл в Telegram
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            long total = length >= 0 ? headBytes.length + length + tailBytes.length : -1;
            if (total >= 0 && total <= Integer.MAX_VALUE) {
                c.setFixedLengthStreamingMode((int) total);
            } else {
                c.setChunkedStreamingMode(0);
            }
            OutputStream os = c.getOutputStream();
            os.write(headBytes);
            byte[] buf = new byte[16384];
            long sent = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
                sent += n;
                if (progress != null && !progress.update(sent, length)) {
                    throw new IOException("cancelled");
                }
            }
            os.write(tailBytes);
            os.close();

            int code = c.getResponseCode();
            InputStream rin = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] raw = rin == null ? new byte[0] : readAll(rin, MAX_JSON);
            JSONObject o;
            try {
                o = new JSONObject(new String(raw, "UTF-8"));
            } catch (JSONException e) {
                throw new ApiException("HTTP " + code + ": response is not JSON");
            }
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                throw new ApiException(str(err, "message"));
            }
            return o;
        } finally {
            c.disconnect();
        }
    }

    private byte[] fetchOnce(String path, boolean forceClose) throws IOException, ApiException {
        HttpURLConnection c = open(baseUrl + path, forceClose);
        boolean ok = false;
        try {
            int code = c.getResponseCode();
            if (code >= 400) {
                throw new ApiException("HTTP " + code);
            }
            byte[] data = readAll(c.getInputStream(), MAX_BINARY, c.getContentLength());
            ok = !forceClose;
            return data;
        } finally {
            release(c, ok);
        }
    }

    /**
     * После полностью прочитанного ответа соединение остаётся в пуле (disconnect() закрыл бы сокет).
     * На ошибке или без keep-alive закрываем.
     */
    private static void release(HttpURLConnection c, boolean ok) {
        if (!(ok && KEEP_ALIVE)) {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String url, boolean forceClose) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(70000);
        if (!KEEP_ALIVE || forceClose) {
            c.setRequestProperty("Connection", "close");
        }
        c.setRequestProperty("X-mpgram-unicode", "1");
        c.setRequestProperty("X-mpgram-app-version", ascii(appVersion));
        c.setRequestProperty("X-mpgram-device", ascii(Build.MODEL));
        c.setRequestProperty("X-mpgram-system", "Android " + ascii(Build.VERSION.RELEASE));
        if (user.length() > 0) {
            c.setRequestProperty("X-mpgram-user", user);
        }
        if (instancePassword.length() > 0) {
            c.setRequestProperty("X-mpgram-instance-password", instancePassword);
        }
        return c;
    }

    // ---------- Утилиты ----------

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        return readAll(in, limit, -1);
    }

    /**
     * hint: длина из Content-Length (или -1). Если она известна, буфер сразу нужного размера:
     * без удвоений при росте пик памяти ниже, а на куче 16 МБ это решает, влезет ли картинка.
     */
    private static byte[] readAll(InputStream in, int limit, int hint) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(hint > 0 && hint <= limit ? hint + 1 : 16384);
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                // Огромный ответ целиком в памяти на 16 МБ куче закончился бы OutOfMemoryError
                if (out.size() > limit) {
                    throw new IOException("response too large");
                }
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /** Безопасно читает строку из JSON: null и отсутствие значения дают "". */
    public static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) {
            return "";
        }
        return o.optString(key, "");
    }

    private static String ascii(String s) {
        if (s == null) {
            return "";
        }
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            b.append(ch >= 0x20 && ch <= 0x7E ? ch : '?');
        }
        return b.toString();
    }
}
