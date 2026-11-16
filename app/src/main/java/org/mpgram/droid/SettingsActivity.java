package org.mpgram.droid;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

/** Настройки приложения: язык и ПИН-код на вход. Новые пункты добавляются сюда. */
public class SettingsActivity extends BaseActivity {

    private TextView textPinState;
    private Button btnPin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.apply(this);
        setTitle(R.string.settings);
        setContentView(R.layout.settings);
        setupHeader(false);

        Lang.setupSpinner(this, (Spinner) findViewById(R.id.spinLang));

        textPinState = (TextView) findViewById(R.id.textPinState);
        btnPin = (Button) findViewById(R.id.btnPin);
        btnPin.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Включить: ввод и повтор нового кода. Отключить: нужно ввести текущий код
                Intent i = new Intent(SettingsActivity.this, PinActivity.class);
                i.putExtra(PinActivity.EXTRA_MODE,
                        Pin.isEnabled(SettingsActivity.this) ? PinActivity.MODE_DISABLE : PinActivity.MODE_SET);
                startActivity(i);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = Pin.isEnabled(this);
        textPinState.setText(on ? R.string.pin_state_on : R.string.pin_state_off);
        btnPin.setText(on ? R.string.btn_pin_disable : R.string.btn_pin_set);
    }
}
