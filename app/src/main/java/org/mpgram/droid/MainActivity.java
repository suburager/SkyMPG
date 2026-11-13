package org.mpgram.droid;

import android.content.Intent;
import android.os.Bundle;
import android.graphics.Typeface;
import android.os.Handler;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Список чатов. Быстрый старт из кэша на диске, обновление с сервера в фоне, последние сообщения и
 * аватарки подгружаются только для строк на экране и только когда прокрутка не идёт полным ходом.
 */
public class MainActivity extends BaseActivity {

    private static final int ID_REFRESH = 3;
    private static final int ID_LOGOUT = 4;
    private static final int REQ_CHAT = 1;
    private static final long RETRY_MS = 30000;
    private static final long SAVE_DELAY_MS = 3000;

    /** Ссылки на элементы строки, чтобы не искать их через findViewById при каждой отрисовке. */
    private static class Holder {
        ImageView avatar;
        TextView title;
        TextView time;
        TextView preview;
        TextView unread;
    }

    // Форматтеры создаются один раз (это дорого). SimpleDateFormat не потокобезопасен, поэтому formatTime synchronized.
    private static final SimpleDateFormat FMT_TIME = new SimpleDateFormat("HH:mm");
    private static final SimpleDateFormat FMT_DAY = new SimpleDateFormat("dd.MM");
    private static final SimpleDateFormat FMT_FULL = new SimpleDateFormat("dd.MM.yy");

    private String appliedLang;
    private Api api;
    private TextView textInfo;
    private ListView listChats;
    private ChatAdapter adapter;
    private AvatarLoader avatars;

    // Папки чатов: "" это основной список, "1" архив, остальное папки пользователя
    private String folder = "";
    private int loadGen;
    private View folderBar;
    private LinearLayout folderRow;
    private Folders.Data folderData = new Folders.Data();

    private final ArrayList<ChatItem> items = new ArrayList<ChatItem>();
    // Эти поля читаются из фоновых потоков, поэтому volatile, а карта после публикации не меняется
    private volatile HashMap<String, Integer> indexById = new HashMap<String, Integer>();
    private volatile int firstVisible = 0;
    private volatile int lastVisible = 15;

    private final LifoExecutor msgQueue = new LifoExecutor(2);
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler();

    private boolean flinging;
    private static final int QUICK_LIMIT = 30;
    private boolean loading;
    private boolean dirty;
    private boolean saveScheduled;
    private volatile boolean destroyed;

