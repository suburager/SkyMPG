package org.mpgram.droid;

import android.app.Service;

import java.lang.reflect.Method;

/**
 * Service.setForeground(boolean) есть в рантайме Android 1.5 и 1.6 (API 3-4), но убран из
 * публичного android.jar, поэтому вызывается рефлексией. Нужен только при SDK < 5, где
 * startForeground() ещё нет. Любая ошибка глушится: без foreground сервис просто может
 * быть убит системой раньше, но работать продолжит.
 */
class SvcLegacy {

    static void fg(Service s, boolean on) {
        try {
            Method m = Service.class.getMethod("setForeground", new Class[] { boolean.class });
            m.invoke(s, new Object[] { Boolean.valueOf(on) });
        } catch (Throwable ignored) {
            // метода нет или он недоступен: продолжаем без foreground
        }
    }
}
