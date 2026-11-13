package org.mpgram.droid;

import android.app.Activity;
import android.content.Intent;
import org.json.JSONObject;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Общая шапка для всех версий Android: системный заголовок (на 3.0+ ActionBar) отключён темой,
 * вместо него в каждый экран подключён layout/header с названием и кнопкой меню.
 * Заодно здесь работает блокировка ПИН-кодом: пока код не введён, содержимое экрана скрыто
 * и поверх открывается PinActivity.
 */
public class BaseActivity extends Activity {

    /** Если приложение было в фоне дольше этого, код нужен снова (меньшее время: поворот экрана). */
    private static final long GRACE_MS = 2000;

    private static int started;      // сколько экранов приложения сейчас видимы
    private static long leftAt;      // когда приложение ушло в фон

    private TextView headerTitle;
    private TextView headerSubtitle;

    // Шапка чата: аватарка слева, название и число участников по центру, подпись "печатает"
    private AvatarLoader headerAvatars;
    private ImageView headerAvatarView;
    private String hdrPeer = "";
    private String hdrTitle = "";
    private boolean hdrForum;
    private boolean hdrHasPhoto;
    private CharSequence hdrCountText;
    private CharSequence hdrTyping;
    private volatile boolean hdrDestroyed;

    /** После разметки экран подгоняется под размер дисплея: на больших экранах всё крупнее. */
    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        Ui.scaleContent(this);
    }

    /** Экран, который сам занимается ПИН-кодом (PinActivity), переопределяет на false. */
    protected boolean lockable() {
        return true;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EmojiMap.attach(this); // картинки эмодзи нужны на любом экране
    }

    /**
     * Включает вид шапки для экранов чата: аватарка слева, название и число участников или
     * подписчиков по центру. Число и фото берутся с сервера (getPeer и getPeerInfo). Касание
     * аватарки или названия открывает информацию о чате.
     */
    protected void initChatHeader(final Api api, final String peerId, String title, boolean forum) {
        hdrPeer = peerId;
        hdrTitle = title == null ? "" : title;
        hdrForum = forum;
        headerAvatarView = (ImageView) findViewById(R.id.headerAvatar);
        TextView t = (TextView) findViewById(R.id.headerTitle);
        if (t != null) {
            t.setGravity(Gravity.CENTER_HORIZONTAL);
        }
        if (headerSubtitle != null) {
            headerSubtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        }
        View.OnClickListener open = new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(BaseActivity.this, ChatInfoActivity.class);
                i.putExtra(ChatInfoActivity.EXTRA_ID, hdrPeer);
                i.putExtra(ChatInfoActivity.EXTRA_TITLE, hdrTitle);
                i.putExtra(ChatInfoActivity.EXTRA_FORUM, hdrForum);
                startActivity(i);
            }
        };
        View box = findViewById(R.id.headerTitleBox);
        if (box != null) {
            box.setOnClickListener(open);
        }
        if (headerAvatarView != null) {
            headerAvatarView.setVisibility(View.VISIBLE);
            headerAvatarView.setOnClickListener(open);
            headerAvatars = new AvatarLoader(this, api, new AvatarLoader.Listener() {
                public boolean isWanted(String id) {
                    return !hdrDestroyed;
                }

                public void onAvatarLoaded(String id) {
                    if (!hdrDestroyed) {
                        showHeaderAvatar();
                    }
                }
            }, 36);
            showHeaderAvatar();
        }
        // Фото и число участников приходят с сервера; до этого видна буква-заглушка
        new Thread(new Runnable() {
            public void run() {
                boolean photo = false;
                boolean channel = false;
                int count = 0;
                try {
                    JSONObject p = api.call("getPeer", "id", peerId);
                    photo = p.optBoolean("p", false);
                    channel = p.optBoolean("c", false);
                } catch (Exception e) {
                    // Остаётся заглушка
                }
                if (peerId.startsWith("-")) {
                    try {
                        count = api.call("getPeerInfo", "id", peerId).optInt("count", 0);
                    } catch (Exception e) {
                        // Без числа остаётся одно название
                    }
                }
                final boolean fPhoto = photo;
                final boolean fChannel = channel;
                final int fCount = count;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (hdrDestroyed) {
                            return;
                        }
                        hdrHasPhoto = fPhoto;
                        showHeaderAvatar();
                        if (fCount > 0) {
                            hdrCountText = fChannel
                                    ? Plural.format(BaseActivity.this, fCount, R.string.subscribers_one,
                                            R.string.subscribers_few, R.string.subscribers_many)
                                    : Plural.format(BaseActivity.this, fCount, R.string.members_one,
                                            R.string.members_few, R.string.members_many);
                            refreshSubtitle();
                        }
                    }
                });
            }
        }).start();
    }

    private void showHeaderAvatar() {
        if (headerAvatars != null && headerAvatarView != null) {
            headerAvatars.load(headerAvatarView, hdrPeer, hdrTitle, hdrHasPhoto, hdrForum);
        }
    }

    /** Подпись "печатает…" вместо числа участников; null возвращает число. */
    protected void setHeaderTyping(CharSequence text) {
        hdrTyping = text;
        refreshSubtitle();
    }

    private void refreshSubtitle() {
        if (hdrTyping != null) {
            setHeaderSubtitle(hdrTyping);
            if (headerSubtitle != null) {
                headerSubtitle.setTextColor(0xFF33B5E5);
            }
        } else {
            setHeaderSubtitle(hdrCountText);
            if (headerSubtitle != null) {
                headerSubtitle.setTextColor(0xFFB5C7E0);
            }
        }
    }

    @Override
    protected void onDestroy() {
        hdrDestroyed = true;
        if (headerAvatars != null) {
            headerAvatars.shutdown();
        }
        // Передача файла не должна держать закрытый экран: отменяем её и отпускаем вьюхи
        TransferDialog.releaseFor(this);
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (started == 0 && leftAt > 0 && System.currentTimeMillis() - leftAt > GRACE_MS) {
            Pin.unlocked = false;
        }
        started++;
    }

    @Override
    protected void onStop() {
        super.onStop();
        started--;
        if (started <= 0) {
            started = 0;
            leftAt = System.currentTimeMillis();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        recheckLock();
    }

    /**
     * Предупреждение для Android 5.0+ (если ещё не подтверждено), затем проверка ПИН-кода.
     * Вызывается из onResume и после подтверждения предупреждения.
     */
    public void recheckLock() {
        View root = getWindow().getDecorView();
        if (Disclaimer.needed()) {
            // Под предупреждением содержимое не показываем, если оно закрыто ПИН-кодом
            root.setVisibility(Pin.isEnabled(this) ? View.INVISIBLE : View.VISIBLE);
            Disclaimer.show(this);
            return;
        }
        if (lockable() && !Pin.unlocked && Pin.isEnabled(this)) {
            root.setVisibility(View.INVISIBLE);
            Intent i = new Intent(this, PinActivity.class);
            i.putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_UNLOCK);
            startActivity(i);
        } else {
            root.setVisibility(View.VISIBLE);
        }
    }

    /** Вызывать сразу после setContentView. withMenu: нужна ли кнопка меню на этом экране. */
    protected void setupHeader(boolean withMenu) {
        headerTitle = (TextView) findViewById(R.id.headerTitle);
        headerSubtitle = (TextView) findViewById(R.id.headerSubtitle);
        if (headerTitle != null) {
            EmojiMap.setText(headerTitle, getTitle());
        }
        View btn = findViewById(R.id.headerMenu);
        if (btn == null) {
            return;
        }
        if (withMenu) {
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openOptionsMenu();
                }
            });
        } else {
            btn.setVisibility(View.GONE);
        }
    }

    /** Мелкая строка под названием в шапке (число участников и т.п.); пустой текст её скрывает. */
    protected void setHeaderSubtitle(CharSequence text) {
        if (headerSubtitle == null) {
            return;
        }
        if (text == null || text.length() == 0) {
            headerSubtitle.setVisibility(View.GONE);
        } else {
            headerSubtitle.setText(text);
            headerSubtitle.setVisibility(View.VISIBLE);
        }
    }

    /** setTitle() должен обновлять и нашу шапку. */
    @Override
    protected void onTitleChanged(CharSequence title, int color) {
        super.onTitleChanged(title, color);
        if (headerTitle != null) {
            EmojiMap.setText(headerTitle, title);
        }
    }
}
