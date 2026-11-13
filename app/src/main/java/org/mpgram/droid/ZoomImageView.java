package org.mpgram.droid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ImageView;

/**
 * Картинка с приближением. Щипок двумя пальцами (Android 2.0+), двойное касание, а также
 * zoomBy() для кнопок "+" и "-", которые работают и на устройствах без мультитача.
 * Одним пальцем картинку можно двигать, когда она больше экрана.
 */
public class ZoomImageView extends ImageView {

    private static final float MAX_ZOOM = 8f;       // во сколько раз можно увеличить сверх "вписано"
    private static final long DOUBLE_TAP_MS = 300;

    private final Matrix matrix = new Matrix();
    private final float[] v = new float[9];
    private final boolean multi = Api.sdkLevel() >= 5; // MotionEvent.getPointerCount() с API 5

    private Bitmap bitmap;
    private float fit = 1f;

    private int mode; // 0 ничего, 1 перетаскивание, 2 щипок
    private float lastX;
    private float lastY;
    private float downX;
    private float downY;
    private boolean moved;
    private float pinchStartDist;
    private float pinchStartScale;
    private long lastTapTime;
    private float lastTapX;
    private float lastTapY;
    private int slop;

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        slop = (int) (8 * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        bitmap = bm;
        reset();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        reset();
    }

    /** Вписывает картинку целиком в экран и ставит по центру. */
    private void reset() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        fit = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        matrix.reset();
        matrix.postScale(fit, fit);
        applyBounds();
    }

    private float currentScale() {
        matrix.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    /** Не даёт увести картинку за края; если она меньше экрана, держит по центру. */
    private void applyBounds() {
        if (bitmap == null) {
            return;
        }
        matrix.getValues(v);
        float scale = v[Matrix.MSCALE_X];
        float w = bitmap.getWidth() * scale;
        float h = bitmap.getHeight() * scale;
        float tx = v[Matrix.MTRANS_X];
        float ty = v[Matrix.MTRANS_Y];
        float dx = 0;
        float dy = 0;
        if (w <= getWidth()) {
            dx = (getWidth() - w) / 2 - tx;
        } else if (tx > 0) {
            dx = -tx;
        } else if (tx + w < getWidth()) {
            dx = getWidth() - (tx + w);
        }
        if (h <= getHeight()) {
            dy = (getHeight() - h) / 2 - ty;
        } else if (ty > 0) {
            dy = -ty;
        } else if (ty + h < getHeight()) {
            dy = getHeight() - (ty + h);
        }
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    /** Масштаб в долях "вписано" вокруг точки (px, py). */
    private void zoomTo(float target, float px, float py) {
        float min = fit;
        float max = fit * MAX_ZOOM;
        float t = Math.max(min, Math.min(max, target));
        float f = t / currentScale();
        matrix.postScale(f, f, px, py);
        applyBounds();
    }

    /** Для кнопок: factor 1.5 приближает, 1/1.5 отдаляет, вокруг центра экрана. */
    public void zoomBy(float factor) {
        if (bitmap == null) {
            return;
        }
        zoomTo(currentScale() * factor, getWidth() / 2f, getHeight() / 2f);
    }

    private static float dist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (bitmap == null) {
            return true;
        }
        int action = ev.getAction() & 0xFF;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mode = 1;
                lastX = downX = ev.getX();
                lastY = downY = ev.getY();
                moved = false;
                break;
            case 5: // ACTION_POINTER_DOWN
                if (multi && ev.getPointerCount() >= 2) {
                    mode = 2;
                    pinchStartDist = dist(ev);
                    pinchStartScale = currentScale();
                    moved = true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode == 2 && multi && ev.getPointerCount() >= 2) {
                    float d = dist(ev);
                    if (pinchStartDist > 10) {
                        zoomTo(pinchStartScale * d / pinchStartDist,
                                (ev.getX(0) + ev.getX(1)) / 2, (ev.getY(0) + ev.getY(1)) / 2);
                    }
                } else if (mode == 1) {
                    float x = ev.getX();
                    float y = ev.getY();
                    if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) {
                        moved = true;
                    }
                    if (moved) {
                        matrix.postTranslate(x - lastX, y - lastY);
                        applyBounds();
                    }
                    lastX = x;
                    lastY = y;
                }
                break;
            case 6: // ACTION_POINTER_UP
                mode = 0; // после щипка палец не тащит картинку, пока не коснутся заново
                break;
            case MotionEvent.ACTION_UP:
                if (mode == 1 && !moved) {
                    onTap(ev.getX(), ev.getY());
                }
                mode = 0;
                break;
            case MotionEvent.ACTION_CANCEL:
                mode = 0;
                break;
            default:
                break;
        }
        return true;
    }

    /** Двойное касание: приблизить вдвое-втрое или вернуть "вписано". */
    private void onTap(float x, float y) {
        long now = System.currentTimeMillis();
        boolean dbl = now - lastTapTime < DOUBLE_TAP_MS
                && Math.abs(x - lastTapX) < slop * 4 && Math.abs(y - lastTapY) < slop * 4;
        if (dbl) {
            lastTapTime = 0;
            if (currentScale() > fit * 1.05f) {
                zoomTo(fit, x, y);
            } else {
                zoomTo(fit * 3f, x, y);
            }
        } else {
            lastTapTime = now;
            lastTapX = x;
            lastTapY = y;
        }
    }
}
