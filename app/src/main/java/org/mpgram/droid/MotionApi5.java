package org.mpgram.droid;

import android.view.MotionEvent;

/**
 * Мультитач MotionEvent (Android 2.0, API 5): getPointerCount() и getX/getY(int).
 * Отдельный класс, грузится только при SDK >= 5; на 1.5 и 1.6 щипка нет, остаётся один палец.
 */
class MotionApi5 {

    static int count(MotionEvent e) {
        return e.getPointerCount();
    }

    static float dist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    static float midX(MotionEvent e) {
        return (e.getX(0) + e.getX(1)) / 2;
    }

    static float midY(MotionEvent e) {
        return (e.getY(0) + e.getY(1)) / 2;
    }
}
