package org.mpgram.droid;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;

import java.lang.reflect.Method;

/** Уведомления для служб отправки и скачивания: одинаково собираются на Android от 1.5 до 4.4. */
class Notifs {

    /**
     * ongoing: идущая работа (не смахивается); percent >= 0 добавляет полосу прогресса на
     * Android 4.0+, на более старых прогресс виден в тексте.
     */
    static Notification make(Context ctx, int icon, String title, String text, PendingIntent pi,
                             boolean ongoing, int percent) {
        if (Api.sdkLevel() >= 11) {
            return Notif11.build(ctx, icon, title, text, pi, ongoing, percent);
        }
        // Android 1.5-2.3: уведомление собирается по-старому; setLatestEventInfo вызываем через
        // рефлексию, потому что в новых SDK его уже нет
        Notification n = new Notification(icon, title, System.currentTimeMillis());
        n.flags |= ongoing ? Notification.FLAG_ONGOING_EVENT : Notification.FLAG_AUTO_CANCEL;
        try {
            Method m = Notification.class.getMethod("setLatestEventInfo",
                    Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
            m.invoke(n, ctx, title, text, pi);
        } catch (Exception e) {
            // Без текста значок всё равно покажется
        }
        return n;
    }
}
