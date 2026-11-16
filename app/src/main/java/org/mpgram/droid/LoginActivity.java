package org.mpgram.droid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Вход: по номеру телефона (капча, код, облачный пароль) или по QR-коду.
 * Весь процесс в одной Activity, шаги переключаются панелями.
 */
public class LoginActivity extends BaseActivity {

    private interface Task {
        void run() throws Exception;
    }

    private String appliedLang;
    private Api api;
    private boolean busy;
    private volatile boolean qrPolling;
    private volatile boolean destroyed;

    private String mode = "phone"; // "phone" или "qr"
    private String phone = "";
    private String captchaId = "";
    private String phoneCodeHash = "";
    private volatile String lastQrText = "";
    private int qrSize = 240;
    private int shortSide = 320;

    private View panelMain, panelPhone, panelCaptcha, panelCode, panelPassword, panelQr;
    private View currentPanel;
    private TextView textStatus, textTitle;
    private EditText editInstance, editInstancePassword, editPhone;
    private Spinner spinInstances;
    private ArrayAdapter<String> instanceAdapter;
    private ArrayList<String> instanceLabels;
    private int[] instanceColors;
    private String[] instancePing;
    private boolean[] hostHttps;
    private EditText editCaptcha, editCode, editPassword;
    private ImageView imgCaptcha, imgQr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.apply(this);
        appliedLang = Lang.get(this);

        api = Api.load(this);
        if (api.isLoggedIn()) {
            startMain();
            return;
        }

        setContentView(R.layout.login);
        setupHeader(true);

        panelMain = findViewById(R.id.panelMain);
        panelPhone = findViewById(R.id.panelPhone);
        panelCaptcha = findViewById(R.id.panelCaptcha);
        panelCode = findViewById(R.id.panelCode);
        panelPassword = findViewById(R.id.panelPassword);
        panelQr = findViewById(R.id.panelQr);
        textStatus = (TextView) findViewById(R.id.textStatus);
        textTitle = (TextView) findViewById(R.id.textTitle);
        editInstance = (EditText) findViewById(R.id.editInstance);
        editInstancePassword = (EditText) findViewById(R.id.editInstancePassword);
        editPhone = (EditText) findViewById(R.id.editPhone);
        editCaptcha = (EditText) findViewById(R.id.editCaptcha);
        editCode = (EditText) findViewById(R.id.editCode);
        editPassword = (EditText) findViewById(R.id.editPassword);
        imgCaptcha = (ImageView) findViewById(R.id.imgCaptcha);
        imgQr = (ImageView) findViewById(R.id.imgQr);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        // Сторона QR и ширина капчи: три четверти меньшей стороны экрана, но не шире экрана с полями
        shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        qrSize = Math.min(shortSide * 3 / 4, shortSide - Ui.px(this, 24));

        setupInstances();
        editInstancePassword.setText(api.getInstancePassword());

