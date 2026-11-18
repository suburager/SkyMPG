package org.mpgram.droid;

import android.app.AlertDialog;
import android.content.DialogInterface;

/**
 * Предупреждение при запуске на Android 5.0 и выше: клиент рассчитан только на Android 1.5-4.4.
 * Показывается один раз за всё время: после "Продолжить" выбор запоминается; "Выйти" закрывает экран.
 */
public class Disclaimer {

    private static final String PREFS = "mpgram";
    private static final String KEY = "android5_warning_ok";

    private static boolean accepted;
    private static boolean showing;

    /** Нужно ли показать предупреждение прямо сейчас. */
    public static boolean needed(android.content.Context ctx) {
        if (Api.sdkLevel() < 21 || accepted) {
            return false;
        }
        if (ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY, false)) {
            accepted = true;
            return false;
        }
        return true;
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
                        android.content.SharedPreferences.Editor e = act
                                .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit();
                        e.putBoolean(KEY, true);
                        Api.apply(e);
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
