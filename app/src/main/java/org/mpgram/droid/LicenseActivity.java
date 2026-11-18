package org.mpgram.droid;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;

/**
 * Текст лицензии. Файл читается и режется на абзацы в фоне, а на экране это обычный список:
 * строки создаются только для видимых абзацев и переиспользуются. Один TextView на 35 КБ текста
 * (около 600 строк) так не работает: его вёрстка блокирует главный поток, а на новых системах
 * такой высокий вид не помещается в аппаратную текстуру.
 */
public class LicenseActivity extends BaseActivity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ASSET = "asset";

    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.apply(this);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        final String asset = getIntent().getStringExtra(EXTRA_ASSET);
        setTitle(title == null ? getString(R.string.about_license) : title);
        setContentView(R.layout.license);
        setupHeader(false);

        final TextView info = (TextView) findViewById(R.id.textInfo);
        final ListView list = (ListView) findViewById(R.id.listLicense);

        new Thread(new Runnable() {
            public void run() {
                final ArrayList<String> parts = asset == null ? null : readParagraphs(asset);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        if (parts == null || parts.size() == 0) {
                            info.setText(R.string.err_unknown);
                            return;
                        }
                        info.setVisibility(View.GONE);
                        list.setAdapter(new ParagraphAdapter(parts));
                        list.setVisibility(View.VISIBLE);
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    /**
     * Абзацы разделены пустой строкой. Внутри абзаца строки, жёстко разбитые по ширине в файле,
     * склеиваются пробелом, чтобы текст переносился по ширине экрана; абзацы со списками
     * и заголовками (с отступами) сохраняют свои переводы строк.
     */
    private ArrayList<String> readParagraphs(String path) {
        ArrayList<String> out = new ArrayList<String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(getAssets().open(path), "UTF-8"), 8192);
            StringBuilder cur = new StringBuilder();
            boolean keepBreaks = false;
            boolean first = true;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().length() == 0) {
                    flush(out, cur);
                    keepBreaks = false;
                    first = true;
                    continue;
                }
                boolean indented = line.charAt(0) == ' ' || line.charAt(0) == '\t';
                if (!first && indented) {
                    keepBreaks = true;
                }
                if (!first) {
                    cur.append(keepBreaks ? '\n' : ' ');
                }
                cur.append(line.trim());
                first = false;
            }
            flush(out, cur);
        } catch (IOException e) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }
        return out;
    }

    private static void flush(ArrayList<String> out, StringBuilder cur) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    private class ParagraphAdapter extends ArrayAdapter<String> {

        ParagraphAdapter(ArrayList<String> parts) {
            super(LicenseActivity.this, 0, parts);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView v = (TextView) convertView;
            if (v == null) {
                v = (TextView) Ui.scaleTree(getLayoutInflater().inflate(R.layout.license_row, null));
            }
            v.setText(getItem(position));
            return v;
        }

        @Override
        public boolean isEnabled(int position) {
            return false; // строки только для чтения
        }
    }
}
