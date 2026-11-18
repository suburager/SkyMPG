package org.mpgram.droid;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** Настройки приложения: язык, ПИН-код на вход и очистка кэша. Новые пункты добавляются сюда. */
public class SettingsActivity extends BaseActivity {

    private TextView textPinState;
    private Button btnPin;
    private Button btnClearCache;

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

        btnClearCache = (Button) findViewById(R.id.btnClearCache);
        btnClearCache.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                clearCache();
            }
        });
    }

    /** Размер кэша на кнопке; файлы считаются в фоне: на большом кэше обход диска заметен. */
    private void refreshCacheSize() {
        btnClearCache.setText(getString(R.string.btn_clear_cache, "\u2026"));
        new Thread(new Runnable() {
            public void run() {
                final String size = CacheStore.format(SettingsActivity.this, CacheStore.size(SettingsActivity.this));
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!isFinishing()) {
                            btnClearCache.setText(getString(R.string.btn_clear_cache, size));
                            btnClearCache.setEnabled(true);
                        }
                    }
                });
            }
        }).start();
    }

    private void clearCache() {
        btnClearCache.setEnabled(false);
        new Thread(new Runnable() {
            public void run() {
                CacheStore.clear(SettingsActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        Toast.makeText(SettingsActivity.this, R.string.cache_cleared, Toast.LENGTH_SHORT).show();
                        refreshCacheSize();
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = Pin.isEnabled(this);
        textPinState.setText(on ? R.string.pin_state_on : R.string.pin_state_off);
        btnPin.setText(on ? R.string.btn_pin_disable : R.string.btn_pin_set);
        refreshCacheSize();
    }
}
