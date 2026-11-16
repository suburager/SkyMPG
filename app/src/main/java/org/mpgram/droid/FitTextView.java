package org.mpgram.droid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.style.ReplacementSpan;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * Текст, который не растягивается шире самой длинной своей строки. Обычный TextView с
 * wrap_content, когда текст переносится на несколько строк, занимает всю доступную ширину, и
 * справа от коротких строк остаётся пустое место (пузырь сообщения получается шире текста).
 * Здесь после разметки ширина урезается до самой длинной строки.
 *
 * Результат запоминается: пузырь измеряет детей по нескольку раз за проход, и без этого текст
 * каждый раз укладывался бы заново, а это самое дорогое в прокрутке на слабом процессоре.
 */
public class FitTextView extends TextView {

    // Результат последнего измерения и то, при чём он верен
    private CharSequence memoText;
    private float memoTextSize;
    private int memoAvail;   // доступная ширина, при которой текст уложен
    private int memoWidth;   // ширина после урезания (0: результата нет)
    private int memoHeight;

    public FitTextView(Context context) {
        super(context);
    }

    public FitTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FitTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        boolean atMost = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST
                && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY;
        int size = MeasureSpec.getSize(widthMeasureSpec);
        // Если текст тот же и доступная ширина между "узкой" и "той, при которой укладывали",
        // строки разобьются так же, и укладывать заново не нужно
        if (atMost && memoWidth > 0 && size >= memoWidth && size <= memoAvail
                && getText() == memoText && getTextSize() == memoTextSize && getLayout() != null) {
            setMeasuredDimension(memoWidth, memoHeight);
            return;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        memoWidth = 0;
        if (!atMost) {
            return;
        }
        Layout layout = getLayout();
        if (layout == null) {
            return;
        }
        if (layout.getLineCount() >= 2) {
            float widest = 0;
            for (int i = 0; i < layout.getLineCount(); i++) {
                widest = Math.max(widest, layout.getLineWidth(i));
            }
            int w = (int) Math.ceil(widest) + getCompoundPaddingLeft() + getCompoundPaddingRight();
            if (w < getMeasuredWidth()) {
                setMeasuredDimension(w, getMeasuredHeight());
            }
        }
        memoText = getText();
        memoTextSize = getTextSize();
        memoAvail = size;
        memoWidth = getMeasuredWidth();
        memoHeight = getMeasuredHeight();
    }

    /**
     * Невидимая вставка в конце текста шириной w: оставляет в последней строке место для
     * времени сообщения, которое рисуется поверх в правом нижнем углу. Если места не хватает,
     * вставка уходит на новую строку, и под текстом появляется строка для времени.
     */
    public static class Gap extends ReplacementSpan {
        private final int width;

        public Gap(int width) {
            this.width = width;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return width;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                         int bottom, Paint paint) {
            // Ничего не рисуем
        }
    }
}
