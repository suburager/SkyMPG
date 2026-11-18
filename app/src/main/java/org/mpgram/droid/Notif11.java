package org.mpgram.droid;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;

/**
 * Уведомление через Notification.Builder (Android 3.0+). Вынесено в отдельный класс, чтобы на
 * более старых версиях, где Builder нет, он даже не загружался.
 */
class Notif11 {

    static Notification build(Context ctx, int icon, String title, String text, PendingIntent pi,
                              boolean ongoing, int percent) {
        Notification.Builder b = new Notification.Builder(ctx);
        b.setSmallIcon(icon);
        b.setContentTitle(title);
        b.setContentText(text);
        b.setContentIntent(pi);
        b.setOngoing(ongoing);
        b.setAutoCancel(!ongoing);
        b.setWhen(System.currentTimeMillis());
        if (ongoing && percent >= 0 && Api.sdkLevel() >= 14) {
            b.setProgress(100, percent, false); // полоса прогресса в уведомлении с Android 4.0
        }
        return Api.sdkLevel() >= 16 ? b.build() : b.getNotification();
    }
}
