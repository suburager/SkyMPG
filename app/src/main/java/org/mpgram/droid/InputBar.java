package org.mpgram.droid;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * Строка ввода: кнопки (эмодзи, вложение, отправка) квадратные и той же высоты, что однострочное
 * поле. Высота поля не зависит от размеров кнопок, поэтому размер берём из уже разложенного поля
 * в слушателе раскладки, без измерения внутри onMeasure: так кнопки не расходятся с полем
 * ни на одной плотности. Когда текста больше одной строки, кнопки сохраняют прежний размер.
 */
public class InputBar extends LinearLayout {

    private static final int[] BUTTON_IDS = {R.id.btnEmoji, R.id.btnAttach, R.id.btnSend};

    private EditText edit;
    private ImageButton[] buttons;
    private final int minUnit;
    private int unit;

    private final ViewTreeObserver.OnGlobalLayoutListener syncer = new ViewTreeObserver.OnGlobalLayoutListener() {
        public void onGlobalLayout() {
            syncButtons();
        }
    };

    public InputBar(Context context) {
        super(context);
        minUnit = Ui.px(context, 40);
    }

    public InputBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        minUnit = Ui.px(context, 40);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        edit = (EditText) findViewById(R.id.editMessage);
        buttons = new ImageButton[BUTTON_IDS.length];
        for (int i = 0; i < BUTTON_IDS.length; i++) {
            buttons[i] = (ImageButton) findViewById(BUTTON_IDS[i]);
            buttons[i].setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        edit.setMinHeight(minUnit);
        applyUnit(minUnit);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalLayoutListener(syncer);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeGlobalOnLayoutListener(syncer);
        super.onDetachedFromWindow();
    }

    /** Поле уже разложено: берём его однострочную высоту и подгоняем кнопки под неё. */
    private void syncButtons() {
        if (edit == null || edit.getLineCount() > 1) {
            return;
        }
        int h = edit.getHeight();
        if (h <= 0) {
            return;
        }
        int u = Math.max(minUnit, h);
        // Ui.scaleContent на больших экранах умножает размеры кнопок уже после нас: сверяем с реальными
        boolean off = buttons[0].getLayoutParams().width != u || buttons[0].getLayoutParams().height != u;
        if (u != unit || off) {
            applyUnit(u);
            requestLayout();
        }
    }

    private void applyUnit(int u) {
        int icon = Math.round(u * 0.62f);
        int pad = Math.max(0, (u - icon) / 2);
        for (int i = 0; i < buttons.length; i++) {
            ViewGroup.LayoutParams lp = buttons[i].getLayoutParams();
            lp.width = u;
            lp.height = u;
            buttons[i].setLayoutParams(lp);
            buttons[i].setPadding(pad, pad, pad, pad);
        }
        unit = u;
    }
}
