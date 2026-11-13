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
import android.os.SystemClock;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;

/**
 * Скачивание вложений в фоне. Идёт в службе, поэтому не зависит от экрана: диалог с прогрессом
 * можно спрятать ("В фон"), а ход виден в уведомлении (проценты и скорость, на Android 4.0+
 * ещё и полоса прогресса). Когда файл скачан, появляется уведомление: по касанию файл
 * открывается сразу, без проводника.
 */
public class DownloadService extends Service {

    /** Один скачиваемый файл. */
    public static class Item {
        public String path; // запрос к серверу (file.php?...)
        public String name;
        public long size;   // 0, если неизвестен
    }

    /** Пачка файлов. Тексты уже переведены экраном (язык приложения задаётся там). */
    public static class Job {
        public final ArrayList<Item> items = new ArrayList<Item>();
        public String sTitle = "";      // "Скачивание…"
        public String sProgress = "";   // формат "%1$d из %2$d: %3$s"
        public String sDone1 = "";      // формат "Сохранено: %1$s" (путь к файлу)
        public String sDoneN = "";      // формат "Сохранено файлов: %1$d (%2$s)"
        public String sFailed = "";     // "Не удалось скачать"
        public String sOpen = "";       // "Скачано · нажмите, чтобы открыть"
        public String sMbPerSec = "";   // "МБ/с"
        public String sKbPerSec = "";   // "КБ/с"
    }

    /** События скачивания; вызываются в UI-потоке. */
    public interface Listener {
        void onFileStart(Job job, int index, int total, String name, boolean sizeKnown);

        void onProgress(Job job, long done, long total);

        void onFinished(Job job, int saved, Exception error, boolean cancelled);
    }

    private static final int NOTIF_ID = 5201;
    private static final int NOTIF_DONE_BASE = 5300;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ArrayList<Job> queue = new ArrayList<Job>();
    private static boolean workerRunning;
    private static Listener listener;
    private static volatile boolean cancelRequested;
    private static int doneCounter;

    // ---------- Интерфейс для экранов ----------

