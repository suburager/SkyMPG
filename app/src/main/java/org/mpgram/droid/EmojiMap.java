package org.mpgram.droid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.ReplacementSpan;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Эмодзи как картинки: на Android 1.5-4.4 системный шрифт их почти не умеет рисовать.
 * Картинки лежат в assets/emoji (по одной на эмодзи, 40x40), таблица соответствия в index.txt:
 * "последовательность кодовых точек, номер картинки". Текст просматривается один раз, найденные
 * эмодзи закрываются EmojiSpan: сам текст не меняется, поэтому смещения ссылок и форматирования
 * остаются верными, а отправляется он обычным Юникодом.
 *
 * Правила распознавания как у Telegram: самый длинный подходящий вариант (флаги, семьи,
 * цвета кожи), U+FE0F необязателен, а символы вроде (c), (R), цифр и # считаются эмодзи
 * только в составе рамки-клавиши или с U+FE0F. Если варианта с цветом кожи нет, рисуется
 * эмодзи без цвета.
 *
 * Бережёт слабые телефоны (около 800 МГц, куча 24 МБ): таблицы читаются только когда в тексте
 * впервые встретилось что-то похожее на эмодзи (русский и английский текст их не трогает),
 * список панели выбора читается отдельно и только при открытии панели, а картинки держатся
 * в небольших кэшах.
 */
public class EmojiMap {

    /** Один эмодзи для панели выбора. */
    public static class Pick {
        public int id;
        public String text; // что вставлять в поле ввода (со всеми U+FE0F)
    }

    private static final int FE0F = 0xFE0F;
    private static final int KEYCAP = 0x20E3;
    private static final int ORIGINAL_CACHE = 96;   // исходные 40x40 (панель выбора, источник для масштабов)
    private static final int SCALED_CACHE = 160;    // уже подогнанные под строку шрифта

    private static Context appContext;
    private static boolean loaded;
    private static boolean pickerLoaded;
    private static final HashMap<String, Integer> ids = new HashMap<String, Integer>(8192);
    private static final HashSet<String> needsFe0f = new HashSet<String>();
    private static final boolean[] seen = new boolean[65536]; // символы, встречающиеся в эмодзи
    private static int maxLen;
    private static final ArrayList<ArrayList<Pick>> categories = new ArrayList<ArrayList<Pick>>();

