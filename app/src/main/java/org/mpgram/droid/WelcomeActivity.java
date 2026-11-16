package org.mpgram.droid;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

/** Стартовый экран: иконка и название берутся из самого APK, кнопка ведёт ко входу. */
public class WelcomeActivity extends BaseActivity {

    private String appliedLang;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.apply(this);
        appliedLang = Lang.get(this);

        if (Api.load(this).isLoggedIn()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.welcome);
        setupHeader(true);

        PackageManager pm = getPackageManager();
        ApplicationInfo ai = getApplicationInfo();
        ((ImageView) findViewById(R.id.imgIcon)).setImageDrawable(pm.getApplicationIcon(ai));
        ((TextView) findViewById(R.id.textName)).setText(pm.getApplicationLabel(ai));

        ((Button) findViewById(R.id.btnStart)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(WelcomeActivity.this, LoginActivity.class));
                finish();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return AppMenu.create(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return AppMenu.select(this, item) || super.onOptionsItemSelected(item);
    }

    // Если язык сменили в настройках, пересоздаём экран с новой локалью
    @Override
    protected void onResume() {
        super.onResume();
        if (appliedLang != null && !appliedLang.equals(Lang.get(this))) {
            Lang.restart(this);
        }
    }
}
