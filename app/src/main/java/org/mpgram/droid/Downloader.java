package org.mpgram.droid;

import android.app.Activity;
import android.content.Context;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;

/**
 * Скачивание вложений из сообщений (file.php без преобразования) в папку Download/SkyMPG.
 * Саму работу делает DownloadService; здесь экран показывает диалог с прогрессом (его можно
 * спрятать кнопкой "В фон", и тогда ход виден в уведомлении). Файл пишется потоком, поэтому
 * большие файлы не упираются в память. Если общая память недоступна (новые Android с
 * ограничениями), используется папка приложения на карте.
 */
public class Downloader {

    private static final String DIR = "SkyMPG";

    /** Скачивает одно вложение. */
    public static void start(Activity act, Api api, String peer, int msgId, String name, long size) {
        DownloadService.Job job = newJob(act);
        job.items.add(item(peer, msgId, name, size));
        launch(act, job);
    }

    /**
     * Скачивает все вложения группы подряд ("2 из 5: имя"). Отмена останавливает на текущем
     * файле; уже скачанные остаются.
     */
    public static void startAll(Activity act, Api api, String peer, ArrayList<MsgItem.Part> parts) {
        DownloadService.Job job = newJob(act);
        for (int i = 0; i < parts.size(); i++) {
            MsgItem.Part p = parts.get(i);
            job.items.add(item(peer, p.id, p.fileName, p.fileSize));
        }
        launch(act, job);
    }

    private static DownloadService.Item item(String peer, int msgId, String name, long size) {
        DownloadService.Item it = new DownloadService.Item();
        it.path = "file.php?c=" + Api.enc(peer) + "&m=" + msgId;
        it.name = name;
        it.size = size;
        return it;
    }

    /** Тексты готовим на экране: язык приложения применяется к его ресурсам, а не к службе. */
    private static DownloadService.Job newJob(Activity act) {
        DownloadService.Job job = new DownloadService.Job();
        job.sTitle = act.getString(R.string.dl_progress);
        job.sProgress = act.getString(R.string.upload_progress);
        job.sDone1 = act.getString(R.string.dl_done);
        job.sDoneN = act.getString(R.string.dl_all_done);
        job.sFailed = act.getString(R.string.dl_failed);
        job.sOpen = act.getString(R.string.dl_notif_done);
        job.sMbPerSec = act.getString(R.string.unit_mb) + act.getString(R.string.unit_per_sec);
        job.sKbPerSec = act.getString(R.string.unit_kb) + act.getString(R.string.unit_per_sec);
        return job;
    }

    private static void launch(Activity act, DownloadService.Job job) {
        if (targetDir(act) == null) {
            Toast.makeText(act, R.string.dl_no_storage, Toast.LENGTH_LONG).show();
            return;
        }
        final TransferDialog td = new TransferDialog(act, R.string.dl_progress);
        td.setBackHides(true);      // "Назад" и "В фон" прячут диалог
        td.setKeepOnRelease(true);  // закрытие экрана скачивание не отменяет
        td.setOnCancel(new Runnable() {
            public void run() {
                DownloadService.cancelCurrent();
            }
        });
        td.show();
        // Слушатель держит только диалог, а не экран: диалог отпускает вьюхи, когда экран закрыт
        DownloadService.setListener(new DownloadService.Listener() {
            public void onFileStart(DownloadService.Job j, int index, int total, String name, boolean sizeKnown) {
                td.begin(name, sizeKnown);
            }

            public void onProgress(DownloadService.Job j, long done, long total) {
                td.update(done, total);
            }

            public void onFinished(DownloadService.Job j, int saved, Exception error, boolean cancelled) {
                td.dismiss();
                DownloadService.setListener(null);
            }
        });
        DownloadService.enqueue(act, job);
    }

    /** Недокачанные .part старше суток остаются, если приложение убили посреди скачивания. */
    static void cleanStale(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long limit = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (int i = 0; i < files.length; i++) {
            if (files[i].getName().endsWith(".part") && files[i].lastModified() < limit) {
                files[i].delete();
            }
        }
    }

    static File targetDir(Context ctx) {
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File pub = new File(Environment.getExternalStorageDirectory(), "Download/" + DIR);
            if ((pub.isDirectory() || pub.mkdirs()) && pub.canWrite()) {
                return pub;
            }
            if (Api.sdkLevel() >= 8) {
                File own = ctx.getExternalFilesDir(null);
                if (own != null) {
                    File d = new File(own, "Download");
                    if ((d.isDirectory() || d.mkdirs()) && d.canWrite()) {
                        return d;
                    }
                }
            }
        }
        return null;
    }

    /** Убирает символы, недопустимые в именах файлов на карте памяти. */
    static String sanitize(String name) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean bad = c < 32 || "/\\:*?\"<>|".indexOf(c) >= 0;
            sb.append(bad ? '_' : c);
        }
        String s = sb.toString().trim();
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        return s.length() == 0 ? "file" : s;
    }

    /** Если файл с таким именем уже есть, добавляет " (1)", " (2)" перед расширением. */
    static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) {
                return f;
            }
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }
}
