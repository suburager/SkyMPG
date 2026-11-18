package org.mpgram.droid;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Экран «О приложении»: иконка, версия, описание и благодарности с текстами лицензий. */
public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.apply(this);
        setTitle(R.string.about_title);
        setContentView(R.layout.about);
        setupHeader(false);

        // Иконка приложения из APK (если своей нет, система отдаёт стандартную иконку пакета)
        ((ImageView) findViewById(R.id.imgIcon)).setImageDrawable(
                getPackageManager().getApplicationIcon(AppInfo.applicationInfo(this)));

        // "Версия" жирным белым, номер версии цветом акцента (как "Вы:" в списке чатов)
        String ver = AppInfo.versionName(this);
        String verLine = getString(R.string.about_version, ver);
        SpannableString verSp = new SpannableString(verLine);
        int vp = ver.length() > 0 ? verLine.lastIndexOf(ver) : -1;
        if (vp > 0) {
            verSp.setSpan(new StyleSpan(Typeface.BOLD), 0, vp, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            verSp.setSpan(new ForegroundColorSpan(ChatItem.ACCENT), vp, vp + ver.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        ((TextView) findViewById(R.id.textVersion)).setText(verSp);

        // В описании жирным белым только слово SkyMPG, остальное обычным белым
        String descText = getString(R.string.about_description);
        SpannableString descSp = new SpannableString(descText);
        int dp0 = descText.indexOf("SkyMPG");
        if (dp0 >= 0) {
            descSp.setSpan(new StyleSpan(Typeface.BOLD), dp0, dp0 + "SkyMPG".length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        ((TextView) findViewById(R.id.textDescription)).setText(descSp);

        // Сердечко рисуется картинкой из набора эмодзи: системного шрифта на Android 2.x для него нет
        TextView madeBy = (TextView) findViewById(R.id.textMadeBy);
        String madeText = getString(R.string.about_made_by);
        EmojiMap.setText(madeBy, madeText);
        int mp = madeText.indexOf("suburager");
        if (mp >= 0) {
            // Копия с теми же спанами (картинка сердечка), поверх цвет для ника
            SpannableStringBuilder madeSp = new SpannableStringBuilder(madeBy.getText());
            madeSp.setSpan(new ForegroundColorSpan(ChatItem.ACCENT), mp, mp + "suburager".length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            madeBy.setText(madeSp);
        }

        LinearLayout credits = (LinearLayout) findViewById(R.id.credits);
        addCredit(credits, R.string.about_credits_mpgram, "https://github.com/shinovon/mpgram-web",
                "AGPL-3.0", "licenses/agpl-3.0.txt");
        addCredit(credits, R.string.about_credits_madeline, "https://github.com/danog/MadelineProto",
                "AGPL-3.0", "licenses/agpl-3.0.txt");
        addCredit(credits, R.string.about_app_license, null,
                "GPL-3.0", "licenses/gpl-3.0.txt");
    }

    /** Строка благодарности: описание, ссылка на проект и «кнопка» с лицензией, открывающая её текст. */
    private void addCredit(LinearLayout parent, int descRes, final String url,
                           final String licenseName, final String asset) {
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (6 * dp);

        TextView desc = new TextView(this);
        desc.setText(creditText(getString(descRes)));
        desc.setTextColor(0xFFFFFFFF);
        desc.setTextSize(14);
        desc.setPadding(0, pad, 0, 0);
        parent.addView(desc);

        if (url != null) {
            TextView link = new TextView(this);
            link.setText(url);
            link.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
            link.setTextSize(12);
            link.setTextColor(ChatItem.ACCENT);
            parent.addView(link);
        }

        TextView license = new TextView(this);
        license.setText(getString(R.string.about_license) + ": " + licenseName + " \u203A");
        license.setTextSize(12);
        license.setTextColor(ChatItem.ACCENT);
        license.setPadding(0, pad, 0, pad * 2);
        license.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showLicense(licenseName, asset);
            }
        });
        parent.addView(license);
    }

    /**
     * Название проекта выделяется жирным: это часть до первого двоеточия («MPGram Web от shinovon:»);
     * если двоеточия нет (строка про сам SkyMPG), жирным становится слово SkyMPG.
     */
    private CharSequence creditText(String text) {
        int end = text.indexOf(':');
        int begin = 0;
        if (end < 0) {
            begin = text.indexOf("SkyMPG");
            end = begin < 0 ? -1 : begin + "SkyMPG".length();
        }
        if (begin < 0 || end <= begin) {
            return text;
        }
        SpannableString sp = new SpannableString(text);
        sp.setSpan(new StyleSpan(Typeface.BOLD), begin, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    /** Текст лицензии открывается отдельным экраном со списком абзацев, а не одним огромным TextView. */
    private void showLicense(String title, String asset) {
        Intent i = new Intent(this, LicenseActivity.class);
        i.putExtra(LicenseActivity.EXTRA_TITLE, title);
        i.putExtra(LicenseActivity.EXTRA_ASSET, asset);
        startActivity(i);
    }
}