    private final Runnable saveRunnable = new Runnable() {
        public void run() {
            if (flinging) {
                // Сериализация списка в JSON на этом процессоре заметна: ждём, пока прокрутка остановится
                ui.postDelayed(this, 1500);
                return;
            }
            saveScheduled = false;
            saveNow();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.apply(this);
        appliedLang = Lang.get(this);

        api = Api.load(this);
        if (!api.isLoggedIn()) {
            goLogin();
            return;
        }

        setContentView(R.layout.main);
        setupHeader(true);
        textInfo = (TextView) findViewById(R.id.textInfo);
        listChats = (ListView) findViewById(R.id.listChats);
        folderBar = findViewById(R.id.folderBar);
        folderRow = (LinearLayout) findViewById(R.id.folderRow);

        avatars = new AvatarLoader(this, api, new AvatarLoader.Listener() {
            public boolean isWanted(String id) {
                return MainActivity.this.isWanted(id);
            }

            public void onAvatarLoaded(String id) {
                if (destroyed) {
                    return;
                }
                Integer pos = indexById.get(id);
                if (pos != null) {
                    refreshRow(pos.intValue());
                }
            }
        });

        adapter = new ChatAdapter();
        listChats.setAdapter(adapter);
        listChats.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= items.size()) {
                    return;
                }
                ChatItem c = items.get(position);
                // Супергруппы могут быть форумами: сначала показываем список топиков
                boolean forumRoute = ForumCache.mayBeForum(c.id)
                        && ForumCache.get(MainActivity.this, c.id) != ForumCache.NOT_FORUM;
                Intent i = new Intent(MainActivity.this,
                        forumRoute ? TopicsActivity.class : ChatActivity.class);
                i.putExtra(ChatActivity.EXTRA_ID, c.id);
                i.putExtra(ChatActivity.EXTRA_TITLE, c.title);
                startActivityForResult(i, REQ_CHAT);
            }
        });
        listChats.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                boolean fling = scrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING;
                boolean wasFling = flinging;
                flinging = fling;
                avatars.setPaused(fling);
                if (!fling) {
                    if (wasFling) {
                        rebindVisible(); // подхватываем то, что успело загрузиться за время прокрутки
                    }
                    requestVisibleMessages();
                }
            }

            public void onScroll(AbsListView view, int first, int visibleCount, int totalCount) {
                firstVisible = first;
                lastVisible = first + visibleCount - 1;
            }
        });

        // Вкладки папок: сначала из кэша, потом свежий список с сервера
        setFolders(Folders.load(this, api.sessionHash()));
        loadFolders();

        // Быстрый старт: показываем кэш, пока идёт запрос к серверу
        ArrayList<ChatItem> cached = ChatCache.load(this, api.sessionHash(), folder);
        if (!cached.isEmpty()) {
            setItems(cached);
            textInfo.setVisibility(View.GONE);
        }
        loadChats();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Экран ушёл на задний план (например, открыт чат): не отнимаем у него канал
        msgQueue.clear();
        if (listChats != null) {
            ui.removeCallbacks(saveRunnable);
            saveScheduled = false;
            saveNow();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        if (avatars != null) {
            avatars.shutdown();
        }
        msgQueue.shutdown();
        ioPool.shutdown(); // уже поставленная запись кэша допишется
        super.onDestroy();
    }

    // ---------- Папки ----------

    private int dp(int v) {
        return Ui.px(this, v);
    }

    private void loadFolders() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject o = api.call("getFolders");
                    final Folders.Data data = Folders.parse(o);
                    Folders.save(MainActivity.this, api.sessionHash(), data);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (!destroyed) {
                                setFolders(data);
                            }
                        }
                    });
                } catch (Exception e) {
                    // Без сети остаются вкладки из кэша, а если папок нет, вкладок просто не будет
                }
            }
        }).start();
    }

    /** Перестраивает полосу вкладок. Вкладки скрыты, если у аккаунта нет ни папок, ни архива. */
    private void setFolders(Folders.Data data) {
        folderData = data;
        folderRow.removeAllViews();
        boolean any = !data.list.isEmpty() || data.archive;
        folderBar.setVisibility(any ? View.VISIBLE : View.GONE);

        boolean exists = folder.length() == 0;
        if (any) {
            addTab("", getString(R.string.folder_all));
            for (int i = 0; i < data.list.size(); i++) {
                Folders.Folder f = data.list.get(i);
                String title = f.title.length() > 0 ? f.title : getString(R.string.folder_n, f.id);
                addTab(f.id, title);
                if (f.id.equals(folder)) {
                    exists = true;
                }
            }
            if (data.archive) {
                addTab("1", getString(R.string.folder_archive));
                if ("1".equals(folder)) {
                    exists = true;
                }
            }
        }
        // Выбранную папку удалили: возвращаемся к основному списку
        if (!exists) {
            selectFolder("");
        }
        highlightTabs();
    }

    /** Вкладка в стиле классических Android-табов: заглавные буквы, у выбранной голубая линия снизу. */
    private void addTab(String id, String title) {
        TextView tv = new TextView(this);
        tv.setText(title.toUpperCase(Locale.getDefault()));
        tv.setTag(id);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(14 * Ui.scale(this));
        tv.setTypeface(null, Typeface.BOLD);
        tv.setHeight(dp(40));
        tv.setPadding(dp(16), 0, dp(16), 0);
        tv.setClickable(true);
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                selectFolder((String) v.getTag());
            }
        });
        // Вес 1: пока вкладки помещаются, делят ширину поровну; иначе полоса прокручивается
        folderRow.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void highlightTabs() {
        for (int i = 0; i < folderRow.getChildCount(); i++) {
            TextView tv = (TextView) folderRow.getChildAt(i);
            boolean sel = folder.equals(tv.getTag());
            tv.setBackgroundResource(sel ? R.drawable.tab_selected : R.drawable.tab_normal);
            tv.setTextColor(sel ? 0xFFFFFFFF : 0xFF999999);
        }
    }

    /** Переключает список на другую папку: сначала кэш этой папки, затем свежие данные. */
    private void selectFolder(String f) {
        if (f.equals(folder)) {
            return;
        }
        saveNow(); // не теряем изменения прежней папки
        folder = f;
        loadGen++; // ответы на прежние запросы теперь игнорируются
        loading = false;
        highlightTabs();

        ArrayList<ChatItem> cached = ChatCache.load(this, api.sessionHash(), f);
        setItems(cached);
        if (!cached.isEmpty()) {
            textInfo.setVisibility(View.GONE);
        }
        listChats.setSelection(0);
        loadChats();
    }

    // ---------- Загрузка списка ----------

    private void loadChats() {
        if (loading) {
            return;
        }
        loading = true;
        boolean cold = items.isEmpty();
        if (cold) {
            textInfo.setTextColor(0xFFAAAAAA);
            textInfo.setText(R.string.main_loading);
            textInfo.setVisibility(View.VISIBLE);
        }
        // Без кэша сначала берём небольшую порцию: она приходит и разбирается в разы быстрее,
        // а полный список подтягивается следом
        startLoad(cold);
    }

    /** Запасные размеры страницы, если сервер падает на одном из диалогов (см. Errors.isServerArrayKey). */
    private static final int[] DIALOG_FALLBACK = {50, 20, 10, 5};

    /**
     * getDialogs. Если сервер упал с "Undefined array key" (в ответе есть диалог, который он не
     * смог разобрать), повторяем с меньшим лимитом: список покажется хотя бы частично.
     */
    private JSONObject loadDialogs(String f, int limit) throws java.io.IOException, Api.ApiException {
        int next = -1;
        int limitNow = limit;
        while (true) {
            try {
                return api.call("getDialogs",
                        "limit", String.valueOf(limitNow),
                        "f", f.length() > 0 ? f : null,
                        "fields", "dialogs,users,chats");
            } catch (Api.ApiException e) {
                if (!Errors.isServerArrayKey(e)) {
                    throw e;
                }
                // Берём следующий по убыванию лимит из запасных, меньший текущего
                next = -1;
                for (int i = 0; i < DIALOG_FALLBACK.length; i++) {
                    if (DIALOG_FALLBACK[i] < limitNow) {
                        next = DIALOG_FALLBACK[i];
                        break;
                    }
                }
                if (next < 0) {
                    throw e;
                }
                limitNow = next;
            }
        }
    }

    private void startLoad(final boolean quick) {
        // Результат нужен, только если за время запроса не переключили папку
        final int gen = loadGen;
        final String f = folder;
        // Старое содержимое нужно, чтобы не терять превью сообщений при обновлении
        final HashMap<String, ChatItem> old = new HashMap<String, ChatItem>();
        for (int i = 0; i < items.size(); i++) {
            old.put(items.get(i).id, items.get(i));
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject o = loadDialogs(f, quick ? QUICK_LIMIT : 100);
                    JSONArray raw = o.optJSONArray("dialogs");
                    // Если сервер отдал меньше запрошенного, это уже весь список
                    final boolean more = quick && raw != null && raw.length() >= QUICK_LIMIT;
                    final ArrayList<ChatItem> result = parseDialogs(o, old);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed || gen != loadGen) {
                                return;
                            }
                            setItems(result);
                            if (items.isEmpty()) {
                                textInfo.setTextColor(0xFFAAAAAA);
                                textInfo.setText(R.string.chats_empty);
                                textInfo.setVisibility(View.VISIBLE);
                            } else {
                                textInfo.setVisibility(View.GONE);
                            }
                            markDirty();
                            requestVisibleMessages();
                            if (more) {
                                startLoad(false);
                            } else {
                                loading = false;
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed || gen != loadGen) {
                                return;
                            }
                            loading = false;
                            if (Errors.isSessionError(e)) {
                                // Сессия на сервере истекла
                                Api.clearSession(MainActivity.this);
                                AvatarLoader.clearDisk(MainActivity.this);
                                ImageLoader.clearDisk(MainActivity.this);
                                LocalGroups.clear(MainActivity.this);
                                ChatCache.clear(MainActivity.this);
                Folders.clear(MainActivity.this);
                                PageCache.clearAll(MainActivity.this);
                                ForumCache.clear(MainActivity.this);
                                dirty = false; // чтобы onPause не записал кэш заново
                                goLogin();
                                return;
                            }
                            // Кэшированный список остаётся на экране, ошибка показывается над ним
                            textInfo.setTextColor(0xFFFF8080);
                            textInfo.setText(Errors.text(MainActivity.this, e));
                            textInfo.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        }).start();
    }

    /** Заменяет содержимое списка и перестраивает индекс. Только из UI-потока. */
    private void setItems(ArrayList<ChatItem> list) {
        items.clear();
        items.addAll(list);
        HashMap<String, Integer> map = new HashMap<String, Integer>();
        for (int i = 0; i < items.size(); i++) {
            ChatItem c = items.get(i);
            c.index = i;
            map.put(c.id, Integer.valueOf(i));
        }
        indexById = map;
        adapter.notifyDataSetChanged();
    }

    // ---------- Разбор ответа getDialogs ----------

    private ArrayList<ChatItem> parseDialogs(JSONObject o, HashMap<String, ChatItem> old) {
        ArrayList<ChatItem> out = new ArrayList<ChatItem>();
        JSONArray arr = o.optJSONArray("dialogs");
        if (arr == null) {
            return out;
        }
        JSONObject users = o.optJSONObject("users");
        JSONObject chats = o.optJSONObject("chats");
        users = fillMissingSenders(arr, users);

        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            if (d == null) {
                continue;
            }
            String id = Api.str(d, "id");
            if (id.length() == 0 || isSecret(id)) {
                continue; // секретные чаты API не поддерживает
            }
            ChatItem c = new ChatItem();
            c.id = id;
            c.title = titleFor(id, users, chats);
            JSONObject peer = id.startsWith("-")
                    ? (chats == null ? null : chats.optJSONObject(id))
                    : (users == null ? null : users.optJSONObject(id));
            c.hasPhoto = peer != null && peer.optBoolean("p", false);
            c.forum = peer != null && peer.optBoolean("f", false);
            c.setUnread(d.optInt("unread", 0));
            c.pinned = d.optBoolean("pin", false);
            c.muted = d.optBoolean("silent", false);

            JSONObject msg = d.optJSONObject("msg");
            if (msg == null) {
                msg = d.optJSONObject("message");
            }
            if (msg != null) {
                c.hasMsg = true;
                c.msgFresh = true;
                c.previewPrefix = prefixFor(id, msg, users);
                c.preview = previewFor(msg);
                c.time = formatTime(msg.optLong("date", 0));
            } else {
                // Сервер сообщение не прислал: показываем прежнее, актуальное подгрузим для строк на экране
                ChatItem prev = old.get(id);
                if (prev != null && prev.hasMsg) {
                    c.hasMsg = true;
                    c.previewPrefix = prev.previewPrefix;
                    c.preview = prev.preview;
                    c.time = prev.time;
                }
            }
            c.buildLine();
            out.add(c);
        }
        return out;
    }

    private static boolean isSecret(String id) {
        try {
            return Long.parseLong(id) < -2000000000000L;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String titleFor(String id, JSONObject users, JSONObject chats) {
        if (id.startsWith("-")) {
            JSONObject chat = chats == null ? null : chats.optJSONObject(id);
            if (chat != null) {
                String t = Api.str(chat, "t");
                if (t.length() > 0) {
                    return t;
                }
            }
            return id;
        }
        JSONObject user = users == null ? null : users.optJSONObject(id);
        if (user != null) {
            String name = (Api.str(user, "fn") + " " + Api.str(user, "ln")).trim();
            if (name.length() > 0) {
                return name;
            }
            String link = Api.str(user, "name");
            if (link.length() > 0) {
                return "@" + link;
            }
        }
        return getString(R.string.chat_deleted);
    }

    /**
     * В группах в строке "Имя: сообщение" нужны имена отправителей. Если сервер не прислал
     * кого-то в users, докачиваем одним запросом getPeers.
     */
    private JSONObject fillMissingSenders(JSONArray dialogs, JSONObject users) {
        HashSet<String> missing = new HashSet<String>();
        for (int i = 0; i < dialogs.length(); i++) {
            JSONObject d = dialogs.optJSONObject(i);
            JSONObject msg = d == null ? null : d.optJSONObject("msg");
            if (msg == null || !Api.str(d, "id").startsWith("-")) {
                continue;
            }
            String from = Api.str(msg, "from_id");
            if (from.length() > 0 && !from.startsWith("-")
                    && (users == null || users.optJSONObject(from) == null)) {
                missing.add(from);
            }
        }
        if (missing.isEmpty()) {
            return users;
        }
        try {
            StringBuffer ids = new StringBuffer();
            int n = 0;
            for (String id : missing) {
                if (n++ >= 100) {
                    break;
                }
                if (ids.length() > 0) {
                    ids.append(',');
                }
                ids.append(id);
            }
            JSONObject res = api.call("getPeers", "id", ids.toString());
            JSONObject extra = res.optJSONObject("users");
            if (extra != null) {
                if (users == null) {
                    users = new JSONObject();
                }
                java.util.Iterator<?> keys = extra.keys();
                while (keys.hasNext()) {
                    String k = (String) keys.next();
                    users.put(k, extra.get(k));
                }
            }
        } catch (Exception e) {
            // Без имён строка просто покажет текст сообщения
        }
        return users;
    }

    private static String senderName(JSONObject users, String fromId) {
        JSONObject u = users == null ? null : users.optJSONObject(fromId);
        if (u == null) {
            return "";
        }
        String name = Api.str(u, "fn");
        if (name.length() == 0) {
            name = Api.str(u, "ln");
        }
        if (name.length() == 0 && Api.str(u, "name").length() > 0) {
            name = "@" + Api.str(u, "name");
        }
        return name;
    }

    /** Цветная часть строки: "Вы: " для своих сообщений, "Имя: " для чужих в группах. */
    private String prefixFor(String dialogId, JSONObject msg, JSONObject users) {
        if (msg == null) {
            return "";
        }
        if (msg.optBoolean("out", false)) {
            return getString(R.string.preview_you) + " ";
        }
        if (dialogId.startsWith("-")) {
            String from = Api.str(msg, "from_id");
            if (from.length() > 0 && !from.startsWith("-")) {
                String name = senderName(users, from);
                if (name.length() > 0) {
                    return name + ": ";
                }
            }
        }
        return "";
    }

    /** Серая часть строки: текст сообщения или тип вложения. */
    private String previewFor(JSONObject msg) {
        if (msg == null) {
            return "";
        }
        String text = Api.str(msg, "text").replace('\n', ' ').trim();
        if (text.length() > 0) {
            return text;
        }

        JSONObject media = msg.optJSONObject("media");
        if (media != null) {
            String type = Api.str(media, "type");
            int res;
            if ("photo".equals(type)) {
                res = R.string.preview_photo;
            } else if ("document".equals(type)) {
                if (media.optBoolean("voice", false)) {
                    res = R.string.preview_voice;
                } else if (media.optBoolean("audio", false)) {
                    res = R.string.preview_audio;
                } else {
                    res = R.string.preview_file;
                }
            } else if ("webpage".equals(type)) {
                res = R.string.preview_link;
            } else if ("geo".equals(type)) {
                res = R.string.preview_location;
            } else if ("poll".equals(type)) {
                res = R.string.preview_poll;
            } else {
                res = R.string.preview_media;
            }
            return getString(res);
        }
        if (msg.has("act")) {
            return getString(R.string.preview_service);
        }
        // getDialogs не присылает media, поэтому сообщение без текста считаем медиа
        return getString(R.string.preview_media);
    }

    /** Время сегодняшнего сообщения, дата для остальных. date в секундах Unix. */
    private static synchronized String formatTime(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        Date date = new Date(seconds * 1000L);
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTime(date);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            if (now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
                return FMT_TIME.format(date);
            }
            return FMT_DAY.format(date);
        }
        return FMT_FULL.format(date);
    }

    // ---------- Последние сообщения ----------

    /** Нужна ли строка сейчас: на экране или рядом с ним. Вызывается и из фоновых потоков. */
    private boolean isWanted(String id) {
        Integer pos = indexById.get(id);
        if (pos == null) {
            return false;
        }
        int p = pos.intValue();
        return p >= firstVisible - 3 && p <= lastVisible + 3;
    }

    private boolean needsMessage(ChatItem c) {
        return !c.msgFresh && !c.msgRequested
                && System.currentTimeMillis() - c.failAt > RETRY_MS;
    }

    private void requestVisibleMessages() {
        if (listChats == null || flinging) {
            return;
        }
        int first = listChats.getFirstVisiblePosition();
        int last = listChats.getLastVisiblePosition();
        for (int i = first; i <= last && i < items.size(); i++) {
            if (i >= 0 && needsMessage(items.get(i))) {
                requestLastMessage(items.get(i));
            }
        }
    }

    /**
     * getDialogs на этих серверах не присылает последнее сообщение, поэтому берём его через getHistory.
     * Запросы уходят только для строк на экране; если строку успели прокрутить, запрос пропускается.
     */
    private void requestLastMessage(final ChatItem c) {
        c.msgRequested = true;
        msgQueue.execute(new Runnable() {
            public void run() {
                if (!isWanted(c.id)) {
                    finishRequest(c, null, false);
                    return;
                }
                try {
                    JSONObject res = api.call("getHistory",
                            "peer", c.id,
                            "limit", "1",
                            "media", "1",
                            "fields", "messages,users,chats");
                    JSONArray msgs = res.optJSONArray("messages");
                    JSONObject msg = (msgs != null && msgs.length() > 0) ? msgs.optJSONObject(0) : null;
                    if (msg == null) {
                        finishRequest(c, null, true); // чат пуст, повторять незачем
                        return;
                    }
                    String prefix = prefixFor(c.id, msg, res.optJSONObject("users"));
                    String[] result = new String[] {
                            prefix, previewFor(msg), formatTime(msg.optLong("date", 0))
                    };
                    finishRequest(c, result, true);
                } catch (Exception e) {
                    finishRequest(c, null, false);
                    markFailed(c);
                }
            }
        });
    }

    private void markFailed(final ChatItem c) {
        runOnUiThread(new Runnable() {
            public void run() {
                c.failAt = System.currentTimeMillis();
            }
        });
    }

    /** Сообщает результат в UI-поток. result == null: сообщения нет; fresh: считать строку актуальной. */
    private void finishRequest(final ChatItem c, final String[] result, final boolean fresh) {
        runOnUiThread(new Runnable() {
            public void run() {
                c.msgRequested = false;
                if (destroyed) {
                    return;
                }
                if (fresh) {
                    c.msgFresh = true;
                }
                if (result != null) {
                    c.previewPrefix = result[0];
                    c.preview = result[1];
                    c.time = result[2];
                    c.hasMsg = true;
                    c.buildLine();
                    // Если список за это время заменили, этот объект уже не на экране
                    if (c.index >= 0 && c.index < items.size() && items.get(c.index) == c) {
                        refreshRow(c.index);
                        markDirty();
                    }
                }
            }
        });
    }

    // ---------- Отрисовка строк ----------

    /** Перерисовывает одну строку, если она на экране; остальной список не трогается. */
    private void refreshRow(int pos) {
        if (listChats == null || pos < 0 || pos >= items.size()) {
            return;
        }
        int first = listChats.getFirstVisiblePosition();
        int last = listChats.getLastVisiblePosition();
        if (pos < first || pos > last) {
            return;
        }
        View child = listChats.getChildAt(pos - first);
        if (child != null) {
            bind(child, items.get(pos));
        }
    }

    private void rebindVisible() {
        int first = listChats.getFirstVisiblePosition();
        int last = listChats.getLastVisiblePosition();
        for (int i = first; i <= last && i < items.size(); i++) {
            View child = listChats.getChildAt(i - first);
            if (child != null && i >= 0) {
                bind(child, items.get(i));
            }
        }
    }

    private void bind(View v, ChatItem c) {
        Holder h = (Holder) v.getTag();
        // Форум известен из списка диалогов (флаг f) или из того, что чат уже открывали как форум
        if (!c.squareChecked) {
            c.square = c.forum || (ForumCache.mayBeForum(c.id) && ForumCache.get(this, c.id) == ForumCache.FORUM);
            c.squareChecked = true;
        }
        avatars.load(h.avatar, c.id, c.title, c.hasPhoto, c.square);
        EmojiMap.setText(h.title, c.title);
        h.time.setText(c.time);
        EmojiMap.setText(h.preview, c.line);
        if (c.unread > 0) {
            h.unread.setText(c.unreadText);
            h.unread.setBackgroundResource(c.muted ? R.drawable.badge_muted : R.drawable.badge);
            h.unread.setVisibility(View.VISIBLE);
        } else {
            h.unread.setVisibility(View.GONE);
        }
    }

    private class ChatAdapter extends ArrayAdapter<ChatItem> {

        ChatAdapter() {
            super(MainActivity.this, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = Ui.scaleTree(getLayoutInflater().inflate(R.layout.chat_item, null));
                Holder h = new Holder();
                h.avatar = (ImageView) v.findViewById(R.id.imgAvatar);
                h.title = (TextView) v.findViewById(R.id.textTitle);
                h.time = (TextView) v.findViewById(R.id.textTime);
                h.preview = (TextView) v.findViewById(R.id.textPreview);
                h.unread = (TextView) v.findViewById(R.id.textUnread);
                v.setTag(h);
            }
            ChatItem c = getItem(position);
            bind(v, c);
            // Во время быстрой прокрутки ничего не запрашиваем, догрузим, когда она остановится
            if (!flinging && needsMessage(c)) {
                requestLastMessage(c);
            }
            return v;
        }
    }

    // ---------- Кэш списка на диске ----------

    private void markDirty() {
        dirty = true;
        if (!saveScheduled) {
            saveScheduled = true;
            ui.postDelayed(saveRunnable, SAVE_DELAY_MS);
        }
    }

    private void saveNow() {
        if (!dirty) {
            return;
        }
        dirty = false;
        final String saveFolder = folder;
        try {
            final String json = ChatCache.toJson(items, api.sessionHash());
            ioPool.execute(new Runnable() {
                public void run() {
                    ChatCache.write(MainActivity.this, json, saveFolder);
                }
            });
        } catch (Exception e) {
            // Кэш необязателен
        }
    }

    // ---------- Меню и выход ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        AppMenu.create(menu);
        menu.add(0, ID_REFRESH, 2, R.string.refresh)
                .setIcon(android.R.drawable.ic_menu_rotate);
        menu.add(0, ID_LOGOUT, 3, R.string.btn_logout)
                .setIcon(android.R.drawable.ic_menu_close_clear_cancel);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == ID_REFRESH) {
            // В новом списке строки без свежего сообщения заново запросят его для видимых строк
            loadChats();
            loadFolders();
            return true;
        }
        if (item.getItemId() == ID_LOGOUT) {
            logout();
            return true;
        }
        return AppMenu.select(this, item) || super.onOptionsItemSelected(item);
    }

    /** Если в чате выяснилось, что сессия истекла, возвращаемся ко входу. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CHAT && resultCode == ChatActivity.RESULT_SESSION_EXPIRED) {
            AvatarLoader.clearDisk(this);
            ImageLoader.clearDisk(this);
            LocalGroups.clear(this);
            ChatCache.clear(this);
            Folders.clear(this);
            PageCache.clearAll(this);
            ForumCache.clear(this);
            dirty = false; // чтобы onPause не записал кэш заново
            goLogin();
        }
    }

    // Если язык сменили в настройках, пересоздаём экран с новой локалью
    @Override
    protected void onResume() {
        super.onResume();
        if (appliedLang != null && !appliedLang.equals(Lang.get(this))) {
            Lang.restart(this);
            return;
        }
        // Запросы, снятые в onPause, ещё помечены как выполняемые: сбрасываем и просим заново
        for (int i = 0; i < items.size(); i++) {
            items.get(i).msgRequested = false;
        }
        requestVisibleMessages();
    }

    private void logout() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    api.call("logout");
                } catch (Exception e) {
                    // Всё равно выходим локально
                }
                Api.clearSession(MainActivity.this);
                AvatarLoader.clearDisk(MainActivity.this);
                ImageLoader.clearDisk(MainActivity.this);
                LocalGroups.clear(MainActivity.this);
                ChatCache.clear(MainActivity.this);
                Folders.clear(MainActivity.this);
                PageCache.clearAll(MainActivity.this);
                                ForumCache.clear(MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        dirty = false; // чтобы onPause не записал кэш заново
                        goLogin();
                    }
                });
            }
        }).start();
    }

    private void goLogin() {
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}
