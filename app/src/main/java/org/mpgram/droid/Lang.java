package org.mpgram.droid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Locale;

/**
 * Выбор языка интерфейса: "" (авто, как в системе), "en", "ru" или "uk".
 * Android сам подбирает values/, values-ru/ или values-uk/ по системной локали,
 * здесь только ручное переопределение. Работает на API 3 (Configuration.locale).
 */
public class Lang {

    public static final String AUTO = "";
    public static final String EN = "en";
    public static final String RU = "ru";
    public static final String UK = "uk";

    private static final String PREFS = "mpgram";

    public static String get(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lang", AUTO);
    }

    public static void set(Context ctx, String lang) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString("lang", lang);
        Api.apply(e);
    }

    /** Вызывать в начале onCreate каждой Activity, до setContentView и getString. */
    public static void apply(Context ctx) {
        String lang = get(ctx);
        Resources res = ctx.getResources();
        Configuration conf = new Configuration(res.getConfiguration());
        if (lang.length() == 0) {
            conf.locale = Locale.getDefault(); // локаль системы
        } else {
            conf.locale = new Locale(lang);
        }
        res.updateConfiguration(conf, res.getDisplayMetrics());
    }

    /** Пересоздаёт Activity, чтобы подхватить новую локаль. */
    public static void restart(Activity a) {
        Intent i = a.getIntent();
        a.finish();
        a.startActivity(i);
    }

    /** Переключатель языка: Авто / English / Русский / Українська. Смена перезапускает текущую Activity. */
    public static void setupSpinner(final Activity a, Spinner sp) {
        final String[] values = new String[] { AUTO, EN, RU, UK };
        // Слева от названия флаг (картинкой из набора эмодзи: системного шрифта на старых Android для флагов нет)
        String[] labels = new String[] {
                "\uD83C\uDF10 " + a.getString(R.string.lang_auto),             // глобус
                "\uD83C\uDDEC\uD83C\uDDE7 English",                          // флаг Великобритании
                "\uD83C\uDDF7\uD83C\uDDFA \u0420\u0443\u0441\u0441\u043A\u0438\u0439",     // флаг России
                "\uD83C\uDDFA\uD83C\uDDE6 \u0423\u043A\u0440\u0430\u0457\u043D\u0441\u044C\u043A\u0430" // флаг Украины
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(a,
                android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return flagged(super.getView(position, convertView, parent), position);
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return flagged(super.getDropDownView(position, convertView, parent), position);
            }

            private View flagged(View v, int position) {
                if (v instanceof TextView) {
                    EmojiMap.setText((TextView) v, getItem(position));
                }
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);

        String cur = get(a);
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(cur)) {
                sel = i;
            }
        }
        sp.setSelection(sel);

        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String chosen = values[position];
                if (!chosen.equals(get(a))) {
                    set(a, chosen);
                    restart(a);
                }
            }

            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }
}
