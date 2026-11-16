package org.mpgram.droid;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Реакции под сообщением: капсулы переносятся на следующую строку, когда не помещаются, так что
 * видны все реакции, а не только первые. Время сообщения (если задано setTime) стоит последним:
 * справа на последней строке, а если там нет места, на отдельной строке справа. Так время всегда
 * внизу пузыря.
 */
public class ReactFlow extends ViewGroup {

    private final int gap;
    private View time;

    // Раскладка, посчитанная при измерении "по содержимому"; при растягивании до ширины пузыря
    // (EXACTLY) строки не пересчитываются, иначе высота разошлась бы с уже измеренной
    private int[] left = new int[16];
    private int[] top = new int[16];
    private int count;
    private int timeRowRight;   // время прижато вправо в своей строке
    private boolean valid;

    public ReactFlow(Context context) {
        super(context);
        gap = Ui.px(context, 4);
    }

    public ReactFlow(Context context, AttributeSet attrs) {
        super(context, attrs);
        gap = Ui.px(context, 4);
    }

    public void setTime(View t) {
        time = t;
        addView(t);
    }

    /** Состав или тексты капсул изменились: раскладку надо посчитать заново. */
    public void invalidateRows() {
        valid = false;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int mode = MeasureSpec.getMode(widthSpec);
        int size = MeasureSpec.getSize(widthSpec);
        int n = getChildCount();
        int limit = mode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE
                : size - getPaddingLeft() - getPaddingRight();
        int free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        int atMost = limit == Integer.MAX_VALUE ? free : MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST);
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            if (c.getVisibility() != GONE) {
                c.measure(atMost, free);
            }
        }
        if (mode == MeasureSpec.EXACTLY && valid && count == n) {
            int h = 0;
            for (int i = 0; i < n; i++) {
                View c = getChildAt(i);
                if (c.getVisibility() != GONE) {
                    h = Math.max(h, top[i] + c.getMeasuredHeight());
                }
            }
            setMeasuredDimension(size, h + getPaddingTop() + getPaddingBottom());
            return;
        }
        if (left.length < n) {
            left = new int[n];
            top = new int[n];
        }
        count = n;
        int x = 0;
        int y = 0;
        int rowH = 0;
        int widest = 0;
        timeRowRight = -1;
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) {
                continue;
            }
            int w = c.getMeasuredWidth();
            int h = c.getMeasuredHeight();
            boolean isTime = c == time;
            if (x > 0 && x + w > limit) {
                y += rowH + gap;
                x = 0;
                rowH = 0;
            }
            left[i] = x;
            top[i] = y;
            x += w + (isTime ? 0 : gap);
            rowH = Math.max(rowH, h);
            widest = Math.max(widest, x - (isTime ? 0 : gap));
            if (isTime) {
                timeRowRight = i;
            }
        }
        // Время ниже капсул: выравниваем по низу строки, если рядом с ним стоят капсулы
        valid = true;
        int w = mode == MeasureSpec.EXACTLY ? size : widest + getPaddingLeft() + getPaddingRight();
        if (mode == MeasureSpec.AT_MOST && w > size) {
            w = size;
        }
        setMeasuredDimension(w, y + rowH + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int n = getChildCount();
        int right = r - l - getPaddingRight();
        int bottomLimit = b - t - getPaddingBottom();
        for (int i = 0; i < n && i < count; i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) {
                continue;
            }
            int cl = getPaddingLeft() + left[i];
            int ct = getPaddingTop() + top[i];
            if (c == time) {
                cl = right - c.getMeasuredWidth();   // время всегда у правого края
                // По низу строки: рядом с капсулами время стоит на одной базовой линии с ними
                ct = Math.max(ct, bottomLimit - c.getMeasuredHeight());
                if (cl < getPaddingLeft()) {
                    cl = getPaddingLeft();
                }
            }
            c.layout(cl, ct, cl + c.getMeasuredWidth(), ct + c.getMeasuredHeight());
        }
    }
}
