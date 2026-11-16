package org.mpgram.droid;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Информация о чате: по центру аватарка, под ней название и (для групп и каналов) число
 * участников или подписчиков, внизу username и ID. Касание username или ID копирует их.
 */
public class ChatInfoActivity extends BaseActivity {

    public static final String EXTRA_ID = "id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_FORUM = "forum";
    /** Открыто из чата по имени автора: для человека показываем кнопку "Написать". */
    public static final String EXTRA_WRITE = "write";

    private Api api;
    private AvatarLoader avatars;
    private String peerId;
    private String title = "";
    private boolean hasPhoto;
    private boolean forum;
    private volatile boolean destroyed;

    private ImageView avatar;
    private TextView textTitle;
    private TextView textCount;
    private TextView textUsername;
    private TextView textId;
    private View rowUsername;
    private View rowAbout;
    private View rowPhone;
    private TextView textAbout;
    private TextView textPhone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.apply(this);
        api = Api.load(this);
        peerId = getIntent().getStringExtra(EXTRA_ID);
        if (!api.isLoggedIn() || peerId == null || peerId.length() == 0) {
            finish();
            return;
        }
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        title = t == null ? "" : t;
        forum = getIntent().getBooleanExtra(EXTRA_FORUM, false);
        boolean canWrite = getIntent().getBooleanExtra(EXTRA_WRITE, false) && !peerId.startsWith("-");

        setTitle(R.string.chat_info);
        setContentView(R.layout.chat_info);
        setupHeader(false);

        avatar = (ImageView) findViewById(R.id.infoAvatar);
        textTitle = (TextView) findViewById(R.id.infoTitle);
        textCount = (TextView) findViewById(R.id.infoCount);
        textUsername = (TextView) findViewById(R.id.infoUsername);
        textId = (TextView) findViewById(R.id.infoId);
        rowUsername = findViewById(R.id.rowUsername);
        rowAbout = findViewById(R.id.rowAbout);
        rowPhone = findViewById(R.id.rowPhone);
        textAbout = (TextView) findViewById(R.id.infoAbout);
        textPhone = (TextView) findViewById(R.id.infoPhone);
        rowAbout.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copy(textAbout.getText());
            }
        });
        rowPhone.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copy(textPhone.getText());
            }
        });

        EmojiMap.setText(textTitle, title);
        textId.setText(peerId);
        View write = findViewById(R.id.btnWrite);
        if (canWrite) {
            write.setVisibility(View.VISIBLE);
            write.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // Новый диалог: чат с этим человеком; он появится в списке с первым сообщением
                    Intent i = new Intent(ChatInfoActivity.this, ChatActivity.class);
                    i.putExtra(ChatActivity.EXTRA_ID, peerId);
                    i.putExtra(ChatActivity.EXTRA_TITLE, title);
                    startActivity(i);
                    finish();
                }
            });
        }
        findViewById(R.id.rowId).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copy(textId.getText());
            }
        });
        rowUsername.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copy(textUsername.getText());
            }
        });

        avatars = new AvatarLoader(this, api, new AvatarLoader.Listener() {
            public boolean isWanted(String id) {
                return !destroyed;
            }

            public void onAvatarLoaded(String id) {
                if (!destroyed) {
                    showAvatar();
                }
            }
        }, 120);
        showAvatar();
        load();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (avatars != null) {
            avatars.shutdown();
        }
        super.onDestroy();
    }

    private void showAvatar() {
        avatars.load(avatar, peerId, title, hasPhoto, forum);
    }

    @SuppressWarnings("deprecation")
    private void copy(CharSequence text) {
        // android.text.ClipboardManager есть на всех версиях Android, в том числе 1.5
        android.text.ClipboardManager cm = (android.text.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setText(text);
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    /** Название, фото, число участников и username берутся с сервера; показывается, что удалось получить. */
    private void load() {
        new Thread(new Runnable() {
            public void run() {
                String name = null;
                boolean photo = false;
                boolean channel = false;
                String username = "";
                int count = 0;
                String about = "";
                String phone = "";
                try {
                    JSONObject p = api.call("getPeer", "id", peerId);
                    if (p.has("t")) {
                        name = Api.str(p, "t");
                    } else {
                        String fn = Api.str(p, "fn");
                        String ln = Api.str(p, "ln");
                        name = (fn + " " + ln).trim();
                    }
                    photo = p.optBoolean("p", false);
                    channel = p.optBoolean("c", false);
                    username = Api.str(p, "name");
                } catch (Exception e) {
                    // Покажем то, что передали при открытии
                }
                try {
                    JSONObject info = api.call("getPeerInfo", "id", peerId);
                    count = info.optInt("count", 0);
                    about = Api.str(info, "about").trim();
                    phone = Api.str(info, "phone").trim(); // сервер отдаёт номер, только если его видно по настройкам приватности
                    String u = Api.str(info, "username");
                    if (u.length() > 0) {
                        username = u;
                    }
                } catch (Exception e) {
                    // Счётчика и username может не быть
                }
                try {
                    // Описание и номер сервер MPGram кладёт в getFullInfo: full.about и User.phone
                    // (getPeerInfo ищет их не там и обычно возвращает пусто)
                    JSONObject fi = api.call("getFullInfo", "id", peerId);
                    JSONObject full = fi.optJSONObject("full");
                    if (full != null && about.length() == 0) {
                        about = Api.str(full, "about").trim();
                    }
                    JSONObject user = fi.optJSONObject("User");
                    if (user != null && phone.length() == 0) {
                        phone = Api.str(user, "phone").trim();
                    }
                } catch (Exception e) {
                    // Без описания профиль остаётся прежним
                }
                final String fName = name;
                final boolean fPhoto = photo;
                final boolean fChannel = channel;
                final String fUser = username.startsWith("@") ? username.substring(1) : username;
                final int fCount = count;
                final String fAbout = about;
                final String fPhone = peerId.startsWith("-") || phone.length() == 0 ? ""
                        : (Character.isDigit(phone.charAt(0)) ? "+" + phone : phone);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        if (fName != null && fName.length() > 0) {
                            title = fName;
                            EmojiMap.setText(textTitle, title);
                        }
                        // Число показываем для групп и каналов (у людей id положительный)
                        if (peerId.startsWith("-") && fCount > 0) {
                            textCount.setText(fChannel
                                    ? Plural.format(ChatInfoActivity.this, fCount,
                                            R.string.subscribers_one, R.string.subscribers_few, R.string.subscribers_many)
                                    : Plural.format(ChatInfoActivity.this, fCount,
                                            R.string.members_one, R.string.members_few, R.string.members_many));
                            textCount.setVisibility(View.VISIBLE);
                        }
                        if (fUser.length() > 0) {
                            textUsername.setText("@" + fUser);
                            rowUsername.setVisibility(View.VISIBLE);
                        }
                        if (fAbout.length() > 0) {
                            EmojiMap.setText(textAbout, fAbout);
                            rowAbout.setVisibility(View.VISIBLE);
                        }
                        if (fPhone.length() > 0) {
                            textPhone.setText(fPhone);
                            rowPhone.setVisibility(View.VISIBLE);
                        }
                        hasPhoto = fPhoto;
                        showAvatar();
                    }
                });
            }
        }).start();
    }
}
