package org.mpgram.droid;

import android.text.SpannableStringBuilder;

import java.util.ArrayList;

/** Одно сообщение в экране чата. */
public class MsgItem {
    public int id;
    public long date;
    public long dayKey;
    public String time = "";
    public boolean out;

    /** Отправляется сейчас, ещё не подтверждено сервером. */
    public boolean pending;

    /** Служебное сообщение: показывается по центру без пузыря. */
    public boolean service;
    public String serviceText = "";

    /** Имя отправителя (только для входящих в группах) и его цвет. */
    public String sender = "";
    public int senderColor = 0xFF444444;

    public String fwd = "";
    public String quote = "";
    /** id сообщения, на которое это ответ (0, если не ответ) */
    public int replyId;
    public CharSequence body = "";
    public boolean hasLinks;

    /** Вложение-картинка (фото или файл-изображение): вместо подписи показывается превью. */
    public boolean image;
    /** Размеры оригинала, если сервер их сообщил (иначе 0). */
    public int imgW;
    public int imgH;

    /** Вложение можно скачать (фото или файл) и под каким именем. */
    public boolean downloadable;

    /** Служебное сообщение "закреплено": по нему плашка закрепа понимает, что закреп сменился. */
    public boolean pinEvent;

    /** Сообщение из одних эмодзи (их число, 1..10): рисуется без пузыря, одиночное крупно. 0: обычное. */
    public int emojiOnly;

    /** Автор входящего сообщения в группе (id): касание его имени открывает профиль. */
    public String senderId = "";

    /** Реакции: сколько всего и, если сервер отдаёт, список эмодзи со счётчиками. */
    public int reactCount;
    /** Эмодзи и счётчики по отдельности (по порядку от сервера); null: сервер отдал только общее число. */
    public String[] reactEmoji;
    public int[] reactNum;
    /** Готовые подписи капсул (собираются при показе и переиспользуются). */
    public CharSequence[] reactLabels;

    /** Текст с просветом под время в конце (собирается при показе и переиспользуется). */
    public CharSequence bodyGap;
    public int gapWidth;
    /** Подпись вложения ("Фото", имя файла): запасной текст, когда у сообщения нет подписи (плашка закрепа). */
    public String mediaLabel = "";
    public String fileName = "";
    public long fileSize;

    /** Ключ группы (альбом): сообщения подряд с одним ключом показываются одним баблом. 0: одиночное. */
    public long groupKey;

    /** Одно вложение внутри склеенного бабла. */
    public static class Part {
        public int id;
        public boolean image;
        public int w;
        public int h;
        public boolean downloadable;
        public String fileName = "";
        public long fileSize;
    }

    /** Не null у склеенной группы: все её вложения по порядку. У одиночного сообщения null. */
    public ArrayList<Part> parts;
    private ArrayList<CharSequence> bodies;

    public Part toPart() {
        Part p = new Part();
        p.id = id;
        p.image = image;
        p.w = imgW;
        p.h = imgH;
        p.downloadable = downloadable;
        p.fileName = fileName;
        p.fileSize = fileSize;
        return p;
    }

    /** Присоединяет следующее сообщение группы: подписи склеиваются, время и id берутся от последнего. */
    public void merge(MsgItem m) {
        if (parts == null) {
            parts = new ArrayList<Part>();
            parts.add(toPart());
            bodies = new ArrayList<CharSequence>();
            bodies.add(body);
        }
        parts.add(m.toPart());
        bodies.add(m.body);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < bodies.size(); i++) {
            CharSequence b = bodies.get(i);
            if (b != null && b.length() > 0) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(b);
            }
        }
        body = sb;
        hasLinks = hasLinks || m.hasLinks;
        downloadable = downloadable || m.downloadable;
        id = m.id;
        time = m.time;
    }

    /** Разделитель с датой перед этим сообщением. */
    public boolean showDate;
    public String dateLabel = "";
}
