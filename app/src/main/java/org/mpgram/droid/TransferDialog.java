package org.mpgram.droid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.SystemClock;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Диалог скачивания и отправки: имя файла, полоса прогресса и под ней проценты слева и скорость
 * справа. Реализует Api.Progress, поэтому update() можно вызывать из рабочего потока.
 * Скорость сглаживается, чтобы цифра не прыгала.
 *
 * Экран хранится по слабой ссылке, а когда он закрывается, BaseActivity вызывает releaseFor():
 * передача отменяется и все ссылки на вьюхи сбрасываются, так что рабочий поток не удерживает
 * закрытый экран.
 */
public class TransferDialog implements Api.Progress {

    /** Открытые диалоги: чтобы закрыть и отменить их вместе с экраном. */
    private static final ArrayList<TransferDialog> active = new ArrayList<TransferDialog>();

    private final WeakReference<Activity> owner;
    private AlertDialog dialog;
    private TextView textName;
    private TextView textPercent;
    private TextView textSpeed;
    private ProgressBar bar;

    /** true, если пользователь нажал "Отмена", закрыл диалог или экран закрылся. */
    public volatile boolean cancelled;

    // Для отправки в фоновом сервисе: "Назад" только прячет диалог, а закрытие экрана не отменяет
    private boolean backHides;
    private boolean keepOnRelease;
    private Runnable onCancel;

    private long lastTime;
    private long lastBytes;
    private float speed;
    private int lastPercent = -1;
    private boolean speedDirty;

    public TransferDialog(Activity act, int titleRes) {
        owner = new WeakReference<Activity>(act);
        View v = Ui.scaleTree(act.getLayoutInflater().inflate(R.layout.transfer_dialog, null));
        textName = (TextView) v.findViewById(R.id.transferName);
        textPercent = (TextView) v.findViewById(R.id.transferPercent);
        textSpeed = (TextView) v.findViewById(R.id.transferSpeed);
        bar = (ProgressBar) v.findViewById(R.id.transferBar);
        dialog = new AlertDialog.Builder(act)
                .setTitle(titleRes)
                .setView(v)
                .setNegativeButton(R.string.btn_cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        cancelled = true;
                        if (onCancel != null) {
                            onCancel.run();
                        }
                    }
                })
                .create();
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) {
                if (backHides) {
                    synchronized (active) {
                        active.remove(TransferDialog.this);
                    }
                    return; // передача продолжается, видно её в уведомлении
                }
                cancelled = true;
                if (TransferDialog.this.onCancel != null) {
                    TransferDialog.this.onCancel.run();
                }
            }
        });
        synchronized (active) {
            active.add(this);
        }
    }

    /** Назад прячет диалог, не отменяя передачу. */
    public void setBackHides(boolean v) {
        backHides = v;
        Activity a = owner.get();
        if (v && dialog != null && a != null) {
            // Кнопка "В фон": прячет диалог, передача идёт дальше (видна в уведомлении)
            dialog.setButton(DialogInterface.BUTTON_NEUTRAL, a.getString(R.string.btn_background),
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            synchronized (active) {
                                active.remove(TransferDialog.this);
                            }
                        }
                    });
        }
    }

    /** Закрытие экрана не отменяет передачу (она идёт в сервисе). */
    public void setKeepOnRelease(boolean v) {
        keepOnRelease = v;
    }

    /** Что сделать при отмене пользователем (например, остановить сервис отправки). */
    public void setOnCancel(Runnable r) {
        onCancel = r;
    }

    /** Выполняет r в UI-потоке, если экран ещё жив. */
    public void post(Runnable r) {
        Activity a = owner.get();
        if (a != null) {
            a.runOnUiThread(r);
        }
    }

    public void show() {
        if (dialog != null) {
            dialog.show();
        }
    }

    /** Закрывает диалог (из UI-потока). */
    public void dismiss() {
        synchronized (active) {
            active.remove(this);
        }
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Exception e) {
                // Экран уже закрыт
            }
        }
    }

    /** Экран закрывается: отменяем передачу, закрываем диалог и отпускаем вьюхи. UI-поток. */
    public static void releaseFor(Activity act) {
        ArrayList<TransferDialog> mine = new ArrayList<TransferDialog>();
        synchronized (active) {
            for (int i = 0; i < active.size(); i++) {
                if (active.get(i).owner.get() == act) {
                    mine.add(active.get(i));
                }
            }
        }
        for (int i = 0; i < mine.size(); i++) {
            TransferDialog t = mine.get(i);
            if (!t.keepOnRelease) {
                t.cancelled = true;
            }
            t.dismiss();
            t.dialog = null;
            t.textName = null;
            t.textPercent = null;
            t.textSpeed = null;
            t.bar = null;
        }
    }

    /** Новый файл: сбрасывает скорость, показывает имя. Можно вызывать из любого потока. */
    public void begin(final String name, final boolean sizeKnown) {
        synchronized (this) {
            lastTime = 0;
            lastBytes = 0;
            speed = 0;
            lastPercent = -1;
            speedDirty = false;
        }
        post(new Runnable() {
            public void run() {
                if (bar == null) {
                    return;
                }
                textName.setText(name);
                bar.setIndeterminate(!sizeKnown);
                bar.setProgress(0);
                textPercent.setText("");
                textSpeed.setText("");
            }
        });
    }

    /** Возвращает false, если передачу нужно прервать. total <= 0: размер неизвестен. */
    public boolean update(long done, long total) {
        final int percent;
        final String speedText;
        synchronized (this) {
            long now = SystemClock.uptimeMillis();
            if (lastTime == 0) {
                lastTime = now;
                lastBytes = done;
            }
            long dt = now - lastTime;
            if (dt >= 500) {
                float inst = (done - lastBytes) * 1000f / dt;
                speed = speed == 0 ? inst : speed * 0.6f + inst * 0.4f;
                lastTime = now;
                lastBytes = done;
                speedDirty = true;
            }
            percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
            if (percent == lastPercent && !speedDirty) {
                return !cancelled;
            }
            lastPercent = percent;
            speedDirty = false;
            speedText = speed > 0 ? formatSpeed(speed) : "";
        }
        post(new Runnable() {
            public void run() {
                if (bar == null) {
                    return;
                }
                if (percent >= 0) {
                    bar.setProgress(percent);
                    textPercent.setText(percent + "%");
                }
                textSpeed.setText(speedText);
            }
        });
        return !cancelled;
    }

    private String formatSpeed(float bytesPerSec) {
        Activity a = owner.get();
        if (a == null) {
            return "";
        }
        String perSec = a.getString(R.string.unit_per_sec);
        if (bytesPerSec >= 1024f * 1024f) {
            return ((int) (bytesPerSec * 10 / (1024f * 1024f)) / 10.0) + " " + a.getString(R.string.unit_mb) + perSec;
        }
        return Math.max(1, (int) (bytesPerSec / 1024f)) + " " + a.getString(R.string.unit_kb) + perSec;
    }
}