    private static final LinkedHashMap<Integer, Bitmap> originals =
            new LinkedHashMap<Integer, Bitmap>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, Bitmap> eldest) {
                    return size() > ORIGINAL_CACHE;
                }
            };
    private static final LinkedHashMap<Integer, Bitmap> scaledCache =
            new LinkedHashMap<Integer, Bitmap>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, Bitmap> eldest) {
                    return size() > SCALED_CACHE;
                }
            };

    /** Запоминает контекст приложения; таблицы читаются при первой надобности. */
    public static void attach(Context ctx) {
        if (appContext == null) {
            appContext = ctx.getApplicationContext();
        }
    }

    // ---------- Таблицы ----------

    /** Таблица распознавания (один раз, из любого потока). */
    private static synchronized void ensure() {
        if (loaded || appContext == null) {
            return;
        }
        loaded = true;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    appContext.getAssets().open("emoji/index.txt"), "UTF-8"), 8192);
            String line;
            while ((line = r.readLine()) != null) {
                int t1 = line.indexOf('\t');
                if (t1 <= 0) {
                    continue;
                }
                int t2 = line.indexOf('\t', t1 + 1);
                String key = keyFromHex(line.substring(0, t1));
                int id = Integer.parseInt(t2 < 0 ? line.substring(t1 + 1) : line.substring(t1 + 1, t2));
                ids.put(key, Integer.valueOf(id));
                if (t2 >= 0 && t2 + 1 < line.length() && line.charAt(t2 + 1) == 't') {
                    needsFe0f.add(key);
                }
                if (key.length() > maxLen) {
                    maxLen = key.length();
                }
                for (int i = 0; i < key.length(); i++) {
                    seen[key.charAt(i)] = true;
                }
            }
            r.close();
            seen[FE0F] = true;
        } catch (Exception e) {
            // Без таблицы эмодзи остаются обычным текстом
        }
    }

    /** Список для панели выбора: читается только при её открытии. */
    private static synchronized void ensurePicker() {
        if (pickerLoaded || appContext == null) {
            return;
        }
        pickerLoaded = true;
        for (int i = 0; i < 8; i++) {
            categories.add(new ArrayList<Pick>());
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    appContext.getAssets().open("emoji/picker.txt"), "UTF-8"), 8192);
            String line;
            while ((line = r.readLine()) != null) {
                int t1 = line.indexOf('\t');
                int t2 = t1 < 0 ? -1 : line.indexOf('\t', t1 + 1);
                if (t2 < 0) {
                    continue;
                }
                int cat = Integer.parseInt(line.substring(0, t1));
                Pick pk = new Pick();
                pk.id = Integer.parseInt(line.substring(t1 + 1, t2));
                pk.text = keyFromHex(line.substring(t2 + 1));
                if (cat >= 0 && cat < categories.size()) {
                    categories.get(cat).add(pk);
                }
            }
            r.close();
        } catch (Exception e) {
            // Панель останется пустой
        }
    }

    /** "1F468-200D-1F469" в строку из этих символов. */
    private static String keyFromHex(String hex) {
        StringBuilder sb = new StringBuilder(hex.length() / 3 + 2);
        int i = 0;
        int n = hex.length();
        while (i < n) {
            int j = hex.indexOf('-', i);
            if (j < 0) {
                j = n;
            }
            if (j > i) {
                sb.appendCodePoint(Integer.parseInt(hex.substring(i, j), 16));
            }
            i = j + 1;
        }
        return sb.toString();
    }

    // ---------- Поиск эмодзи в тексте ----------

    /** Может ли символ быть частью эмодзи (дешёвая проверка до чтения таблиц). */
    private static boolean emojiish(char c) {
        if (c < 0xA9) {
            return false;
        }
        return (c >= 0xD800 && c <= 0xDBFF)                       // начало пары суррогатов: почти все эмодзи
                || c == 0xA9 || c == 0xAE || c == 0x203C || c == 0x2049 || c == 0x2122 || c == 0x2139
                || c == FE0F || c == KEYCAP
                || (c >= 0x2190 && c <= 0x21FF) || (c >= 0x2300 && c <= 0x23FF)
                || c == 0x24C2 || (c >= 0x25A0 && c <= 0x25FF) || (c >= 0x2600 && c <= 0x27BF)
                || (c >= 0x2900 && c <= 0x297F) || (c >= 0x2B00 && c <= 0x2BFF)
                || c == 0x3030 || c == 0x303D || c == 0x3297 || c == 0x3299;
    }

    /** Есть ли в тексте то, что может оказаться эмодзи. Для обычного текста таблицы не читаются. */
    public static boolean maybeHas(CharSequence s) {
        if (s == null) {
            return false;
        }
        for (int i = 0, n = s.length(); i < n; i++) {
            if (emojiish(s.charAt(i))) {
                ensure();
                return !ids.isEmpty();
            }
        }
        return false;
    }

    /**
     * Эмодзи, начинающийся в позиции i: возвращает длину в символах, а номер картинки кладёт в
     * idOut[0]. 0, если там не эмодзи.
     */
    private static int match(CharSequence s, int i, int n, int[] idOut) {
        char c = s.charAt(i);
        if (c < 0x80) {
            // Рамка-клавиша: цифра, # или *, затем необязательный U+FE0F и U+20E3
            if (!(c == '#' || c == '*' || (c >= '0' && c <= '9'))) {
                return 0;
            }
            int j = i + 1;
            if (j < n && s.charAt(j) == FE0F) {
                j++;
            }
            if (j < n && s.charAt(j) == KEYCAP) {
                Integer id = ids.get(String.valueOf(c) + (char) KEYCAP);
                if (id != null) {
                    idOut[0] = id.intValue();
                    return j + 1 - i;
                }
            }
            return 0;
        }
        if (!seen[c]) {
            return 0;
        }
        StringBuilder exact = new StringBuilder(16);
        StringBuilder bare = new StringBuilder(16); // то же без цвета кожи (запасной вариант)
        int limit = Math.min(n, i + maxLen + 8);
        int bestEnd = 0;
        int bestId = -1;
        int fallbackEnd = 0;
        int fallbackId = -1;
        int j = i;
        while (j < limit) {
            char ch = s.charAt(j);
            boolean fe = ch == FE0F;
            if (!fe) {
                if (ch < 0x80 || !seen[ch]) {
                    break;
                }
                exact.append(ch);
                if (ch >= 0xDFFB && ch <= 0xDFFF && j > i && s.charAt(j - 1) == 0xD83C) {
                    bare.setLength(bare.length() - 1); // убираем начало пары цвета кожи
                } else {
                    bare.append(ch);
                }
            }
            j++;
            Integer id = ids.get(exact.toString());
            if (id != null) {
                boolean needs = needsFe0f.contains(exact.toString());
                if (!needs || fe) { // символ с текстовым видом по умолчанию должен идти с U+FE0F
                    bestEnd = j;
                    bestId = id.intValue();
                }
            }
            if (bare.length() != exact.length()) {
                Integer bid = ids.get(bare.toString());
                if (bid != null) {
                    fallbackEnd = j;
                    fallbackId = bid.intValue();
                }
            }
        }
        if (fallbackEnd > bestEnd) {
            bestEnd = fallbackEnd;
            bestId = fallbackId;
        }
        if (bestEnd == 0) {
            return 0;
        }
        idOut[0] = bestId;
        return bestEnd - i;
    }

    /** Закрывает найденные эмодзи картинками. Уже закрытые места пропускает. */
    public static void addSpans(Spannable sp) {
        if (!maybeHas(sp)) {
            return;
        }
        int n = sp.length();
        int[] idOut = new int[1];
        int i = 0;
        while (i < n) {
            int len = match(sp, i, n, idOut);
            if (len > 0) {
                if (sp.getSpans(i, i + len, EmojiSpan.class).length == 0) {
                    sp.setSpan(new EmojiSpan(idOut[0]), i, i + len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    bitmap(idOut[0]); // декодируем сейчас (разбор сообщений идёт в фоновом потоке), а не при прорисовке
                }
                i += len;
            } else {
                i++;
            }
        }
    }

    /** Дольше этого эмодзи-сообщение уже читается как обычный текст и остаётся в пузыре. */
    public static final int MAX_ONLY = 10;

    /**
     * Если текст состоит только из эмодзи (и пробелов), возвращает их число (1..MAX_ONLY),
     * иначе 0. Нужны уже расставленные addSpans() эмодзи.
     */
    public static int onlyEmojiCount(Spanned sp) {
        int len = sp.length();
        if (len == 0 || len > 80) {
            return 0;
        }
        EmojiSpan[] spans = sp.getSpans(0, len, EmojiSpan.class);
        if (spans.length == 0 || spans.length > MAX_ONLY) {
            return 0;
        }
        int covered = 0;
        for (int i = 0; i < spans.length; i++) {
            covered += sp.getSpanEnd(spans[i]) - sp.getSpanStart(spans[i]);
        }
        if (len - covered > 12) {
            return 0; // слишком много всего остального, это не "только эмодзи"
        }
        for (int i = 0; i < len; i++) {
            if (!Character.isWhitespace(sp.charAt(i)) && sp.getSpans(i, i + 1, EmojiSpan.class).length == 0) {
                return 0;
            }
        }
        return spans.length;
    }

    /**
     * Реакция сервера без U+FE0F ("❤"): для символов, которые по умолчанию рисуются текстом,
     * добавляет его, чтобы нашлась та же картинка, что и в панели выбора.
     */
    public static String forceImage(String e) {
        if (e == null || e.length() == 0 || e.indexOf(FE0F) >= 0) {
            return e;
        }
        ensure();
        return needsFe0f.contains(e) ? e + (char) FE0F : e;
    }

    /** Текст в TextView с эмодзи-картинками; размер они берут из шрифта самого TextView. */
    public static void setText(TextView tv, CharSequence text) {
        attach(tv.getContext());
        if (maybeHas(text)) {
            SpannableStringBuilder sb = new SpannableStringBuilder(text);
            addSpans(sb);
            tv.setText(sb);
        } else {
            tv.setText(text);
        }
    }

    // ---------- Картинки ----------

    /** Исходная картинка эмодзи (40x40) или null. */
    public static Bitmap bitmap(int id) {
        Integer key = Integer.valueOf(id);
        synchronized (originals) {
            Bitmap b = originals.get(key);
            if (b != null) {
                return b;
            }
        }
        if (appContext == null) {
            return null;
        }
        Bitmap b = null;
        try {
            InputStream in = appContext.getAssets().open("emoji/" + id + ".png");
            try {
                b = BitmapFactory.decodeStream(in);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            // Нет картинки: место останется пустым
        }
        if (b != null) {
            synchronized (originals) {
                originals.put(key, b);
            }
        }
        return b;
    }

    /**
     * Картинка, уже подогнанная под px x px: в строке текста она рисуется без масштабирования
     * на лету (дёшево для процессора) и занимает мало памяти (при шрифте 16sp около 1,5 КБ).
     */
    private static Bitmap scaled(int id, int px) {
        Integer key = Integer.valueOf((id << 8) | (px & 0xFF)); // px до 255, id до 8 млн
        synchronized (scaledCache) {
            Bitmap b = scaledCache.get(key);
            if (b != null) {
                return b;
            }
        }
        Bitmap src = bitmap(id);
        if (src == null) {
            return null;
        }
        Bitmap b = src.getWidth() == px ? src : Bitmap.createScaledBitmap(src, px, px, true);
        synchronized (scaledCache) {
            scaledCache.put(key, b);
        }
        return b;
    }

    private static volatile int prefetchGen;

    /**
     * Заранее подгружает первые эмодзи вкладки фоновым потоком, чтобы панель открывалась без
     * рывков: декодирование на слабом процессоре в UI-потоке заметно при прокрутке.
     * Новая вкладка отменяет предыдущую подгрузку.
     */
    public static void prefetch(final ArrayList<Pick> list, final int count) {
        final int gen = ++prefetchGen;
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < list.size() && i < count; i++) {
                    if (gen != prefetchGen) {
                        return;
                    }
                    bitmap(list.get(i).id);
                }
            }
        });
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Картинка по "ключу" эмодзи, например "1F600"; для значков панели. -1, если нет. */
    public static int idOf(String hex) {
        ensure();
        Integer id = ids.get(keyFromHex(hex.replace("-FE0F", "")));
        return id == null ? -1 : id.intValue();
    }

    /**
     * Эмодзи в тексте. Размер берётся из шрифта (от верхней границы строки до нижней), поэтому
     * картинка всегда помещается в строку: ничего не обрезается сверху и строка не раздувается.
     */
    public static class EmojiSpan extends ReplacementSpan {
        private final int id;
        private Bitmap cached;   // подогнанная картинка: в draw() нет ни поиска в кэше, ни выделения памяти
        private int cachedSize;

        EmojiSpan(int id) {
            this.id = id;
        }

        private static int lineHeight(Paint paint) {
            int a = (int) Math.floor(paint.ascent());
            int d = (int) Math.ceil(paint.descent());
            return Math.max(8, Math.min(255, d - a));
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return lineHeight(paint);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                         int bottom, Paint paint) {
            int h = lineHeight(paint);
            if (cached == null || cachedSize != h) {
                cached = scaled(id, h);
                cachedSize = h;
            }
            if (cached != null) {
                canvas.drawBitmap(cached, x, y + (int) Math.floor(paint.ascent()), null);
            }
        }
    }

    // ---------- Панель выбора ----------

    /** Эмодзи категории 0..7: люди, природа, еда, спорт, путешествия, предметы, символы, флаги. */
    public static ArrayList<Pick> category(int index) {
        ensure();
        ensurePicker();
        return index >= 0 && index < categories.size() ? categories.get(index) : new ArrayList<Pick>();
    }

    private static final int MAX_RECENT = 40;

    /** Недавно использованные (первый самый свежий). */
    public static ArrayList<Pick> recent(Context ctx) {
        ensure();
        ArrayList<Pick> out = new ArrayList<Pick>();
        String raw = ctx.getSharedPreferences("emoji", Context.MODE_PRIVATE).getString("recent", "");
        int i = 0;
        while (i < raw.length()) {
            int j = raw.indexOf(' ', i);
            if (j < 0) {
                j = raw.length();
            }
            if (j > i) {
                String text = keyFromHex(raw.substring(i, j));
                Integer id = ids.get(text.replace("\uFE0F", ""));
                if (id != null) {
                    Pick p = new Pick();
                    p.id = id.intValue();
                    p.text = text;
                    out.add(p);
                }
            }
            i = j + 1;
        }
        return out;
    }

    public static void addRecent(Context ctx, Pick p) {
        ArrayList<Pick> list = recent(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).text.equals(p.text)) {
                list.remove(i);
                break;
            }
        }
        list.add(0, p);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < MAX_RECENT; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            String t = list.get(i).text;
            for (int k = 0; k < t.length(); ) {
                int cp = t.codePointAt(k);
                if (k > 0) {
                    sb.append('-');
                }
                sb.append(Integer.toHexString(cp).toUpperCase());
                k += Character.charCount(cp);
            }
        }
        SharedPreferences.Editor e = ctx.getSharedPreferences("emoji", Context.MODE_PRIVATE).edit();
        e.putString("recent", sb.toString());
        Api.apply(e);
    }
}
