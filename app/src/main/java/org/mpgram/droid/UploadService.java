package org.mpgram.droid;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.content.res.AssetFileDescriptor;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Random;

/**
 * Отправка вложений в фоне. Раньше загрузка шла потоком внутри экрана чата и держала его в
 * памяти; теперь ею занимается сервис, и она не зависит ни от экрана, ни от того, что приложение
 * свернули. Пока идёт отправка, виден значок в строке состояния (на Android 2.0+ сервис при этом
 * считается "на переднем плане", так что система реже его убивает).
 *
 * Экран чата подписывается на события через Listener и показывает диалог с прогрессом. Если
 * экрана нет, по окончании приходит уведомление. Всё это работает на любой версии Android:
 * Service, уведомления и startForeground (с API 5) выбираются по номеру версии.
 */
public class UploadService extends Service {

    /** Один отправляемый файл. */
    public static class Item {
        public Uri uri;
        public String name;
        public long size;       // -1, если неизвестен
        public String mime;
        public boolean asFile;  // отправить файлом как есть, без сжатия
    }

    /** Пачка файлов в один чат. Тексты уже переведены экраном (язык приложения задаётся там). */
    public static class Job {
        public String peer;
        public int topicId;
        public int replyId;
        public String caption = "";
        public final ArrayList<Item> items = new ArrayList<Item>();
        public String sTitle = "";     // "Отправка файла…"
        public String sProgress = "";  // формат "%1$d из %2$d: %3$s"
        public String sDone = "";      // формат "Отправлено файлов: %1$d"
        public String sFailed = "";    // "Не удалось отправить"
    }

    /** События отправки; вызываются в UI-потоке. */
    public interface Listener {
        /** Этот экран показывает именно тот чат, куда идёт отправка. */
        boolean accepts(Job job);

        void onFileStart(Job job, int index, int total, String name, boolean sizeKnown);

        void onProgress(Job job, long done, long total);

        /**
         * Отправка закончена. remaining: что не ушло (ошибка или отмена); captionUnsent: подпись
         * ни к одному файлу не успела уйти; error: причина ошибки или null.
         */
        void onFinished(Job job, int sent, ArrayList<Item> remaining, boolean captionUnsent,
                        Exception error, boolean cancelled);
    }

    private static final int NOTIF_ID = 5101;
    private static final int NOTIF_DONE_ID = 5102;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ArrayList<Job> queue = new ArrayList<Job>();
    private static Job current;
    private static String currentLabel = "";
    private static boolean workerRunning;
    private static Listener listener;
    private static volatile boolean cancelRequested;

    // ---------- Интерфейс для экранов ----------

