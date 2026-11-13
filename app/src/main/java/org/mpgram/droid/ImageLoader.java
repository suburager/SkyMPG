package org.mpgram.droid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Картинки из сообщений чата (file.php, уменьшенный JPEG). Кэш в памяти ограничен по байтам
 * (на Android 2.x пиксели bitmap идут в бюджет 16 МБ), кэш на диске живёт неделю, два фоновых
 * потока с очередью "последние запросы первыми". Строки, которые уже прокрутили, не качаются.
 * Пока картинка грузится, к ней можно подписать несколько ImageView (список пересоздаёт
 * строки при каждом обновлении): готовый результат получат все, кто ещё ждёт именно её.
 */
public class ImageLoader {

    private static final long DISK_TTL = 7L * 24 * 60 * 60 * 1000;
    private static final long FAIL_BACKOFF = 60000;
    // Больше кэш: при прокрутке чата с картинками меньше повторных загрузок, а значит меньше мигания
    private static final int MEMORY_BYTES = memoryBudget();

    private static int memoryBudget() {
        long heap = Runtime.getRuntime().maxMemory();
        return heap >= 48L * 1024 * 1024 ? 8 * 1024 * 1024
                : heap >= 32L * 1024 * 1024 ? 6 * 1024 * 1024
                : heap >= 24L * 1024 * 1024 ? 4 * 1024 * 1024 : 3 * 1024 * 1024;
    }

    private final Api api;
    private final File dir;
    private final Handler handler = new Handler();
    private final LifoExecutor queue = new LifoExecutor(2);
    /** key -> те, кто ждёт эту картинку; наличие ключа значит, что она уже грузится. */
    private final HashMap<String, ArrayList<ImageView>> waiting = new HashMap<String, ArrayList<ImageView>>();
    private final HashMap<String, Long> failed = new HashMap<String, Long>();
    private final LinkedHashMap<String, Bitmap> memory = new LinkedHashMap<String, Bitmap>(16, 0.75f, true);
    private int memoryBytes;
    private volatile boolean paused;
    /** Строки, прокрученные во время флинга: загрузка стартует, когда прокрутка остановится. */
    private final ArrayList<Object[]> deferred = new ArrayList<Object[]>();

    /** Создавать в UI-потоке. */
    public ImageLoader(Context ctx, Api api) {
        this.api = api;
        this.dir = new File(ctx.getFilesDir(), "img");
        this.dir.mkdirs();
    }

    /** Пока прокрутка летит (флинг), новые картинки не грузим; после остановки догружаем нужные. */
    public void setPaused(boolean p) {
        if (paused == p) {
            return;
        }
        paused = p;
        if (p) {
            return;
        }
        ArrayList<Object[]> todo;
        synchronized (deferred) {
            todo = new ArrayList<Object[]>(deferred);
            deferred.clear();
        }
        for (int i = 0; i < todo.size(); i++) {
            Object[] d = todo.get(i);
            ImageView v = (ImageView) d[0];
            if (d[1].equals(v.getTag())) { // строка ещё показывает именно эту картинку
                load(v, (String) d[1], (String) d[2]);
            }
        }
    }

    public void shutdown() {
        queue.shutdown();
        synchronized (waiting) {
            waiting.clear(); // ImageView держат экран; после закрытия они не нужны
        }
    }

