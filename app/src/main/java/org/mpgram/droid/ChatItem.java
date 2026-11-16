package org.mpgram.droid;

import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.SpannedString;
import android.text.style.ForegroundColorSpan;

/** Одна строка списка чатов. Изменять только из UI-потока (после публикации в списке). */
public class ChatItem {

    public static final int ACCENT = 0xFF5C9CFF;

    public String id = "";
    public String title = "";
    public String previewPrefix = "";
    public String preview = "";
    public String time = "";
    public int unread;
    public String unreadText = "";
    public boolean pinned;
    public boolean muted;
    public boolean hasPhoto;
    /** Чат с топиками (форум): аватарка рисуется квадратом со скруглёнными углами. */
    public boolean forum;
    /** Квадратная аватарка вычислена один раз (чтение настроек на каждую прорисовку строки дорого). */
    public boolean squareChecked;
    public boolean square;

    /** Есть что показать в строке сообщения (с сервера или из кэша). */
    public boolean hasMsg;
    /** Сообщение получено с сервера в этом сеансе. */
    public boolean msgFresh;
    /** Запрос последнего сообщения уже выполняется. */
    public boolean msgRequested;
    /** Время последней неудачной попытки, чтобы не долбить сервер без сети. */
    public long failAt;
    /** Позиция в списке. */
    public int index;

    /** Готовая строка превью: цветное имя отправителя и серый текст. Строится один раз, не при каждой отрисовке. */
    public CharSequence line = "";

    public void setUnread(int n) {
        unread = n;
        unreadText = n > 0 ? String.valueOf(n) : "";
    }

    public void buildLine() {
        if (previewPrefix.length() == 0) {
            line = preview;
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(previewPrefix + preview);
        sb.setSpan(new ForegroundColorSpan(ACCENT), 0, previewPrefix.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        // Неизменяемая копия: TextView не копирует её при каждом setText
        line = new SpannedString(sb);
    }
}