    /** Ставит пачку в очередь и запускает сервис. */
    public static void enqueue(Context ctx, Job job) {
        synchronized (queue) {
            queue.add(job);
        }
        ctx.startService(new Intent(ctx, UploadService.class));
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    /** Отписывает экран; чужого слушателя не трогает. */
    public static void clearListener(Listener l) {
        if (listener == l) {
            listener = null;
        }
    }

    /** Прерывает текущую отправку (на ближайшем блоке данных). Оставшиеся файлы возвращаются слушателю. */
    public static void cancelCurrent() {
        cancelRequested = true;
    }

    /** Идёт или ждёт очереди отправка в этот чат. */
    public static boolean isActive(String peer) {
        synchronized (queue) {
            if (current != null && peer.equals(current.peer)) {
                return true;
            }
            for (int i = 0; i < queue.size(); i++) {
                if (peer.equals(queue.get(i).peer)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Подпись текущего файла ("2 из 3: имя") для экрана, который открылся посреди отправки. */
    public static String currentLabel() {
        return currentLabel;
    }

    // ---------- Сервис ----------

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Android 2.0+ вызывает его вместо onStart(). */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startWorker();
        return 2; // START_NOT_STICKY: убитый сервис не перезапускается без очереди
    }

    /** Android 1.5-1.6 вызывают только его. */
    @Override
    public void onStart(Intent intent, int startId) {
        startWorker();
    }

    private void startWorker() {
        synchronized (queue) {
            if (workerRunning) {
                return;
            }
            workerRunning = true;
        }
        new Thread(new Runnable() {
            public void run() {
                work();
            }
        }).start();
    }

    private void work() {
        Api api = Api.load(this);
        boolean first = true;
        while (true) {
            Job job;
            synchronized (queue) {
                if (queue.isEmpty()) {
                    workerRunning = false;
                    current = null;
                    break;
                }
                job = queue.remove(0);
                current = job;
                cancelRequested = false;
            }
            if (first) {
                first = false;
                Notification n = notification(android.R.drawable.stat_sys_upload, job.sTitle, "", true, -1);
                if (Api.sdkLevel() >= 5) {
                    startForeground(NOTIF_ID, n);
                } else {
                    notifier().notify(NOTIF_ID, n);
                }
            }
            final Job crashed = job;
            try {
                runJob(api, crashed);
            } catch (final Throwable t) {
                // Сбой не должен убивать рабочий поток: иначе очередь навсегда зависла бы
                currentLabel = "";
                UI.post(new Runnable() {
                    public void run() {
                        finish(crashed, 0, new ArrayList<Item>(crashed.items), crashed.caption.length() > 0,
                                new java.io.IOException(String.valueOf(t)), false);
                    }
                });
            }
        }
        // Остановка сервиса только в UI-потоке и только если за это время не пришла новая работа
        UI.post(new Runnable() {
            public void run() {
                synchronized (queue) {
                    if (workerRunning || !queue.isEmpty()) {
                        return;
                    }
                }
                if (Api.sdkLevel() >= 5) {
                    stopForeground(true);
                } else {
                    notifier().cancel(NOTIF_ID);
                }
                stopSelf();
            }
        });
    }

    private void runJob(Api api, final Job job) {
        final int total = job.items.size();
        final ArrayList<Item> remaining = new ArrayList<Item>(job.items);
        int sent = 0;
        boolean captionLeft = job.caption.length() > 0;
        Exception failure = null;

        for (int i = 0; i < total && failure == null && !cancelRequested; i++) {
            final Item it = job.items.get(i);
            final long sz = it.size > 0 ? it.size : resolveSize(it.uri);
            final String label = String.format(job.sProgress, i + 1, total, it.name);
            currentLabel = label;
            final int index = i + 1;
            UI.post(new Runnable() {
                public void run() {
                    Listener l = listener;
                    if (l != null && l.accepts(job)) {
                        l.onFileStart(job, index, total, it.name, sz > 0);
                    }
                }
            });
            notifier().notify(NOTIF_ID, notification(android.R.drawable.stat_sys_upload, job.sTitle, label, true, -1));

            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(it.uri);
                if (in == null) {
                    throw new java.io.IOException("cannot open file");
                }
                api.upload("sendMedia",
                        new String[] {
                                "peer", job.peer,
                                "text", captionLeft ? job.caption : null,
                                "reply", job.replyId > 0 ? String.valueOf(job.replyId)
                                        : (job.topicId > 0 ? String.valueOf(job.topicId) : null),
                                "uncompressed", it.asFile ? "1" : null,
                                "r", String.valueOf(new Random().nextInt(Integer.MAX_VALUE))},
                        "file", it.name, it.mime, in, sz, new ProgressRelay(job, label));
                sent++;
                captionLeft = false;
                remaining.remove(it);
            } catch (Exception e) {
                failure = e;
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception e) {
                        // Закрываем молча
                    }
                }
            }
        }

        if (sent > 1 && job.topicId <= 0) {
            rememberBatch(api, job, sent);
        }
        final boolean cancelled = cancelRequested;
        final int sentCount = sent;
        final boolean captionUnsent = captionLeft;
        final Exception err = failure;
        currentLabel = "";
        UI.post(new Runnable() {
            public void run() {
                finish(job, sentCount, remaining, captionUnsent, err, cancelled);
            }
        });
    }

    /**
     * Через API нельзя отправить альбом одним сообщением, поэтому файлы уходят отдельными. Чтобы
     * показывать их одним баблом, находим только что отправленные сообщения в истории и
     * запоминаем как группу (LocalGroups). Делается здесь, а не на экране: так склейка не
     * теряется, если из чата вышли до конца отправки.
     */
    private void rememberBatch(Api api, Job job, int sent) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JSONObject o = api.call("getHistory", "peer", job.peer,
                        "limit", String.valueOf(sent + 12), "media", "1", "fields", "messages");
                JSONArray arr = o.optJSONArray("messages");
                ArrayList<Integer> ids = new ArrayList<Integer>();
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m != null && m.optBoolean("out", false) && m.optJSONObject("media") != null) {
                        ids.add(Integer.valueOf(m.optInt("id")));
                    }
                }
                if (ids.size() >= sent) {
                    java.util.Collections.sort(ids);
                    int[] last = new int[sent];
                    for (int i = 0; i < sent; i++) {
                        last[i] = ids.get(ids.size() - sent + i).intValue();
                    }
                    LocalGroups.remember(this, job.peer, last);
                    return;
                }
                Thread.sleep(600); // сервер мог ещё не отдать все сообщения
            } catch (Exception e) {
                return;
            }
        }
    }

    /** Итог пачки: экран, если он на месте, иначе сообщение пользователю от сервиса. */
    private void finish(Job job, int sent, ArrayList<Item> remaining, boolean captionUnsent,
                        Exception err, boolean cancelled) {
        Listener l = listener;
        if (l != null && l.accepts(job)) {
            l.onFinished(job, sent, remaining, captionUnsent, err, cancelled);
            return;
        }
        // Экрана с этим чатом нет: сообщаем сами
        if (err != null && !cancelled) {
            String text = job.sFailed + ": " + Errors.text(this, err);
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            notifier().notify(NOTIF_DONE_ID,
                    notification(android.R.drawable.stat_notify_error, job.sFailed, Errors.text(this, err), false, -1));
        } else if (sent > 0) {
            String text = String.format(job.sDone, sent);
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            notifier().notify(NOTIF_DONE_ID,
                    notification(android.R.drawable.stat_sys_upload_done, text, "", false, -1));
        }
    }

    /** Размер файла по Uri, когда провайдер не отдал его при выборе. -1: неизвестен. */
    private long resolveSize(Uri uri) {
        try {
            AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd != null) {
                long n = afd.getLength();
                afd.close();
                return n > 0 ? n : -1;
            }
        } catch (Exception e) {
            // Размер останется неизвестным: отправим потоком без длины
        }
        return -1;
    }

    /** Передаёт ход загрузки экрану и в уведомление, не чаще чем нужно. */
    private class ProgressRelay implements Api.Progress {
        private final Job job;
        private final String label;
        private int lastPercent = -1;
        private long lastNotify;

        ProgressRelay(Job job, String label) {
            this.job = job;
            this.label = label;
        }

        public boolean update(final long done, final long total) {
            int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
            long now = System.currentTimeMillis();
            if (percent != lastPercent || total <= 0) {
                lastPercent = percent;
                UI.post(new Runnable() {
                    public void run() {
                        Listener l = listener;
                        if (l != null && l.accepts(job)) {
                            l.onProgress(job, done, total);
                        }
                    }
                });
                if (now - lastNotify > 1000) {
                    lastNotify = now;
                    notifier().notify(NOTIF_ID, notification(android.R.drawable.stat_sys_upload,
                            job.sTitle, percent >= 0 ? label + " " + percent + "%" : label, true, percent));
                }
            }
            return !cancelRequested;
        }
    }

    // ---------- Уведомления ----------

    private NotificationManager notifier() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** Уведомление в строке состояния; по касанию открывает приложение. */
    private Notification notification(int icon, String title, String text, boolean ongoing, int percent) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        return Notifs.make(this, icon, title, text, pi, ongoing, percent);
    }
}