    /** Удаляет файлы кэша (при выходе из аккаунта). */
    public static void clearDisk(Context ctx) {
        File[] files = new File(ctx.getFilesDir(), "img").listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                files[i].delete();
            }
        }
    }

    /**
     * Вызывать при заполнении строки. key уникален для картинки и размера, path это запрос к
     * серверу. Готовая картинка ставится сразу, иначе серая заглушка, а картинка подгружается.
     */
    public void load(final ImageView view, final String key, final String path) {
        if (key.equals(view.getTag()) && view.getDrawable() != null) {
            return; // уже показывает именно эту картинку
        }
        view.setTag(key);
        Bitmap b;
        synchronized (memory) {
            b = memory.get(key);
        }
        if (b != null) {
            view.setImageBitmap(b);
            return;
        }
        view.setImageBitmap(null);
        if (paused) {
            // Флинг: процессор нужен прокрутке, а не декодированию картинок
            synchronized (deferred) {
                if (deferred.size() >= 48) {
                    deferred.remove(0);
                }
                deferred.add(new Object[] {view, key, path});
            }
            return;
        }
        synchronized (failed) {
            Long t = failed.get(key);
            if (t != null && System.currentTimeMillis() - t.longValue() < FAIL_BACKOFF) {
                return;
            }
        }
        synchronized (waiting) {
            ArrayList<ImageView> list = waiting.get(key);
            if (list != null) {
                list.add(view); // уже загружается: просто дождёмся её
                return;
            }
            list = new ArrayList<ImageView>();
            list.add(view);
            waiting.put(key, list);
        }
        queue.execute(new Runnable() {
            public void run() {
                if (!anyWaiting(key)) {
                    // Все строки уже прокрутили или переиспользовали: трафик не тратим
                    synchronized (waiting) {
                        waiting.remove(key);
                    }
                    return;
                }
                final Bitmap result = fetch(key, path);
                if (result != null) {
                    // В память кладём до того, как снимем ожидание: опоздавший load() увидит готовое
                    put(key, result);
                } else {
                    synchronized (failed) {
                        failed.put(key, Long.valueOf(System.currentTimeMillis()));
                    }
                }
                final ArrayList<ImageView> views;
                synchronized (waiting) {
                    views = waiting.remove(key);
                }
                if (result == null || views == null) {
                    return;
                }
                handler.post(new Runnable() {
                    public void run() {
                        for (int i = 0; i < views.size(); i++) {
                            ImageView v = views.get(i);
                            if (key.equals(v.getTag())) {
                                v.setImageBitmap(result);
                            }
                        }
                    }
                });
            }
        });
    }

    /** Есть ли ещё хоть одна строка, ждущая именно эту картинку. */
    private boolean anyWaiting(String key) {
        synchronized (waiting) {
            ArrayList<ImageView> list = waiting.get(key);
            if (list == null) {
                return false;
            }
            for (int i = 0; i < list.size(); i++) {
                if (key.equals(list.get(i).getTag())) {
                    return true;
                }
            }
            return false;
        }
    }

    private void put(String key, Bitmap b) {
        synchronized (memory) {
            Bitmap old = memory.put(key, b);
            if (old != null) {
                memoryBytes -= size(old);
            }
            memoryBytes += size(b);
            // Вытесняем самые давние, но последнюю добавленную картинку оставляем всегда
            while (memoryBytes > MEMORY_BYTES && memory.size() > 1) {
                Map.Entry<String, Bitmap> eldest = memory.entrySet().iterator().next();
                memoryBytes -= size(eldest.getValue());
                memory.remove(eldest.getKey());
            }
        }
    }

    private static int size(Bitmap b) {
        return b.getRowBytes() * b.getHeight();
    }

    private Bitmap fetch(String key, String path) {
        try {
            File f = new File(dir, safeName(key) + ".jpg");
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < DISK_TTL) {
                Bitmap cached = decode(f);
                if (cached != null) {
                    return cached;
                }
            }
            byte[] data = api.fetch(path);
            Bitmap bmp = decodeBytes(data);
            if (bmp == null) {
                return null;
            }
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(f);
                out.write(data);
            } catch (Exception e) {
                // Без дискового кэша тоже работаем
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Exception e) {
                        // Закрываем молча
                    }
                }
            }
            return bmp;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * RGB_565 вдвое экономнее по памяти, для превью хватает. На слабых телефонах с кучей 16 МБ
     * декодирование может не поместиться: тогда повторяем с уменьшением вдвое (до 1/4 стороны).
     */
    private static Bitmap decodeBytes(byte[] data) {
        for (int sample = 1; sample <= 4; sample *= 2) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inSampleSize = sample;
                return BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } catch (OutOfMemoryError e) {
                // пробуем меньший размер
            }
        }
        return null;
    }

    private static Bitmap decode(File f) {
        for (int sample = 1; sample <= 4; sample *= 2) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inSampleSize = sample;
                return BitmapFactory.decodeFile(f.getPath(), o);
            } catch (OutOfMemoryError e) {
                // пробуем меньший размер
            }
        }
        return null;
    }

    private static String safeName(String key) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            sb.append((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ? c : '_');
        }
        return sb.toString();
    }
}