    public static void enqueue(Context ctx, Job job) {
        synchronized (queue) {
            queue.add(job);
        }
        ctx.startService(new Intent(ctx, DownloadService.class));
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    /** Прерывает текущую загрузку на ближайшем блоке данных. */
    public static void cancelCurrent() {
        cancelRequested = true;
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
        return 2; // START_NOT_STICKY
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
                    break;
                }
                job = queue.remove(0);
                cancelRequested = false;
            }
            if (first) {
                first = false;
                Notification n = Notifs.make(this, android.R.drawable.stat_sys_download, job.sTitle, "",
                        openApp(), true, -1);
                if (Api.sdkLevel() >= 5) {
                    startForeground(NOTIF_ID, n);
                } else {
                    notifier().notify(NOTIF_ID, n);
                }
            }
            final Job current = job;
            try {
                runJob(api, current);
            } catch (final Throwable t) {
                // Неожиданный сбой не должен убивать рабочий поток: иначе workerRunning навсегда
                // остался бы true, служба не остановилась бы, а новые загрузки не запускались
                UI.post(new Runnable() {
                    public void run() {
                        Listener l = listener;
                        if (l != null) {
                            l.onFinished(current, 0, new java.io.IOException(String.valueOf(t)), false);
                        }
                    }
                });
            }
        }
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
        File dir = Downloader.targetDir(this);
        int saved = 0;
        Exception failure = null;
        File lastFile = null;
        if (dir == null) {
            failure = new java.io.IOException(getString(R.string.dl_no_storage));
        } else {
            Downloader.cleanStale(dir);
        }
        for (int i = 0; dir != null && i < total && failure == null && !cancelRequested; i++) {
            final Item it = job.items.get(i);
            final int index = i + 1;
            File dest = Downloader.uniqueFile(dir, Downloader.sanitize(it.name));
            final String label = total > 1 ? String.format(job.sProgress, index, total, dest.getName())
                    : dest.getName();
            final boolean sizeKnown = it.size > 0;
            UI.post(new Runnable() {
                public void run() {
                    Listener l = listener;
                    if (l != null) {
                        l.onFileStart(job, index, total, label, sizeKnown);
                    }
                }
            });
            notifier().notify(NOTIF_ID, Notifs.make(this, android.R.drawable.stat_sys_download, label, "",
                    openApp(), true, -1));
            try {
                boolean ok = api.download(it.path, dest, new Relay(job, label, it.size));
                if (!ok) {
                    break; // отмена
                }
                saved++;
                lastFile = dest;
                notifyDone(dest, job);
            } catch (Exception e) {
                failure = e;
            }
        }
        final int count = saved;
        final Exception err = failure;
        final boolean cancelled = cancelRequested;
        final File shown = lastFile;
        final File folder = dir;
        UI.post(new Runnable() {
            public void run() {
                Listener l = listener;
                if (l != null) {
                    l.onFinished(job, count, err, cancelled);
                }
                if (count > 0) {
                    String text = count == 1 && shown != null ? String.format(job.sDone1, shown.getPath())
                            : String.format(job.sDoneN, count, folder == null ? "" : folder.getPath());
                    Toast.makeText(DownloadService.this, text, Toast.LENGTH_LONG).show();
                }
                if (err != null && !cancelled) {
                    String msg = err.getMessage() == null ? err.getClass().getName() : err.getMessage();
                    Toast.makeText(DownloadService.this, job.sFailed + ": " + msg, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** Уведомление о готовом файле: по касанию файл открывается сразу. */
    private void notifyDone(File file, Job job) {
        String name = file.getName();
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            ext = name.substring(dot + 1).toLowerCase();
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(Uri.fromFile(file), mime == null ? "*/*" : mime);
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int id = NOTIF_DONE_BASE + (doneCounter++ % 50);
        PendingIntent pi = PendingIntent.getActivity(this, id, view, PendingIntent.FLAG_UPDATE_CURRENT);
        notifier().notify(id, Notifs.make(this, android.R.drawable.stat_sys_download_done, name,
                job.sOpen, pi, false, -1));
    }

    /** Ход скачивания: экрану (по проценту) и уведомлению (раз в секунду), со скоростью. */
    private class Relay implements Api.Progress {
        private final Job job;
        private final String label;
        private final long knownSize;
        private int lastPercent = -1;
        private long lastNotify;
        private long lastTime;
        private long lastBytes;
        private float speed;

        Relay(Job job, String label, long knownSize) {
            this.job = job;
            this.label = label;
            this.knownSize = knownSize;
        }

        public boolean update(final long done, long totalFromServer) {
            final long total = totalFromServer > 0 ? totalFromServer : knownSize;
            long now = SystemClock.uptimeMillis();
            if (lastTime == 0) {
                lastTime = now;
                lastBytes = done;
            }
            long dt = now - lastTime;
            if (dt >= 700) {
                float inst = (done - lastBytes) * 1000f / dt;
                speed = speed == 0 ? inst : speed * 0.6f + inst * 0.4f;
                lastTime = now;
                lastBytes = done;
            }
            int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
            if (percent != lastPercent || percent < 0) {
                lastPercent = percent;
                final long fTotal = total;
                UI.post(new Runnable() {
                    public void run() {
                        Listener l = listener;
                        if (l != null) {
                            l.onProgress(job, done, fTotal);
                        }
                    }
                });
            }
            if (now - lastNotify > 1000) {
                lastNotify = now;
                String speedText = speed > 0 ? formatSpeed(speed) : "";
                String text = percent >= 0 ? percent + "%" + (speedText.length() > 0 ? " · " + speedText : "")
                        : speedText;
                notifier().notify(NOTIF_ID, Notifs.make(DownloadService.this,
                        android.R.drawable.stat_sys_download, label, text, openApp(), true, percent));
            }
            return !cancelRequested;
        }

        private String formatSpeed(float bytesPerSec) {
            if (bytesPerSec >= 1024f * 1024f) {
                return ((int) (bytesPerSec * 10 / (1024f * 1024f)) / 10.0) + " " + job.sMbPerSec;
            }
            return Math.max(1, (int) (bytesPerSec / 1024f)) + " " + job.sKbPerSec;
        }
    }

    private NotificationManager notifier() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** Касание уведомления о ходе загрузки просто открывает приложение. */
    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
    }
}
