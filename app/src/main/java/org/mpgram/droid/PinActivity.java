package org.mpgram.droid;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Ввод ПИН-кода (4-8 цифр) своей цифровой клавиатурой: вход в приложение, установка нового кода
 * (ввод и повтор) и отключение (нужно ввести текущий код).
 */
public class PinActivity extends BaseActivity {

    public static final String EXTRA_MODE = "mode";
    public static final int MODE_UNLOCK = 0;
    public static final int MODE_SET = 1;
    public static final int MODE_DISABLE = 2;

    private static final int MAX_TRIES = 5;
    private static final int LOCK_SECONDS = 30;

    private int mode;
    private final StringBuffer entered = new StringBuffer();
    private String firstEntry;   // при установке: первый ввод, ждём повтор
    private TextView textPrompt;
    private TextView textDots;
    private TextView textError;

    @Override
    protected boolean lockable() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.apply(this);
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_UNLOCK);
        setTitle(R.string.pin_title);
        setContentView(R.layout.pin);
        setupHeader(false);

        textPrompt = (TextView) findViewById(R.id.pinPrompt);
        textDots = (TextView) findViewById(R.id.pinDots);
        textError = (TextView) findViewById(R.id.pinError);
        buildKeypad((LinearLayout) findViewById(R.id.pinKeypad));
        showPrompt();
        refresh();
    }

    private void showPrompt() {
        if (mode == MODE_SET) {
            textPrompt.setText(firstEntry == null ? R.string.pin_new : R.string.pin_repeat);
        } else if (mode == MODE_DISABLE) {
            textPrompt.setText(R.string.pin_confirm_off);
        } else {
            textPrompt.setText(R.string.pin_enter);
        }
    }

    private int dp(int v) {
        return Ui.px(this, v);
    }

    private void buildKeypad(LinearLayout pad) {
        String[][] rows = {
                {"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"<", "0", "OK"}
        };
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final String key = rows[r][c];
                Button b = new Button(this);
                b.setText("<".equals(key) ? "\u232B" : ("OK".equals(key) ? "\u2713" : key));
                b.setTextSize(22 * Ui.scale(this));
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        onKey(key);
                    }
                });
                row.addView(b, new LinearLayout.LayoutParams(0, dp(58), 1f));
            }
            pad.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void onKey(String key) {
        if (blocked()) {
            return;
        }
        textError.setText("");
        if ("<".equals(key)) {
            if (entered.length() > 0) {
                entered.setLength(entered.length() - 1);
            }
        } else if ("OK".equals(key)) {
            submit();
            return;
        } else if (entered.length() < Pin.MAX) {
            entered.append(key);
        }
        refresh();
    }

    private void refresh() {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < entered.length(); i++) {
            sb.append("\u25CF ");
        }
        textDots.setText(sb.length() == 0 ? " " : sb.toString().trim());
    }

    private boolean blocked() {
        long left = Pin.blockedUntil(this) - System.currentTimeMillis();
        if (left > 0) {
            textError.setText(getString(R.string.pin_blocked, (int) ((left + 999) / 1000)));
            return true;
        }
        return false;
    }

    private void submit() {
        String pin = entered.toString();
        if (pin.length() < Pin.MIN) {
            textError.setText(R.string.pin_short);
            return;
        }
        if (mode == MODE_SET) {
            if (firstEntry == null) {
                firstEntry = pin;
                entered.setLength(0);
                showPrompt();
                refresh();
            } else if (firstEntry.equals(pin)) {
                Pin.set(this, pin);
                Pin.unlocked = true;
                Toast.makeText(this, R.string.pin_set_done, Toast.LENGTH_SHORT).show();
                finish();
            } else {
                firstEntry = null;
                entered.setLength(0);
                showPrompt();
                refresh();
                textError.setText(R.string.pin_mismatch);
            }
            return;
        }
        if (Pin.check(this, pin)) {
            Pin.resetFailures(this);
            Pin.unlocked = true;
            if (mode == MODE_DISABLE) {
                Pin.clear(this);
                Toast.makeText(this, R.string.pin_off_done, Toast.LENGTH_SHORT).show();
            }
            finish();
            return;
        }
        entered.setLength(0);
        refresh();
        if (Pin.registerFailure(this, MAX_TRIES, LOCK_SECONDS * 1000L)) {
            blocked();
        } else {
            textError.setText(R.string.pin_wrong);
        }
    }

    /** Назад: при входе в приложение уходим на рабочий стол, а не в закрытый экран. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && mode == MODE_UNLOCK) {
            moveTaskToBack(true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
