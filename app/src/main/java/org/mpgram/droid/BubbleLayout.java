package org.mpgram.droid;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Пузырь сообщения: ширина по самому широкому содержимому. Дети с тегом "fill" (цитата, строка
 * текста со временем) потом растягиваются на всю ширину пузыря.
 *
 * Обычный fill_parent у детей здесь не годится: LinearLayout с wrap_content при этом
 * вычисляет ширину только по остальным детям (например, по короткому имени автора), и текст
 * сжимается в узкую высокую колонку.
 */
public class BubbleLayout extends LinearLayout {

    // Предел ширины пузыря: на больших экранах строки не должны тянуться через весь экран
    private final int maxWidth;

    public BubbleLayout(Context context) {
        super(context);
        maxWidth = Ui.px(context, 400);
    }

    public BubbleLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        maxWidth = Ui.px(context, 400);
    }

    // Фон-9-patch не должен задавать минимальный размер пузыря: иначе короткое сообщение
    // растягивается до размера самой картинки (пузырь "раздувается" вокруг пары букв).
    @Override
    protected int getSuggestedMinimumWidth() {
        return 0;
    }

    @Override
    protected int getSuggestedMinimumHeight() {
        return 0;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST
                && MeasureSpec.getSize(widthMeasureSpec) > maxWidth) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            return;
        }
        int inner = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            // Цитата получает в tag сообщение (для нажатия), поэтому узнаём её по id
            boolean fill = "fill".equals(c.getTag()) || c.getId() == R.id.textQuote;
            if (c.getVisibility() == GONE || !fill) {
                continue;
            }
            LayoutParams lp = (LayoutParams) c.getLayoutParams();
            int w = inner - lp.leftMargin - lp.rightMargin;
            if (w > c.getMeasuredWidth()) {
                // Высота не меняется: текст внутри уже уложен по своей ширине
                c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(c.getMeasuredHeight(), MeasureSpec.EXACTLY));
            }
        }
    }
}
