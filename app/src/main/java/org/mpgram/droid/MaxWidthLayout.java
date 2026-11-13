package org.mpgram.droid;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * Колонка ограниченной ширины: на больших экранах формы входа не растягиваются на весь экран.
 * Пределом служит 480dp с учётом множителя интерфейса; на телефонах ограничение не действует.
 */
public class MaxWidthLayout extends LinearLayout {

    private final int maxWidth;

    public MaxWidthLayout(Context context) {
        super(context);
        maxWidth = Ui.px(context, 480);
    }

    public MaxWidthLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        maxWidth = Ui.px(context, 480);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int size = MeasureSpec.getSize(widthMeasureSpec);
        if (mode != MeasureSpec.UNSPECIFIED && size > maxWidth) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth,
                    mode == MeasureSpec.EXACTLY ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