        ((Button) findViewById(R.id.btnPhone)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setStatus("", false);
                showPanel(panelPhone);
                editPhone.requestFocus();
            }
        });
        ((Button) findViewById(R.id.btnPhoneGo)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideKeyboard();
                onPhoneClicked();
            }
        });
        ((Button) findViewById(R.id.btnQr)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onQrClicked();
            }
        });
        ((Button) findViewById(R.id.btnCaptcha)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideKeyboard();
                onCaptchaClicked();
            }
        });
        ((Button) findViewById(R.id.btnCode)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideKeyboard();
                onCodeClicked();
            }
        });
        ((Button) findViewById(R.id.btnResend)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onResendClicked();
            }
        });
        ((TextView) findViewById(R.id.textCodeToQr)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Код не пришёл: переходим на вход по QR с тем же инстансом
                mode = "qr";
                callInitQr(null, null);
            }
        });
        ((Button) findViewById(R.id.btnPassword)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideKeyboard();
                onPasswordClicked();
            }
        });
        ((Button) findViewById(R.id.btnQrCancel)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopQr();
                showPanel(panelMain);
            }
        });


        showPanel(panelMain);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        qrPolling = false;
        super.onDestroy();
    }

    // На API 3 нет Activity.onBackPressed() (появился в API 5), поэтому onKeyDown
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && currentPanel != null && currentPanel != panelMain) {
            stopQr();
            showPanel(panelMain);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && currentPanel == panelMain) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------- Обработчики кнопок ----------

    private void onPhoneClicked() {
        if (busy || !applyInstance()) {
            return;
        }
        String p = editPhone.getText().toString().trim();
        if (p.length() == 0) {
            setStatus(getString(R.string.err_no_phone), true);
            return;
        }
        mode = "phone";
        phone = p;
        callPhoneLogin(null, null);
    }

    private void onQrClicked() {
        if (busy || !applyInstance()) {
            return;
        }
        mode = "qr";
        callInitQr(null, null);
    }

    private void onCaptchaClicked() {
        String key = editCaptcha.getText().toString().trim();
        if ("phone".equals(mode)) {
            callPhoneLogin(captchaId, key);
        } else {
            callInitQr(captchaId, key);
        }
    }

    private void onCodeClicked() {
        final String code = editCode.getText().toString().trim();
        background(new Task() {
            public void run() throws Exception {
                handleAuth(api.call("completePhoneLogin", "code", code));
            }
        });
    }

    private void onResendClicked() {
        background(new Task() {
            public void run() throws Exception {
                api.call("resendCode", "phone", phone, "hash", phoneCodeHash);
                runOnUiThread(new Runnable() {
                    public void run() {
                        setStatus(getString(R.string.code_resent), false);
                    }
                });
            }
        });
    }

    private void onPasswordClicked() {
        final String pw = editPassword.getText().toString();
        background(new Task() {
            public void run() throws Exception {
                handleAuth(api.call("complete2faLogin", "password", pw));
            }
        });
    }

    private boolean applyInstance() {
        int i = spinInstances.getSelectedItemPosition();
        if (i == Instances.HOSTS.length) {
            api.setBaseUrl(editInstance.getText().toString());
        } else if (i >= 0 && i < Instances.HOSTS.length) {
            api.setBaseUrl(Instances.urlFor(Instances.HOSTS[i], hostHttps[i]));
        } else {
            api.setBaseUrl("");
        }
        api.setInstancePassword(editInstancePassword.getText().toString());
        if (api.getBaseUrl().length() == 0) {
            setStatus(getString(R.string.err_no_instance), true);
            return false;
        }
        return true;
    }

    // ---------- Выбор инстанса и пинг ----------

    private void setupInstances() {
        spinInstances = (Spinner) findViewById(R.id.spinInstances);
        final int n = Instances.HOSTS.length;
        hostHttps = new boolean[n];
        instanceColors = new int[n + 1];
        instancePing = new String[n + 1];
        instanceLabels = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            instanceLabels.add(Instances.HOSTS[i]);
            instancePing[i] = "";
            instanceColors[i] = getResources().getColor(R.color.ping_pending);
        }
        instanceLabels.add(getString(R.string.instance_custom));
        instancePing[n] = "";

        // Адрес чёрным, задержка своим цветом (и в закрытом списке, и в раскрытом)
        instanceAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, instanceLabels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                styleInstanceRow((TextView) v, position);
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                styleInstanceRow((TextView) v, position);
                return v;
            }
        };
        instanceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinInstances.setAdapter(instanceAdapter);

        // Что было выбрано при прошлом входе
        String saved = api.getBaseUrl();
        String savedHost = saved;
        if (savedHost.startsWith("http://")) {
            savedHost = savedHost.substring(7);
        } else if (savedHost.startsWith("https://")) {
            savedHost = savedHost.substring(8);
        }
        if (savedHost.endsWith("/")) {
            savedHost = savedHost.substring(0, savedHost.length() - 1);
        }
        int sel = 0;
        boolean custom = false;
        boolean found = false;
        for (int i = 0; i < n; i++) {
            if (Instances.HOSTS[i].equals(savedHost)) {
                sel = i;
                found = true;
            }
        }
        editInstance.setText("http://");
        if (!found && saved.length() > 0) {
            custom = true;
            sel = n;
            editInstance.setText(saved);
        }
        spinInstances.setSelection(sel);
        editInstance.setVisibility(custom ? View.VISIBLE : View.GONE);

        spinInstances.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                editInstance.setVisibility(position == n ? View.VISIBLE : View.GONE);
            }

            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        ((TextView) findViewById(R.id.textRecheck)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startPing();
            }
        });

        startPing();
    }

    private void startPing() {
        for (int i = 0; i < Instances.HOSTS.length; i++) {
            final int idx = i;
            setInstancePing(i, "\u2026", getResources().getColor(R.color.ping_pending));
            new Thread(new Runnable() {
                public void run() {
                    final Ping.Result r = Ping.run(Instances.HOSTS[idx]);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            hostHttps[idx] = r.https;
                            showPing(idx, r.ms, r.https);
                        }
                    });
                }
            }).start();
        }
    }

    private void showPing(int i, int ms, boolean https) {
        if (ms < 0) {
            setInstancePing(i, getString(R.string.ping_offline),
                    getResources().getColor(R.color.ping_bad));
        } else {
            int color = getResources().getColor(
                    ms < 300 ? R.color.ping_good : (ms < 1000 ? R.color.ping_mid : R.color.ping_slow));
            setInstancePing(i, ms + " " + getString(R.string.ping_ms)
                    + (https ? "   HTTPS" : ""), color);
        }
    }

    private void setInstancePing(int i, String text, int color) {
        instancePing[i] = text;
        instanceColors[i] = color;
        instanceAdapter.notifyDataSetChanged();
    }

    private void styleInstanceRow(TextView tv, int position) {
        String host = instanceLabels.get(position);
        String ping = instancePing[position];
        tv.setTextColor(getResources().getColor(R.color.instance_text));
        if (ping.length() == 0) {
            tv.setText(host);
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(host + "   " + ping);
        sb.setSpan(new ForegroundColorSpan(instanceColors[position]),
                host.length() + 3, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(sb);
    }

    // ---------- Сетевые шаги ----------

    private void callPhoneLogin(final String cid, final String ckey) {
        background(new Task() {
            public void run() throws Exception {
                handleAuth(api.call("phoneLogin",
                        "phone", phone,
                        "captcha_id", cid,
                        "captcha_key", ckey));
            }
        });
    }

    private void callInitQr(final String cid, final String ckey) {
        background(new Task() {
            public void run() throws Exception {
                handleAuth(api.call("initLogin",
                        "qr", "1",
                        "captcha_id", cid,
                        "captcha_key", ckey));
            }
        });
    }

    /** Разбор любого ответа авторизации (см. "Authorization response" в API.md). Вызывается из фонового потока. */
    private void handleAuth(JSONObject o) throws Exception {
        String u = Api.str(o, "user");
        if (u.length() > 0) {
            api.setUser(u);
        }
        final String res = Api.str(o, "res");
        final String message = Api.str(o, "message");

        if ("1".equals(res)) {
            qrPolling = false;
            runOnUiThread(new Runnable() {
                public void run() {
                    api.save(LoginActivity.this);
                    startMain();
                }
            });

        } else if ("need_captcha".equals(res)) {
            final String id = Api.str(o, "captcha_id");
            final Bitmap bmp = fitCaptcha(decode(api.fetch("api.php?v=" + Api.VERSION
                    + "&method=getCaptchaImg&captcha_id=" + Api.enc(id))));
            runOnUiThread(new Runnable() {
                public void run() {
                    captchaId = id;
                    showImage(imgCaptcha, bmp);
                    editCaptcha.setText("");
                    setStatus("", false);
                    showPanel(panelCaptcha);
                }
            });

        } else if ("code_sent".equals(res)) {
            final String hash = Api.str(o, "phone_code_hash");
            runOnUiThread(new Runnable() {
                public void run() {
                    phoneCodeHash = hash;
                    editCode.setText("");
                    setStatus("", false);
                    showPanel(panelCode);
                }
            });

        } else if ("qr".equals(res)) {
            showQr(Api.str(o, "text"));

        } else if ("password".equals(res)) {
            qrPolling = false;
            runOnUiThread(new Runnable() {
                public void run() {
                    editPassword.setText("");
                    setStatus("", false);
                    showPanel(panelPassword);
                }
            });

        } else if ("phone_code_invalid".equals(res) || "phone_code_expired".equals(res)) {
            final String hash = Api.str(o, "phone_code_hash");
            final String text = getString("phone_code_invalid".equals(res)
                    ? R.string.err_code_invalid : R.string.err_code_expired);
            runOnUiThread(new Runnable() {
                public void run() {
                    if (hash.length() > 0) {
                        phoneCodeHash = hash;
                    }
                    editCode.setText("");
                    setStatus(text, true);
                    showPanel(panelCode);
                }
            });

        } else if ("auth_restart".equals(res)) {
            failToMain(getString(R.string.err_auth_restart));
        } else if ("phone_number_invalid".equals(res)) {
            runOnUiThread(new Runnable() {
                public void run() {
                    setStatus(getString(R.string.err_phone_invalid), true);
                    showPanel(panelPhone);
                }
            });
        } else if ("no_password".equals(res)) {
            failToMain(getString(R.string.err_no_password));
        } else if ("need_signup".equals(res)) {
            failToMain(getString(R.string.err_need_signup));
        } else if ("exception".equals(res)) {
            failToMain(getString(R.string.err_server)
                    + (message.length() > 0 ? ": " + message : ""));
        } else {
            failToMain(getString(R.string.err_unknown_res) + ": " + res);
        }
    }

    private void failToMain(final String text) {
        qrPolling = false;
        runOnUiThread(new Runnable() {
            public void run() {
                setStatus(text, true);
                showPanel(panelMain);
            }
        });
    }

    // ---------- QR ----------

    /** Из фонового потока: обновляет картинку QR (если ссылка сменилась) и запускает опрос. */
    private void showQr(String text) throws Exception {
        if (text.length() == 0) {
            throw new Api.ApiException(getString(R.string.err_unknown_res));
        }
        if (!text.equals(lastQrText)) {
            final Bitmap bmp = fitQr(decode(api.fetch("qrcode.php?t=" + Api.enc(text)
                    + "&tw=" + qrSize + "&th=" + qrSize)));
            lastQrText = text;
            runOnUiThread(new Runnable() {
                public void run() {
                    showImage(imgQr, bmp);
                }
            });
        }
        runOnUiThread(new Runnable() {
            public void run() {
                setStatus("", false);
                showPanel(panelQr);
            }
        });
        startQrPolling();
    }

    private void startQrPolling() {
        if (qrPolling) {
            return;
        }
        qrPolling = true;
        new Thread(new Runnable() {
            public void run() {
                while (qrPolling && !destroyed) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!qrPolling || destroyed) {
                        return;
                    }
                    try {
                        JSONObject o = api.call("qrLogin");
                        if (!qrPolling) {
                            return;
                        }
                        handleAuth(o);
                    } catch (final Exception e) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (qrPolling) {
                                    showError(e);
                                }
                            }
                        });
                    }
                }
            }
        }).start();
    }

    private void stopQr() {
        qrPolling = false;
        lastQrText = "";
    }

    // ---------- UI-хелперы ----------

    private void background(final Task task) {
        if (busy) {
            return;
        }
        busy = true;
        setStatus(getString(R.string.wait), false);
        new Thread(new Runnable() {
            public void run() {
                Exception err = null;
                try {
                    task.run();
                } catch (Exception e) {
                    err = e;
                }
                final Exception fe = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        if (fe != null) {
                            showError(fe);
                        }
                    }
                });
            }
        }).start();
    }

    private void showPanel(View p) {
        panelMain.setVisibility(View.GONE);
        panelPhone.setVisibility(View.GONE);
        panelCaptcha.setVisibility(View.GONE);
        panelCode.setVisibility(View.GONE);
        panelPassword.setVisibility(View.GONE);
        panelQr.setVisibility(View.GONE);
        p.setVisibility(View.VISIBLE);
        currentPanel = p;
        // Кнопки "Продолжить" живут внизу экрана вне прокрутки: показываем ту, что относится к шагу
        findViewById(R.id.btnPhoneGo).setVisibility(p == panelPhone ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnCaptcha).setVisibility(p == panelCaptcha ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnCode).setVisibility(p == panelCode ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnPassword).setVisibility(p == panelPassword ? View.VISIBLE : View.GONE);
        textTitle.setText(p == panelCaptcha ? R.string.captcha_title : R.string.login_title);
    }

    private void setStatus(String text, boolean error) {
        textStatus.setTextColor(error ? 0xFFFF8080 : 0xFFAAAAAA);
        textStatus.setText(text);
        // Пустая строка статуса не должна занимать место и растягивать отступ под заголовком
        textStatus.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** После ввода номера, капчи, кода или пароля клавиатура больше не нужна. */
    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) {
            return;
        }
        View f = getCurrentFocus();
        View anchor = f != null ? f : findViewById(R.id.barGo);
        imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
    }

    private void showError(Exception e) {
        String m;
        if (e instanceof Api.ApiException) {
            m = translate(e.getMessage());
        } else if (e instanceof IOException) {
            m = getString(R.string.err_network) + ": " + e;
        } else {
            m = String.valueOf(e);
        }
        setStatus(m, true);
    }

    private String translate(String m) {
        if (m == null || m.length() == 0) {
            return getString(R.string.err_unknown);
        }
        if (m.equals("Instance password is required")) {
            return getString(R.string.err_ipw_required);
        }
        if (m.equals("Wrong instance password")) {
            return getString(R.string.err_ipw_wrong);
        }
        if (m.equals("API is disabled")) {
            return getString(R.string.err_api_disabled);
        }
        if (m.equals("Login API is disabled")) {
            return getString(R.string.err_login_disabled);
        }
        if (m.equals("Unsupported API version")) {
            return getString(R.string.err_version);
        }
        return m;
    }

    /**
     * Капча крупнее на больших экранах: ширина до трёх четвертей меньшей стороны экрана
     * (но не меньше исходной и не шире экрана с полями), пропорции сохраняются.
     */
    private Bitmap fitCaptcha(Bitmap b) {
        int inner = shortSide - Ui.px(this, 24);
        int target = Math.min(inner, Math.max(b.getWidth(), shortSide * 3 / 4));
        return resize(b, target, true);
    }

    /**
     * QR растёт до qrSize целым числом пикселей на модуль: края остаются чёткими, код читается.
     * Если картинка больше доступного места, она уменьшается с пересчётом.
     */
    private Bitmap fitQr(Bitmap b) {
        int w = b.getWidth();
        if (w >= qrSize) {
            return w == qrSize ? b : resize(b, qrSize, true);
        }
        int k = Math.max(1, qrSize / w);
        return resize(b, w * k, false);
    }

    /** Новая ширина w с теми же пропорциями; smooth=false: без сглаживания (QR). */
    private Bitmap resize(Bitmap b, int w, boolean smooth) {
        int bw = b.getWidth();
        int bh = b.getHeight();
        if (w <= 0 || bw <= 0 || bw == w) {
            return b;
        }
        int h = Math.max(1, Math.round(bh * (float) w / bw));
        try {
            return Bitmap.createScaledBitmap(b, w, h, smooth);
        } catch (OutOfMemoryError e) {
            return b;
        }
    }

    /** Показывает картинку ровно того размера, что она есть, по центру (а не по "родному" размеру ImageView). */
    private void showImage(ImageView iv, Bitmap b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                b.getWidth() + iv.getPaddingLeft() + iv.getPaddingRight(),
                b.getHeight() + iv.getPaddingTop() + iv.getPaddingBottom());
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        iv.setImageBitmap(b);
    }

    private Bitmap decode(byte[] data) throws Api.ApiException {
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (b == null) {
            throw new Api.ApiException(getString(R.string.err_bad_image));
        }
        return b;
    }

    private void startMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
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
