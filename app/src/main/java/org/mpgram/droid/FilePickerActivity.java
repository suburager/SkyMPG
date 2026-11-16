package org.mpgram.droid;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Простенький проводник: папки и файлы файловой системы, вверх через "..", выбор файла по
 * касанию. Результат: RESULT_OK и Uri выбранного файла (file://).
 */
public class FilePickerActivity extends BaseActivity {

    /** Последняя открытая папка, чтобы не начинать каждый раз с корня. */
    private static File lastDir;

    private static class Entry {
        File file;
        boolean up;    // строка ".."
        boolean dir;   // папка (определено один раз при чтении, а не при каждой прорисовке)
        long size;     // размер файла
    }

    private File dir;
    private int loadGen;      // номер последней начатой загрузки папки: опоздавшие ответы отбрасываются
    private boolean destroyed;
    private final ArrayList<Entry> entries = new ArrayList<Entry>();
    private ListView list;
    private TextView textEmpty;
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.apply(this);
        setTitle(R.string.files_title);
        setContentView(R.layout.filepicker);
        setupHeader(false);

        list = (ListView) findViewById(R.id.listFiles);
        textEmpty = (TextView) findViewById(R.id.textEmpty);
        adapter = new FileAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Entry e = entries.get(position);
                if (e.up) {
                    open(dir.getParentFile(), false);
                } else if (e.dir) {
                    open(e.file, false);
                } else {
                    Intent r = new Intent();
                    r.setData(Uri.fromFile(e.file));
                    setResult(RESULT_OK, r);
                    finish();
                }
            }
        });

        File start = lastDir;
        if (start == null || !start.isDirectory()) {
            start = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                    ? Environment.getExternalStorageDirectory() : new File("/");
        }
        open(start, true);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    /**
     * Открывает папку. Чтение, проверка "папка или файл" и сортировка идут в фоновом потоке:
     * на медленной карте памяти и в больших папках это занимает секунды, и UI-поток не должен ждать.
     * Если папку прочитать нельзя, остаёмся где были (при fallbackToRoot открываем корень).
     */
    private void open(final File d, final boolean fallbackToRoot) {
        final int gen = ++loadGen;
        new Thread(new Runnable() {
            public void run() {
                File[] files = d == null ? null : d.listFiles();
                final ArrayList<Entry> result = new ArrayList<Entry>();
                final boolean readable = files != null;
                if (readable) {
                    for (int i = 0; i < files.length; i++) {
                        Entry e = new Entry();
                        e.file = files[i];
                        e.dir = files[i].isDirectory();
                        e.size = e.dir ? 0 : files[i].length();
                        result.add(e);
                    }
                    Collections.sort(result, new Comparator<Entry>() {
                        public int compare(Entry a, Entry b) {
                            if (a.dir != b.dir) {
                                return a.dir ? -1 : 1; // папки выше файлов
                            }
                            return a.file.getName().toLowerCase().compareTo(b.file.getName().toLowerCase());
                        }
                    });
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (destroyed || gen != loadGen) {
                            return;
                        }
                        if (!readable) {
                            Toast.makeText(FilePickerActivity.this, R.string.files_unreadable,
                                    Toast.LENGTH_SHORT).show();
                            if (fallbackToRoot) {
                                open(new File("/"), false);
                            }
                            return;
                        }
                        dir = d;
                        lastDir = d;
                        setTitle(d.getPath());
                        entries.clear();
                        if (d.getParentFile() != null) {
                            Entry up = new Entry();
                            up.up = true;
                            entries.add(up);
                        }
                        entries.addAll(result);
                        adapter.notifyDataSetChanged();
                        list.setSelection(0);
                        textEmpty.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }).start();
    }

    /** Назад поднимает на папку выше; на самом верху закрывает выбор. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && dir != null && dir.getParentFile() != null
                && dir.getParentFile().canRead()) {
            open(dir.getParentFile(), false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return (bytes * 10 / (1024L * 1024L) / 10.0) + " " + getString(R.string.unit_mb);
        }
        return Math.max(1, bytes / 1024) + " " + getString(R.string.unit_kb);
    }

    private class FileAdapter extends BaseAdapter {
        private final LayoutInflater inflater = getLayoutInflater();

        public int getCount() {
            return entries.size();
        }

        public Object getItem(int position) {
            return entries.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView : Ui.scaleTree(inflater.inflate(R.layout.file_row, null));
            ImageView icon = (ImageView) v.findViewById(R.id.fileIcon);
            TextView name = (TextView) v.findViewById(R.id.fileName);
            TextView info = (TextView) v.findViewById(R.id.fileInfo);
            Entry e = entries.get(position);
            if (e.up) {
                icon.setImageResource(R.drawable.ic_files_w);
                name.setText("..");
                info.setText("");
            } else if (e.dir) {
                icon.setImageResource(R.drawable.ic_files_w);
                name.setText(e.file.getName());
                info.setText("");
            } else {
                icon.setImageResource(R.drawable.ic_attach_w);
                name.setText(e.file.getName());
                info.setText(formatSize(e.size));
            }
            return v;
        }
    }
}
