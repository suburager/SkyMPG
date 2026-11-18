package org.mpgram.droid;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Капсула с полукруглыми краями при любой высоте: радиус всегда равен половине высоты.
 * XML-shape с большим радиусом на старых Android растягивается в овал (радиус масштабируется
 * отдельно по ширине и высоте), поэтому форму рисуем сами.
 */
class PillDrawable extends Drawable {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float half;

    PillDrawable(int fillColor, int strokeColor, float strokePx) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(fillColor);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(strokeColor);
        stroke.setStrokeWidth(strokePx);
        half = strokePx / 2f;
    }

    @Override
    public void draw(Canvas canvas) {
        rect.set(getBounds());
        rect.inset(half, half);
        float r = Math.min(rect.width(), rect.height()) / 2f;
        canvas.drawRoundRect(rect, r, r, fill);
        canvas.drawRoundRect(rect, r, r, stroke);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        stroke.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        stroke.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
