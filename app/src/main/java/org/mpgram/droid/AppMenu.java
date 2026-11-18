package org.mpgram.droid;

import android.app.Activity;
import android.content.Intent;
import android.view.Menu;
import android.view.MenuItem;

/** Меню, которое открывается физической кнопкой Menu: Настройки и О приложении. */
public class AppMenu {

    private static final int ID_SETTINGS = 1;
    private static final int ID_ABOUT = 2;

    public static boolean create(Menu menu) {
        menu.add(0, ID_SETTINGS, 0, R.string.settings)
                .setIcon(android.R.drawable.ic_menu_preferences);
        menu.add(0, ID_ABOUT, 1, R.string.about)
                .setIcon(android.R.drawable.ic_menu_info_details);
        return true;
    }

    /** Возвращает true, если пункт обработан. */
    public static boolean select(Activity a, MenuItem item) {
        switch (item.getItemId()) {
            case ID_SETTINGS:
                a.startActivity(new Intent(a, SettingsActivity.class));
                return true;
            case ID_ABOUT:
                a.startActivity(new Intent(a, AboutActivity.class));
                return true;
            default:
                return false;
        }
    }
}
