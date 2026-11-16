package org.mpgram.droid;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

/**
 * Список топиков форума. Если чат оказывается обычным (не форумом), сразу открывает его как обычный чат.
 * Последние сообщения топиков берутся одним запросом getMessages.
 */
public class TopicsActivity extends BaseActivity {

    private static final int REQ_REDIRECT = 1;
    private static final int REQ_TOPIC = 2;
    private static final int ID_REFRESH = 3;
    private static final int LIMIT = 50;

    /** Строка списка: ChatItem плюс данные топика. */
    private static class TopicItem extends ChatItem {
        int topicId;
        int lastId;
        boolean closed;
    }

    private static class Holder {
        ImageView avatar;
        TextView title;
        TextView time;
        TextView preview;
        TextView unread;
    }

    private String appliedLang;
    private Api api;
    private String peerId = "";
    private String peerTitle = "";
    private TextView textInfo;
    private ListView listTopics;
    private TopicAdapter adapter;
    private AvatarLoader avatars;
    private final ArrayList<TopicItem> items = new ArrayList<TopicItem>();
    private boolean loading;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.apply(this);
        appliedLang = Lang.get(this);

        api = Api.load(this);
        String id = getIntent().getStringExtra(ChatActivity.EXTRA_ID);
        if (!api.isLoggedIn() || id == null || id.length() == 0) {
            finish();
            return;
        }
        peerId = id;
        String title = getIntent().getStringExtra(ChatActivity.EXTRA_TITLE);
        peerTitle = title == null ? "" : title;
        if (peerTitle.length() > 0) {
            setTitle(peerTitle);
        }

        setContentView(R.layout.main);
        setupHeader(true);
        // Шапка как у чата: аватарка слева, название и число участников по центру
        initChatHeader(api, peerId, peerTitle, true); // экран топиков бывает только у форума
        textInfo = (TextView) findViewById(R.id.textInfo);
        listTopics = (ListView) findViewById(R.id.listChats);

        // Аватарки у топиков нет, используется только цветная заглушка с буквой
        avatars = new AvatarLoader(this, api, new AvatarLoader.Listener() {
            public boolean isWanted(String id) {
                return false;
            }

            public void onAvatarLoaded(String id) {
            }
        });

