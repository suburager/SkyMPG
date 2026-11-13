package org.mpgram.droid;

import android.app.AlertDialog;
import android.content.DialogInterface;

/**
 * Предупреждение при запуске на Android 5.0 и выше: клиент рассчитан только на Android 1.5-4.4.
 * Показывается один раз за запуск процесса и требует подтверждения; "Выйти" закрывает экран.
 */
public class Disclaimer {

    private static boolean accepted;
    private static boolean showing;

    /** Нужно ли показать предупреждение прямо сейчас. */
    public static boolean needed() {
        return Api.sdkLevel() >= 21 && !accepted;
    }

    public static void show(final BaseActivity act) {
        if (showing) {
            return;
        }
        showing = true;
        new AlertDialog.Builder(act)
                .setTitle(R.string.warn_title)
                .setMessage(R.string.warn_android5)
                .setCancelable(false)
                .setPositiveButton(R.string.warn_continue, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        accepted = true;
                        showing = false;
                        act.recheckLock(); // теперь можно проверить ПИН-код и показать экран
                    }
                })
                .setNegativeButton(R.string.warn_exit, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        showing = false;
                        act.finish();
                    }
                })
                .show();
    }
}
