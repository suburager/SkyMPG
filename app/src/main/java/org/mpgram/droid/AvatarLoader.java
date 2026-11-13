package org.mpgram.droid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Круглые аватарки (у чатов с топиками квадратные со скруглёнными углами) через ava.php. Кэш в памяти и на диске (неделя), два фоновых потока с очередью
 * "последние запросы первыми", запросы для строк, уже ушедших с экрана, пропускаются,
 * во время быстрой прокрутки сеть не используется. Пока фото нет, показывается кружок с буквой.
 */
public class AvatarLoader {

    /** Связь с экраном списка. */
    public interface Listener {
        /** Нужна ли ещё эта аватарка (строка на экране или рядом). Вызывается из фонового потока. */
        boolean isWanted(String id);

        /** Аватарка загружена и лежит в кэше. Вызывается в UI-потоке. */
        void onAvatarLoaded(String id);
    }

    private static class LruMap extends LinkedHashMap<String, Bitmap> {
        private final int max;

        LruMap(int max) {
            super(32, 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > max;
        }
    }

    private static final int DP = 48;
    private static final long DISK_TTL = 7L * 24 * 60 * 60 * 1000;
    private static final long FAIL_BACKOFF = 60000;
    private static final int[] COLORS = new int[] {
            0xFFE17076, 0xFFEDA86C, 0xFFA695E7, 0xFF7BC862,
            0xFF6EC9CB, 0xFF65AADD, 0xFFEE7AAE
    };

    private final Api api;
    private final Listener listener;
    private final File dir;
    private final int px;
    private final Handler handler = new Handler();
    private final LifoExecutor queue = new LifoExecutor(2);
    private final HashSet<String> pending = new HashSet<String>();
    private final HashMap<String, Long> failed = new HashMap<String, Long>();
    // На экране помещается 8-10 строк; больше держать незачем: на 2.x пиксели bitmap идут в бюджет 16 МБ
    private final LruMap avatars = new LruMap(48);
    private final LruMap placeholders = new LruMap(48);
    private volatile boolean paused;

    /** Создавать в UI-потоке. Размер аватарки по умолчанию 48dp (строка списка). */
    public AvatarLoader(Context ctx, Api api, Listener listener) {
        this(ctx, api, listener, DP);
    }

    /** sizeDp: крупная аватарка, например на экране информации о чате. */
    public AvatarLoader(Context ctx, Api api, Listener listener, int sizeDp) {
        this.api = api;
        this.listener = listener;
        this.dir = new File(ctx.getFilesDir(), "ava");
        this.dir.mkdirs();
        this.px = Ui.px(ctx, sizeDp);
    }

    public void shutdown() {
        queue.shutdown();
    }

    /** Пока true, новые аватарки не качаются (быстрая прокрутка), показываются только готовые. */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** Удаляет файлы кэша (при выходе из аккаунта). */
    public static void clearDisk(Context ctx) {
        File[] files = new File(ctx.getFilesDir(), "ava").listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                files[i].delete();
            }
        }
    }

    /** Вызывать при заполнении строки: сразу ставит готовую картинку или заглушку, фото подгружается в фоне. */
    public void load(ImageView view, String id, String title, boolean hasPhoto) {
        load(view, id, title, hasPhoto, false);
    }

    /**
     * square: чат с топиками. Как в Telegram, его аватарка квадрат со скруглёнными углами,
     * а не круг. Форма входит в ключ кэша, поэтому круглый и квадратный вид не смешиваются.
     */
    public void load(ImageView view, final String id, String title, boolean hasPhoto, final boolean square) {
        final String key = square ? id + "#" : id;
        Bitmap b;
        synchronized (avatars) {
            b = avatars.get(key);
        }
        if (b != null) {
            if (view.getTag() != b) { // та же картинка уже стоит: не перерисовываем строку зря
                view.setTag(b);
                view.setImageBitmap(b);
            }
            return;
        }
        Bitmap ph = placeholder(id, title, square);
        if (view.getTag() != ph) {
            view.setTag(ph);
            view.setImageBitmap(ph);
        }
        if (!hasPhoto || paused) {
            return;
        }
        synchronized (failed) {
            Long t = failed.get(id);
            if (t != null && System.currentTimeMillis() - t.longValue() < FAIL_BACKOFF) {
                return;
            }
        }
        synchronized (pending) {
            if (!pending.add(id)) {
                return; // уже загружается
            }
        }
        queue.execute(new Runnable() {
            public void run() {
                try {
                    if (!listener.isWanted(id)) {
                        return; // строку уже прокрутили, не тратим трафик
                    }
                    Bitmap result = fetch(id, square);
                    if (result == null) {
                        synchronized (failed) {
                            failed.put(id, Long.valueOf(System.currentTimeMillis()));
                        }
                        return;
                    }
                    synchronized (avatars) {
                        avatars.put(key, result);
                    }
                    handler.post(new Runnable() {
                        public void run() {
                            listener.onAvatarLoaded(id);
                        }
                    });
                } finally {
                    synchronized (pending) {
                        pending.remove(id);
                    }
                }
            }
        });
    }

    private Bitmap fetch(String id, boolean square) {
        try {
            File f = new File(dir, id + "_" + px + ".jpg");
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < DISK_TTL) {
                Bitmap cached = BitmapFactory.decodeFile(f.getPath());
                if (cached != null) {
                    return shape(cached, square);
                }
            }
            // a=1: если у чата нет фото, сервер отвечает 404 вместо картинки-заглушки
            byte[] data = api.fetch("ava.php?c=" + Api.enc(id) + "&p=r" + px + "&a=1");
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
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
            return shape(bmp, square);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Круг или (для чатов с топиками) квадрат со скруглёнными углами. */
    private void drawShape(Canvas canvas, Paint paint, boolean square) {
        if (square) {
            float r = px * 0.3f;
            canvas.drawRoundRect(new RectF(0, 0, px, px), r, r, paint);
        } else {
            canvas.drawCircle(px / 2f, px / 2f, px / 2f, paint);
        }
    }

    private Bitmap shape(Bitmap src, boolean square) {
        Bitmap scaled = src;
        if (src.getWidth() != px || src.getHeight() != px) {
            scaled = Bitmap.createScaledBitmap(src, px, px, true);
        }
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        drawShape(canvas, paint, square);
        // Исходники больше не нужны; без recycle() их пиксели живут до сборки мусора
        if (scaled != src) {
            scaled.recycle();
        }
        src.recycle();
        return out;
    }

    /** Цветной кружок с первой буквой названия. */
    private Bitmap placeholder(String id, String title, boolean square) {
        String key = (square ? "#" : "") + id + "\n" + title;
        synchronized (placeholders) {
            Bitmap cached = placeholders.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(COLORS[Math.abs(id.hashCode()) % COLORS.length]);
        drawShape(canvas, paint, square);

        paint.setColor(0xFFFFFFFF);
        paint.setTextSize(px * 0.45f);
        paint.setTextAlign(Paint.Align.CENTER);
        float y = px / 2f - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText(firstLetter(title), px / 2f, y, paint);

        synchronized (placeholders) {
            placeholders.put(key, out);
        }
        return out;
    }

    private static String firstLetter(String title) {
        String t = title == null ? "" : title.trim();
        if (t.length() == 0) {
            return "?";
        }
        int len = Character.isHighSurrogate(t.charAt(0)) && t.length() > 1 ? 2 : 1;
        return t.substring(0, len).toUpperCase();
    }
}
