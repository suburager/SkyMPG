package org.mpgram.droid;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Адаптивность под размер экрана. Телефоны (наименьшая сторона до ~400dp) остаются как были,
 * на планшетах и больших экранах шрифты, кнопки, отступы и картинки растут пропорционально.
 * Без AndroidX и без новых API: работает на Android 1.5+.
 */
public final class Ui {

    private static float cached;

    private Ui() {
    }

    /** Множитель интерфейса: 1.0 на телефонах, до 1.6 на больших экранах. */
    public static float scale(Context c) {
        if (cached > 0f) {
            return cached;
        }
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float sw = Math.min(dm.widthPixels, dm.heightPixels) / dm.density; // наименьшая сторона в dp
        float s = 1f + Math.max(0f, sw - 400f) / 400f * 0.75f;
        if (s > 1.6f) {
            s = 1.6f;
        }
        if (s < 1.05f) {
            s = 1f;
        }
        cached = s;
        return s;
    }

    /** dp в пикселях с учётом множителя интерфейса. */
    public static int px(Context c, float dp) {
        return (int) (dp * c.getResources().getDisplayMetrics().density * scale(c) + 0.5f);
    }

    /** Масштабирует только что созданное дерево видов (строка списка, диалог). Возвращает его же. */
    public static View scaleTree(View v) {
        float s = scale(v.getContext());
        if (s != 1f) {
            scaleView(v, s);
        }
        return v;
    }

    /** Масштабирует содержимое экрана, уже поставленное через setContentView. */
    public static void scaleContent(android.app.Activity a) {
        float s = scale(a);
        if (s == 1f) {
            return;
        }
        View content = a.findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) content;
            for (int i = 0; i < g.getChildCount(); i++) {
                scaleView(g.getChildAt(i), s);
            }
        }
    }

    private static void scaleView(View v, float s) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            if (lp.width > 0) {
                lp.width = Math.round(lp.width * s);
            }
            if (lp.height > 0) {
                lp.height = Math.round(lp.height * s);
            }
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                m.leftMargin = Math.round(m.leftMargin * s);
                m.topMargin = Math.round(m.topMargin * s);
                m.rightMargin = Math.round(m.rightMargin * s);
                m.bottomMargin = Math.round(m.bottomMargin * s);
            }
        }
        v.setPadding(Math.round(v.getPaddingLeft() * s), Math.round(v.getPaddingTop() * s),
                Math.round(v.getPaddingRight() * s), Math.round(v.getPaddingBottom() * s));
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.getTextSize() * s);
        }
        if (v instanceof ImageButton && lp != null && lp.width > 0 && lp.height > 0) {
            fitIcon((ImageButton) v, lp.width, lp.height, s);
        }
        if (v instanceof ViewGroup && !(v instanceof AdapterView)) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                scaleView(g.getChildAt(i), s);
            }
        }
    }

    /** Значок в кнопке растёт вместе с кнопкой (иначе в большой кнопке остаётся крошечный значок). */
    private static void fitIcon(ImageButton b, int w, int h, float s) {
        Drawable d = b.getDrawable();
        if (d == null) {
            return;
        }
        int iw = Math.round(d.getIntrinsicWidth() * s);
        int ih = Math.round(d.getIntrinsicHeight() * s);
        if (iw <= 0 || ih <= 0) {
            return;
        }
        int px = Math.max(0, (w - iw) / 2);
        int py = Math.max(0, (h - ih) / 2);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(px, py, px, py);
    }
}
