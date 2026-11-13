package org.mpgram.droid;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;

/**
 * Просмотр фото на весь экран: имя файла в шапке, приближение щипком, двойным касанием и кнопками
 * "+" и "-", кнопка скачивания оригинала. Картинка берётся уменьшенной до размера экрана
 * (file.php, p=rview), чтобы старым устройствам хватало памяти.
 */
public class ViewerActivity extends BaseActivity {

    public static final String EXTRA_PEER = "peer";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_SIZE = "size";

    private static final int MAX_SIDE = 1280;

    private Api api;
    private String peer;
    private int msgId;
    private String name;
    private long size;
    private ZoomImageView image;
    private Bitmap shown;
    private TextView textStatus;
    private final Handler handler = new Handler();
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.apply(this);
        api = Api.load(this);
        peer = getIntent().getStringExtra(EXTRA_PEER);
        msgId = getIntent().getIntExtra(EXTRA_ID, 0);
        name = getIntent().getStringExtra(EXTRA_NAME);
        size = getIntent().getLongExtra(EXTRA_SIZE, 0);
        if (!api.isLoggedIn() || peer == null || msgId <= 0) {
            finish();
            return;
        }
        if (name == null || name.length() == 0) {
            name = getString(R.string.preview_photo);
        }
        setTitle(name);
        setContentView(R.layout.viewer);
        setupHeader(false);

        image = (ZoomImageView) findViewById(R.id.zoomImage);
        textStatus = (TextView) findViewById(R.id.textStatus);
        findViewById(R.id.btnZoomIn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                image.zoomBy(1.5f);
            }
        });
        findViewById(R.id.btnZoomOut).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                image.zoomBy(1f / 1.5f);
            }
        });
        findViewById(R.id.btnDownload).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Downloader.start(ViewerActivity.this, api, peer, msgId, name, size);
            }
        });
        load();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        // Большая картинка: на Android 2.x её пиксели занимают бюджет памяти до сборки мусора
        if (image != null) {
            image.setImageBitmap(null);
        }
        if (shown != null) {
            shown.recycle();
            shown = null;
        }
        super.onDestroy();
    }

    private void load() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        final int side = Math.max(320, Math.min(MAX_SIDE, Math.max(w, h)));
        textStatus.setText(R.string.viewer_loading);
        textStatus.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            public void run() {
                Bitmap bmp = null;
                try {
                    byte[] data = api.fetch("file.php?c=" + Api.enc(peer) + "&m=" + msgId
                            + "&p=rview&tw=" + side + "&th=" + side);
                    bmp = decode(data);
                } catch (Throwable e) {
                    bmp = null;
                }
                final Bitmap result = bmp;
                handler.post(new Runnable() {
                    public void run() {
                        if (destroyed) {
                            if (result != null) {
                                result.recycle(); // экран уже закрыт, пиксели не нужны
                            }
                            return;
                        }
                        if (result == null) {
                            textStatus.setText(R.string.viewer_error);
                            return;
                        }
                        textStatus.setVisibility(View.GONE);
                        shown = result;
                        image.setImageBitmap(result);
                    }
                });
            }
        }).start();
    }

    /** RGB_565 вдвое экономнее по памяти; при нехватке памяти повторяем с уменьшением вдвое. */
    private static Bitmap decode(byte[] data) {
        for (int sample = 1; sample <= 4; sample *= 2) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inSampleSize = sample;
                return BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } catch (OutOfMemoryError e) {
                // пробуем меньший размер
            }
        }
        return null;
    }
}