        adapter = new TopicAdapter();
        listTopics.setAdapter(adapter);
        listTopics.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= items.size()) {
                    return;
                }
                TopicItem t = items.get(position);
                startActivityForResult(chatIntent(t.topicId, t.title), REQ_TOPIC);
            }
        });

        loadTopics();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (avatars != null) {
            avatars.shutdown();
        }
        super.onDestroy();
    }

    private Intent chatIntent(int topicId, String title) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_ID, peerId);
        i.putExtra(ChatActivity.EXTRA_TITLE, title);
        if (topicId > 0) {
            i.putExtra(ChatActivity.EXTRA_TOPIC, topicId);
        }
        return i;
    }

    // ---------- Загрузка ----------

    private void loadTopics() {
        if (loading) {
            return;
        }
        loading = true;
        if (items.isEmpty()) {
            textInfo.setTextColor(0xFFAAAAAA);
            textInfo.setText(R.string.main_loading);
            textInfo.setVisibility(View.VISIBLE);
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject res = api.call("getForumTopics",
                            "peer", peerId,
                            "limit", String.valueOf(LIMIT));
                    JSONArray arr = res.optJSONArray("res");
                    if (arr == null || arr.length() == 0) {
                        notForum(ForumCache.TTL_LONG);
                        return;
                    }
                    final ArrayList<TopicItem> list = parseTopics(arr);
                    fillPreviews(list);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            loading = false;
                            ForumCache.setForum(TopicsActivity.this, peerId);
                            items.clear();
                            items.addAll(list);
                            adapter.notifyDataSetChanged();
                            textInfo.setVisibility(View.GONE);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            loading = false;
                            if (Errors.isSessionError(e)) {
                                Api.clearSession(TopicsActivity.this);
                                setResult(ChatActivity.RESULT_SESSION_EXPIRED);
                                finish();
                                return;
                            }
                            if (e instanceof Api.ApiException) {
                                // Сервер ответил ошибкой: скорее всего, это не форум. Если в тексте
                                // явно про форум, запоминаем надолго, иначе ненадолго.
                                String m = e.getMessage();
                                boolean sure = m != null && m.toUpperCase().indexOf("FORUM") >= 0;
                                ForumCache.setNotForum(TopicsActivity.this, peerId,
                                        sure ? ForumCache.TTL_LONG : ForumCache.TTL_SHORT);
                                redirectToChat();
                                return;
                            }
                            // Нет сети и т.п.: остаёмся на экране с ошибкой
                            textInfo.setTextColor(0xFFFF8080);
                            textInfo.setText(Errors.text(TopicsActivity.this, e));
                            textInfo.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        }).start();
    }

    private void notForum(final long ttl) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (destroyed) {
                    return;
                }
                loading = false;
                ForumCache.setNotForum(TopicsActivity.this, peerId, ttl);
                redirectToChat();
            }
        });
    }

    /** Чат не форум: открываем его как обычный. Когда он закроется, закрываем и этот экран. */
    private void redirectToChat() {
        startActivityForResult(chatIntent(0, peerTitle), REQ_REDIRECT);
    }

    private ArrayList<TopicItem> parseTopics(JSONArray arr) {
        ArrayList<TopicItem> list = new ArrayList<TopicItem>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) {
                continue;
            }
            TopicItem it = new TopicItem();
            it.topicId = t.optInt("id", 0);
            if (it.topicId <= 0) {
                continue;
            }
            it.id = peerId + "_" + it.topicId;
            it.title = Api.str(t, "title");
            if (it.title.length() == 0) {
                it.title = "#" + it.topicId;
            }
            it.lastId = t.optInt("top", 0);
            it.setUnread(t.optInt("unread", 0));
            it.pinned = t.optBoolean("pinned", false);
            it.closed = t.optBoolean("closed", false);
            it.buildLine();
            list.add(it);
        }
        // Закреплённые сверху, дальше по номеру последнего сообщения: свежие выше
        Collections.sort(list, new Comparator<TopicItem>() {
            public int compare(TopicItem a, TopicItem b) {
                if (a.pinned != b.pinned) {
                    return a.pinned ? -1 : 1;
                }
                return a.lastId > b.lastId ? -1 : (a.lastId == b.lastId ? 0 : 1);
            }
        });
        return list;
    }

    /** Последние сообщения всех топиков одним запросом. Если не вышло, строки остаются без превью. */
    private void fillPreviews(ArrayList<TopicItem> list) {
        StringBuffer ids = new StringBuffer();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).lastId > 0) {
                if (ids.length() > 0) {
                    ids.append(',');
                }
                ids.append(list.get(i).lastId);
            }
        }
        if (ids.length() == 0) {
            return;
        }
        try {
            JSONObject res = api.call("getMessages",
                    "peer", peerId,
                    "id", ids.toString(),
                    "media", "1",
                    "fields", "messages,users,chats");
            JSONArray msgs = res.optJSONArray("messages");
            if (msgs == null) {
                return;
            }
            JSONObject users = res.optJSONObject("users");
            HashMap<Integer, JSONObject> byId = new HashMap<Integer, JSONObject>();
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m != null) {
                    byId.put(Integer.valueOf(m.optInt("id", 0)), m);
                }
            }
            for (int i = 0; i < list.size(); i++) {
                TopicItem it = list.get(i);
                JSONObject m = byId.get(Integer.valueOf(it.lastId));
                if (m != null) {
                    it.previewPrefix = Preview.prefix(this, true, m, users);
                    it.preview = Preview.text(this, m);
                    it.time = Preview.time(m.optLong("date", 0));
                    it.hasMsg = true;
                    it.buildLine();
                }
            }
        } catch (Exception e) {
            // Без превью список топиков всё равно полезен
        }
    }

    // ---------- Отрисовка ----------

    private class TopicAdapter extends ArrayAdapter<TopicItem> {

        TopicAdapter() {
            super(TopicsActivity.this, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            Holder h;
            if (v == null) {
                v = Ui.scaleTree(getLayoutInflater().inflate(R.layout.chat_item, null));
                h = new Holder();
                h.avatar = (ImageView) v.findViewById(R.id.imgAvatar);
                h.title = (TextView) v.findViewById(R.id.textTitle);
                h.time = (TextView) v.findViewById(R.id.textTime);
                h.preview = (TextView) v.findViewById(R.id.textPreview);
                h.unread = (TextView) v.findViewById(R.id.textUnread);
                v.setTag(h);
            } else {
                h = (Holder) v.getTag();
            }
            TopicItem t = getItem(position);
            avatars.load(h.avatar, t.id, t.title, false);
            EmojiMap.setText(h.title, t.title);
            h.time.setText(t.time);
            EmojiMap.setText(h.preview, t.line);
            if (t.unread > 0) {
                h.unread.setText(t.unreadText);
                h.unread.setBackgroundResource(R.drawable.badge);
                h.unread.setVisibility(View.VISIBLE);
            } else {
                h.unread.setVisibility(View.GONE);
            }
            return v;
        }
    }

    // ---------- Результаты и меню ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == ChatActivity.RESULT_SESSION_EXPIRED) {
            setResult(ChatActivity.RESULT_SESSION_EXPIRED);
            finish();
        } else if (requestCode == REQ_REDIRECT) {
            finish();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        AppMenu.create(menu);
        menu.add(0, ID_REFRESH, 2, R.string.refresh)
                .setIcon(android.R.drawable.ic_menu_rotate);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == ID_REFRESH) {
            loadTopics();
            return true;
        }
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
