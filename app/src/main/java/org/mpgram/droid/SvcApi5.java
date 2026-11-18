package org.mpgram.droid;

import android.app.Notification;
import android.app.Service;

/**
 * Service.startForeground()/stopForeground() (Android 2.0, API 5). Отдельный класс: прямой
 * вызов в сервисе валит верификатор самого сервиса на 1.5 и 1.6. Грузится только при SDK >= 5.
 */
class SvcApi5 {

    static void fg(Service s, int id, Notification n) {
        s.startForeground(id, n);
    }

    static void bg(Service s, boolean removeNotification) {
        s.stopForeground(removeNotification);
    }
}
