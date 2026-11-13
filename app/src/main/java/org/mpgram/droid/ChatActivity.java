package org.mpgram.droid;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.SpannedString;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.ClickableSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.text.Editable;
import android.util.TypedValue;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.GridView;
import android.view.LayoutInflater;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import java.io.File;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Просмотр сообщений чата (только чтение). Пузыри в классическом стиле Android 2.x.
 * Сначала грузятся последние сообщения, старые подгружаются при прокрутке вверх.
 */
public class ChatActivity extends BaseActivity {

    public static final String EXTRA_ID = "id";
    public static final String EXTRA_TITLE = "title";
    /** ID топика форума; без него открывается обычный чат. */
    public static final String EXTRA_TOPIC = "topic";
    /** Результат для списка чатов: сессия истекла, нужно войти заново. */
    public static final int RESULT_SESSION_EXPIRED = RESULT_FIRST_USER;

    private static final int PAGE = 40;      // страница при подгрузке более ранних сообщений
    private static final int INITIAL = 20;   // первая страница: меньше данных, быстрее первый показ
    private static final int PREFETCH = 8;   // подгружаем заранее, когда до верха осталось столько строк
    private static final int MAX_ITEMS = 700;  // длиннее список не держим: на 16 МБ куче история иначе кончается OOM
    private static final int NEWER_PREFETCH = 8; // догружаем новее, когда до низа списка осталось столько строк
    private static final long OLDER_RETRY_MS = 4000;
    private static final int ID_REFRESH = 3;
    private static final int[] NAME_COLORS = new int[] {
            0xFFC0392B, 0xFFD35400, 0xFF8E44AD, 0xFF27AE60,
            0xFF16A085, 0xFF2980B9, 0xFFC2185B
    };
    private static final int FLAG = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE;

    // Форматтеры создаются один раз; SimpleDateFormat не потокобезопасен, поэтому методы synchronized
    private static final SimpleDateFormat FMT_TIME = new SimpleDateFormat("HH:mm");
    private static final SimpleDateFormat FMT_DATE = new SimpleDateFormat("dd.MM.yyyy");

    /** Результат одного запроса истории. */
    private static class Page {
        ArrayList<MsgItem> list = new ArrayList<MsgItem>();
        boolean broadcast;
        int rawCount;
        int minId;      // самый старый id в ответе сервера, включая сообщения, которые не показываем
        int maxId;      // самый новый id в ответе сервера (по нему двигаемся вниз при догрузке новее)
        JSONObject raw; // ответ целиком, для кэша на диске
    }

    private static class Holder {
        TextView date;
        TextView service;
        LinearLayout row;
        LinearLayout bubble;
        TextView sender;
        TextView fwd;
        TextView quote;
        LinearLayout media;
        ReactFlow reactBox;      // капсулы реакций и время; без реакций скрыт целиком
        ArrayList<TextView> reactChips = new ArrayList<TextView>();
        TextView flowTime;       // время в конце блока реакций (когда реакции есть)
        String mediaKey = "";    // что сейчас показано в блоке превью: при том же значении его не пересобираем
        int reactLook;           // 0 не задан; 1 капсулы на пузыре; 2 капсулы без пузыря
        boolean senderClickable;
        TextView btnAll;     // "скачать все" у группы из нескольких файлов
        MsgItem allFor;      // для какого сообщения кнопка уже настроена
        TextView body;
        TextView time;
        int look; // 0 не задан; направление (1 входящее, 2 исходящее) * 2 + 1, если без пузыря
        float bodySp = 16f; // текущий размер текста тела (как в msg_item.xml)
        boolean linksOn; // включён ли LinkMovementMethod
    }

    private String appliedLang;
    private Api api;
    private String peerId = "";
    private int topicId;
    private TextView textInfo;
    private ListView listMessages;
    private EditText editMessage;
    private View inputBar;
    private MsgAdapter adapter;
    private final ArrayList<MsgItem> items = new ArrayList<MsgItem>();
    private final Map<String, String> names =
            Collections.synchronizedMap(new HashMap<String, String>());
    private boolean broadcast;
    private boolean loadingInitial;
    private boolean loadingOlder;
    private boolean hasMore = true;
    private boolean positioned;
    private boolean initialShown;   // экран уже заполнен (кэшем или сетью)
    private int oldestRawId;        // от этого id просим более ранние сообщения
    private long olderFailAt;
    private boolean sending;
    private boolean uploading;   // идёт отправка вложений (в сервисе); текст при этом отправлять можно
    private MsgItem pendingItem;
    private MsgItem replyTo;                   // сообщение, на которое отвечаем сейчас
    private MsgItem editingMsg;                // сообщение, которое сейчас редактируем
    private View replyBar;
    private TextView replyText;
    private int listScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
    private ArrayList<MsgItem> deferredOlder;  // старые сообщения, ждут остановки прокрутки
    private int deferredGen;
    private static final int ACT_REPLY = 0, ACT_DELETE = 1, ACT_COPY = 2, ACT_PIN = 3, ACT_EDIT = 4;
    private final View.OnClickListener quoteClick = new View.OnClickListener() {
        public void onClick(View v) {
            Object t = v.getTag();
            if (t instanceof MsgItem && ((MsgItem) t).replyId > 0) {
                jumpTo(((MsgItem) t).replyId);
            }
        }
    };
    // После неудачной отправки того же текста повторяем с тем же числом: сервер отсеет дубль
    private String lastFailedText;
    private String lastFailedSeed;
    private volatile boolean destroyed;
    private float uiScale = 1f;   // множитель интерфейса для больших экранов
    private int nearMargin;
    private int farMargin;
    private static final int MAX_ATTACHMENTS = 10;
    private static final int REQ_GALLERY = 101;
    private static final int REQ_FILE = 102;

    /** Выбранный файл, ожидающий отправки. */
    private static class Attachment {
        Uri uri;
        String name;
        long size;
        String mime;
        boolean asFile; // из проводника: отправляется файлом как есть
    }

    private final ArrayList<Attachment> attachments = new ArrayList<Attachment>();
    private View attachScroll;
    private LinearLayout attachTray;
    private View btnDown;
    private ImageLoader images;
    private View emojiPanel;
    private GridView emojiGrid;
    private LinearLayout emojiTabs;
    private ImageView[] emojiTabViews;
    private boolean emojiBuilt;
    private int emojiTab = -1;                // 0: недавние, 1..8: категории
    private final ArrayList<EmojiMap.Pick> emojiShown = new ArrayList<EmojiMap.Pick>();
    private BaseAdapter emojiAdapter;
    private int groupAfterSend; // сколько файлов только что отправлено пачкой, чтобы склеить их в один бабл
    private int imgMax; // наибольшая сторона превью в пикселях

    // ---------- Закреплённое сообщение ----------
    private View pinnedBar;
    private TextView pinnedCaption;
    private TextView pinnedText;
    /** Все закреплённые сообщения, от новых к старым (плашка их листает). */
    private final ArrayList<MsgItem> pinnedItems = new ArrayList<MsgItem>();
    private int pinnedIndex;        // какой закреп сейчас показан
    private int pinnedCount;        // сколько закрепов всего на сервере (для счётчика)
    private boolean pinnedLoading;
    private boolean pinnedReloadWanted; // закреп сменился, пока шла загрузка: после неё загрузим заново
    private int lastPinEventId = -1;    // id самого нового служебного "закреплено" из виденных (-1: ещё не смотрели)
    private boolean jumpLoading;    // идёт загрузка страницы с закреплённым сообщением

    // ---------- Догрузка новее (после прыжка к закрепу) ----------
    /** Ниже загруженного куска остаётся недогруженное продолжение переписки. */
    private boolean hasMoreNewer;
    private boolean loadingNewer;   // запрос новее уже идёт
    private int newerMark;          // самый новый id, который уже получен от сервера (от него берём следующую порцию)
    private long newerFailAt;
    private int listGen;            // растёт при каждой полной замене списка: устаревшие ответы отбрасываются
    private boolean forceBottom;    // после перезагрузки обязательно встать на последнее сообщение

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.apply(this);
        appliedLang = Lang.get(this);

        api = Api.load(this);
        String id = getIntent().getStringExtra(EXTRA_ID);
        if (!api.isLoggedIn() || id == null || id.length() == 0) {
            finish();
            return;
        }
        peerId = id;
        topicId = getIntent().getIntExtra(EXTRA_TOPIC, 0);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null && title.length() > 0) {
            setTitle(title);
        }

        uiScale = Ui.scale(this);
        nearMargin = Ui.px(this, 6);
        farMargin = Ui.px(this, 56);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        imgMax = Math.min(Ui.px(this, 220), screenW * 65 / 100);
        images = new ImageLoader(this, api);

        setContentView(R.layout.chat);
        setupHeader(true);
        // Шапка чата: аватарка слева, название и число участников по центру
        initChatHeader(api, peerId, getIntent().getStringExtra(EXTRA_TITLE),
                ForumCache.mayBeForum(peerId) && ForumCache.get(this, peerId) == ForumCache.FORUM);
        textInfo = (TextView) findViewById(R.id.textInfo);
        pinnedBar = findViewById(R.id.pinnedBar);
        pinnedCaption = (TextView) findViewById(R.id.pinnedCaption);
        pinnedText = (TextView) findViewById(R.id.pinnedText);
        // Касание плашки прыгает к закреплённому сообщению, крестик прячет до смены закрепа
        pinnedBar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                jumpToPinned();
            }
        });
        findViewById(R.id.btnPinnedClose).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hidePinnedBar();
            }
        });
        listMessages = (ListView) findViewById(R.id.listMessages);
        adapter = new MsgAdapter();
        listMessages.setAdapter(adapter);
        // Кнопку нужно найти до setOnScrollListener: он сразу вызывает onScroll
        btnDown = findViewById(R.id.btnDown);
        listMessages.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                images.setPaused(scrollState == SCROLL_STATE_FLING);
                listScrollState = scrollState;
                if (scrollState == SCROLL_STATE_IDLE) {
                    applyDeferredOlder();
                    checkPrefetch(view.getFirstVisiblePosition());
                }
                // Флинг может доехать до низа окна: тогда достаём продолжение переписки
                checkLoadNewer();
            }

            public void onScroll(AbsListView view, int first, int visibleCount, int totalCount) {
                checkPrefetch(first);
                // Кнопка "вниз" видна, пока последнее сообщение не на экране,
                // а также пока нижняя часть переписки ещё не догружена
                if (btnDown != null) {
                    btnDown.setVisibility(totalCount > 0
                            && (first + visibleCount < totalCount || hasMoreNewer)
                            ? View.VISIBLE : View.GONE);
                }
                checkLoadNewer();
            }
        });
        btnDown.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (hasMoreNewer) {
                    // Список оборван посреди переписки (прыжок к закрепу): настоящий низ берём
                    // заново, как в Telegram, а не листаем всю историю до него
                    forceBottom = true;
                    loadInitial(false);
                } else {
                    scrollToBottom();
                }
            }
        });
        // Касание сообщения с вложением: фото открывается во весь экран, файл предлагается скачать
        listMessages.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < items.size()) {
                    openAttachment(items.get(position));
                }
            }
        });

        // Долгое касание сообщения: меню действий
        listMessages.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                return position >= 0 && position < items.size() && showMessageMenu(items.get(position));
            }
        });

        editMessage = (EditText) findViewById(R.id.editMessage);
        inputBar = findViewById(R.id.inputBar);
        attachScroll = findViewById(R.id.attachScroll);
        attachTray = (LinearLayout) findViewById(R.id.attachTray);
        // Эмодзи: картинки в тексте сообщений и в поле ввода, панель выбора
        EmojiMap.attach(this);
        editMessage.addTextChangedListener(emojiWatcher);
        editMessage.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideEmojiPanel(false); // касание поля вызывает клавиатуру, панель убираем
            }
        });
        emojiPanel = findViewById(R.id.emojiPanel);
        findViewById(R.id.btnEmoji).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleEmojiPanel();
            }
        });
        UploadService.setListener(uploadListener);
        if (UploadService.isActive(peerId)) {
            // Чат открыт посреди отправки: подключаемся к ней
            uploading = true;
            showUploadDialog();
            uploadDialog.begin(UploadService.currentLabel(), true);
        }
        findViewById(R.id.btnAttach).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAttachMenu();
            }
        });
        replyBar = findViewById(R.id.replyBar);
        replyText = (TextView) findViewById(R.id.replyText);
        findViewById(R.id.btnReplyCancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (editingMsg != null) {
                    cancelEdit();
                } else {
                    setReplyTo(null);
                }
            }
        });
        findViewById(R.id.btnSend).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (editingMsg != null) {
                    sendEdit();
                } else if (attachments.isEmpty()) {
                    sendMessage();
                } else {
                    sendAttachments();
                }
            }
        });
        // Когда открывается клавиатура, показываем конец переписки
        editMessage.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    listMessages.postDelayed(new Runnable() {
                        public void run() {
                            if (!destroyed && !items.isEmpty()) {
                                listMessages.setSelection(items.size() - 1);
                            }
                        }
                    }, 300);
                }
            }
        });

        loadInitial(true);
        loadPinned();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        UploadService.clearListener(uploadListener); // отправка продолжится в сервисе
        if (images != null) {
            images.shutdown();
        }
        super.onDestroy();
    }

    // ---------- Загрузка ----------

    private Page loadPage(int offsetId, int limit) throws Exception {
        return loadPage(offsetId, limit, 0);
    }

    /**
     * Страница истории. addOffset < 0 сдвигает окно к более новым: с offset_id = X + 1
     * и add_offset = -N в ответе будет N сообщений новее X, само X и более старые до limit.
     */
    private Page loadPage(int offsetId, int limit, int addOffset) throws Exception {
        try {
            return loadPageImpl(offsetId, limit, addOffset);
        } catch (OutOfMemoryError e) {
            // На слабом телефоне разбор большой страницы может не поместиться в кучу:
            // показываем обычную ошибку загрузки вместо падения приложения
            throw new java.io.IOException("out of memory");
        }
    }

    private Page loadPageImpl(int offsetId, int limit, int addOffset) throws Exception {
        String offset = offsetId > 0 ? String.valueOf(offsetId) : null;
        String add = addOffset != 0 ? String.valueOf(addOffset) : null;
        JSONObject res;
        if (topicId > 0) {
            // Сообщения топика форума: в getHistory такого параметра нет, берём searchMessages без запроса
            res = api.call("searchMessages",
                    "peer", peerId,
                    "top_msg_id", String.valueOf(topicId),
                    "limit", String.valueOf(limit),
                    "offset_id", offset,
                    "add_offset", add,
                    "media", "1",
                    "fields", "messages,users,chats");
        } else {
            res = api.call("getHistory",
                    "peer", peerId,
                    "limit", String.valueOf(limit),
                    "offset_id", offset,
                    "add_offset", add,
                    "media", "1",
                    "fields", "messages,users,chats");
        }
        Page page = parsePage(res);
        page.raw = res;
        return page;
    }

    /** Подгрузка ранних сообщений, когда до верха списка осталось меньше PREFETCH строк. */
    private void checkPrefetch(int first) {
        if (positioned && first <= PREFETCH && hasMore && !loadingOlder && (deferredOlder == null || deferredGen != listGen)
                && !loadingInitial && !items.isEmpty()
                && System.currentTimeMillis() - olderFailAt > OLDER_RETRY_MS) {
            loadOlder();
        }
    }

    /**
     * Первая страница. С кэшем на диске чат показывается сразу, а сеть догружает свежее:
     * когда она отвечает, список заменяется, а положение прокрутки сохраняется.
     */
    private void loadInitial(final boolean useCache) {
        if (loadingInitial) {
            return;
        }
        loadingInitial = true;
        positioned = false;
        hasMore = true;
        initialShown = false;
        textInfo.setTextColor(0xFFAAAAAA);
        textInfo.setText(R.string.main_loading);
        textInfo.setVisibility(View.VISIBLE);

        if (useCache) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        JSONObject cached = PageCache.read(ChatActivity.this, api.sessionHash(),
                                peerId, topicId);
                        if (cached == null) {
                            return;
                        }
                        final Page cp = parsePage(cached);
                        if (cp.list.isEmpty()) {
                            return;
                        }
                        runOnUiThread(new Runnable() {
                            public void run() {
                                // Сеть могла ответить раньше, чем разобрался кэш
                                if (!destroyed && !initialShown) {
                                    applyInitial(cp);
                                }
                            }
                        });
                    } catch (Throwable e) {
                        // Кэш не обязателен (в том числе при нехватке памяти)
                    }
                }
            }).start();
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    final Page page = loadPage(0, INITIAL);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            loadingInitial = false;
                            applyInitial(page);
                        }
                    });
                    PageCache.write(ChatActivity.this, api.sessionHash(), peerId, topicId, page.raw);
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            loadingInitial = false;
                            if (Errors.isSessionError(e)) {
                                Api.clearSession(ChatActivity.this);
                                setResult(RESULT_SESSION_EXPIRED);
                                finish();
                                return;
                            }
                            // Если на экране уже кэш, он остаётся, а ошибка показывается над ним
                            textInfo.setTextColor(0xFFFF8080);
                            textInfo.setText(Errors.text(ChatActivity.this, e));
                            textInfo.setVisibility(View.VISIBLE);
                            if (initialShown) {
                                positionedAfterLayout();
                            }
                        }
                    });
                }
            }
        }).start();
    }

    /** Показывает первую страницу (из кэша или с сервера). Только из UI-потока. */
    private void applyInitial(Page page) {
        // Если экран уже заполнен кэшем, запоминаем, где пользователь, чтобы не сбросить прокрутку
        boolean replacing = initialShown && !items.isEmpty();
        boolean atBottom = true;
        int anchorId = 0;
        int anchorTop = 0;
        if (replacing) {
            atBottom = listMessages.getLastVisiblePosition() >= items.size() - 2;
            int fp = listMessages.getFirstVisiblePosition();
            if (!atBottom && fp >= 0 && fp < items.size()) {
                anchorId = items.get(fp).id;
                View v = listMessages.getChildAt(0);
                anchorTop = v == null ? 0 : v.getTop();
            }
        }

        if (forceBottom) {
            atBottom = true;
            forceBottom = false;
        }
        listGen++;

        broadcast = page.broadcast;
        // В каналах-трансляциях писать могут только админы, поле ввода там не показываем
        inputBar.setVisibility(page.broadcast ? View.GONE : View.VISIBLE);
        // Список заново начинается с последних сообщений: догружать новее нечего
        hasMoreNewer = false;
        pendingItem = null;
        items.clear();
        items.addAll(page.list);
        notePinEvents(page.list);
        oldestRawId = page.minId > 0 ? page.minId : (items.isEmpty() ? 0 : items.get(0).id);
        hasMore = page.rawCount >= INITIAL && oldestRawId > 1;
        recomputeDates();
        adapter.notifyDataSetChanged();
        if (items.isEmpty()) {
            textInfo.setTextColor(0xFFAAAAAA);
            textInfo.setText(R.string.chat_empty);
            textInfo.setVisibility(View.VISIBLE);
        } else {
            textInfo.setVisibility(View.GONE);
            int target = -1;
            if (!atBottom && anchorId > 0) {
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).id == anchorId) {
                        target = i;
                        break;
                    }
                }
            }
            if (target >= 0) {
                listMessages.setSelectionFromTop(target, anchorTop);
            } else {
                listMessages.setSelection(items.size() - 1);
            }
        }
        initialShown = true;
        positionedAfterLayout();
    }

    /** Отложенная порция старых сообщений: вставляем, когда список остановился. */
    private void applyDeferredOlder() {
        if (deferredOlder == null) {
            return;
        }
        ArrayList<MsgItem> d = deferredOlder;
        deferredOlder = null;
        if (!destroyed && deferredGen == listGen) {
            insertOlder(d);
        }
    }

    /**
     * Вставляет старые сообщения сверху и возвращает то сообщение, что было у верха экрана.
     * Якорь берём в момент вставки, а не в момент запроса: за время загрузки экран мог уехать.
     */
    private void insertOlder(ArrayList<MsgItem> older) {
        int first = listMessages.getFirstVisiblePosition();
        View fv = listMessages.getChildAt(0);
        int top = fv == null ? 0 : fv.getTop();
        int anchorId = first >= 0 && first < items.size() ? items.get(first).id : -1;
        items.addAll(0, older);
        trimNewest(older.size());
        recomputeDates();
        adapter.notifyDataSetChanged();
        int idx = -1;
        for (int i = 0; anchorId > 0 && i < items.size(); i++) {
            if (hasMessage(items.get(i), anchorId)) {
                idx = i;
                break;
            }
        }
        listMessages.setSelectionFromTop(idx >= 0 ? idx : first + older.size(), top);
    }

    /**
     * Подгрузку разрешаем только после раскладки списка: иначе первый onScroll (first == 0)
     * запустил бы её сразу при открытии. Заодно проверяем, не короче ли переписка экрана.
     */
    private void positionedAfterLayout() {
        listMessages.post(new Runnable() {
            public void run() {
                if (destroyed) {
                    return;
                }
                positioned = true;
                if (visible) {
                    startPolling();
                }
                checkPrefetch(listMessages.getFirstVisiblePosition());
            }
        });
    }

    private void loadOlder() {
        if (items.isEmpty() || oldestRawId <= 0) {
            return;
        }
        loadingOlder = true;
        final int offset = oldestRawId;
        final int gen = listGen;
        // Запоминаем положение списка, чтобы после добавления сверху экран не "прыгнул"

        new Thread(new Runnable() {
            public void run() {
                try {
                    final Page page = loadPage(offset, PAGE);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            loadingOlder = false;
                            if (gen != listGen) {
                                return; // список заменили (прыжок или перезагрузка): ответ устарел
                            }
                            // Двигаемся по id из ответа сервера, а не по показанным сообщениям: служебные
                            // и пропущенные при разборе тоже сдвигают границу, и подгрузка не застревает.
                            boolean progress = page.rawCount > 0 && page.minId > 0 && page.minId < offset;
                            if (progress) {
                                oldestRawId = page.minId;
                            }
                            hasMore = progress && page.minId > 1;
                            // Защита от дублей: оставляем только то, что старше уже загруженного
                            ArrayList<MsgItem> older = new ArrayList<MsgItem>();
                            for (int i = 0; i < page.list.size(); i++) {
                                if (page.list.get(i).id < offset) {
                                    older.add(page.list.get(i));
                                }
                            }
                            if (older.isEmpty()) {
                                checkPrefetch(listMessages.getFirstVisiblePosition());
                                return;
                            }
                            if (listScrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                                // Список листают: сейчас вставлять сверху нельзя, позиция поедет. Ждём остановки.
                                deferredOlder = older;
                                deferredGen = gen;
                                return;
                            }
                            insertOlder(older);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            loadingOlder = false;
                            if (Errors.isSessionError(e)) {
                                Api.clearSession(ChatActivity.this);
                                setResult(RESULT_SESSION_EXPIRED);
                                finish();
                                return;
                            }
                            // Повторим при следующей прокрутке, но не чаще раза в несколько секунд
                            olderFailAt = System.currentTimeMillis();
                            Toast.makeText(ChatActivity.this,
                                    getString(R.string.err_load_older) + ": "
                                            + Errors.text(ChatActivity.this, e),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    // ---------- Закреплённое сообщение ----------

    /**
     * Все закреплённые сообщения: searchMessages с фильтром Pinned отдаёт их от новых
     * к старым (лимит 10). Ошибку молча игнорируем: плашка не критична для работы чата.
     */
    private void loadPinned() {
        if (!api.isLoggedIn()) {
            return;
        }
        if (pinnedLoading) {
            pinnedReloadWanted = true; // текущий ответ мог выйти до смены закрепа
            return;
        }
        pinnedLoading = true;
        new Thread(new Runnable() {
            public void run() {
                final ArrayList<MsgItem> list = new ArrayList<MsgItem>();
                int total = 0;
                try {
                    JSONObject res = api.call("searchMessages",
                            "peer", peerId,
                            "filter", "Pinned",
                            "limit", "10",
                            "media", "1",
                            "fields", "messages,users,chats",
                            // В топике форума закреп ищем внутри самого топика
                            "top_msg_id", topicId > 0 ? String.valueOf(topicId) : null);
                    total = res.optInt("count", 0);
                    fillNames(res);
                    JSONArray arr = res.optJSONArray("messages");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject m = arr.optJSONObject(i);
                            if (m == null) {
                                continue;
                            }
                            MsgItem it = toItem(m, false);
                            // Служебные закрепы в плашке не показываем
                            if (it != null && it.id > 0 && !it.service) {
                                list.add(it);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Плашка не обязательна: без закрепа просто не показываем
                }
                final int count = total;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pinnedLoading = false;
                        if (destroyed) {
                            return;
                        }
                        pinnedItems.clear();
                        pinnedItems.addAll(list);
                        pinnedIndex = 0;
                        pinnedCount = count;
                        updatePinnedBar();
                        if (pinnedReloadWanted) {
                            pinnedReloadWanted = false;
                            loadPinned();
                        }
                    }
                });
            }
        }).start();
    }

    /** Наполняет names из ответа, как parsePage, чтобы превью знало отправителя. */
    private void fillNames(JSONObject res) {
        JSONObject users = res.optJSONObject("users");
        if (users != null) {
            Iterator<?> keys = users.keys();
            while (keys.hasNext()) {
                String k = (String) keys.next();
                String n = nameOf(users.optJSONObject(k));
                if (n.length() > 0) {
                    names.put(k, n);
                }
            }
        }
        JSONObject chats = res.optJSONObject("chats");
        if (chats != null) {
            Iterator<?> keys = chats.keys();
            while (keys.hasNext()) {
                String k = (String) keys.next();
                String n = Api.str(chats.optJSONObject(k), "t");
                if (k.startsWith("-") && n.length() > 0) {
                    names.put(k, n);
                }
            }
        }
    }

    private MsgItem pinnedShown() {
        if (pinnedIndex < 0 || pinnedIndex >= pinnedItems.size()) {
            return null;
        }
        return pinnedItems.get(pinnedIndex);
    }

    /**
     * Показывает или прячет плашку. Если закрепов несколько: в заголовке счётчик,
     * тап по плашке прыгает к показанному закрепу и показывает следующий по кругу.
     */
    private void updatePinnedBar() {
        MsgItem shown = pinnedShown();
        if (shown == null) {
            pinnedBar.setVisibility(View.GONE);
            return;
        }
        // Плашку скрывают целиком: она возвращается, только когда появится новый закреп
        // (мерило: id самого нового из закреплённых)
        String hidden = getSharedPreferences("pinned", MODE_PRIVATE)
                .getString("hide_" + peerId, null);
        try {
            if (hidden != null && Integer.parseInt(hidden) == pinnedItems.get(0).id) {
                pinnedBar.setVisibility(View.GONE);
                return;
            }
        } catch (NumberFormatException e) {
            // Битое значение игнорируем и показываем плашку
        }
        // Без подписи (например, фото) показываем название вложения вместо пустой строки
        CharSequence content = shown.body != null && shown.body.length() > 0 ? shown.body : shown.mediaLabel;
        if (shown.sender.length() > 0) {
            SpannableStringBuilder pv = new SpannableStringBuilder();
            pv.append(shown.sender).append(": ");
            if (content != null) {
                pv.append(content.toString()); // без картинок бабла: они другого размера
            }
            EmojiMap.setText(pinnedText, pv);
        } else {
            EmojiMap.setText(pinnedText, content == null ? "" : content.toString());
        }
        // Листаем по кругу только то, что загружено; если закрепов на сервере больше, ставим "+"
        int n = pinnedItems.size();
        pinnedCaption.setText(n > 1 || pinnedCount > n
                ? getString(R.string.pinned_title) + " " + (pinnedIndex + 1) + "/" + n
                        + (pinnedCount > n ? "+" : "")
                : getString(R.string.pinned_title));
        pinnedBar.setVisibility(View.VISIBLE);
    }

    /** Крестик: прячет плашку до появления другого закреплённого сообщения. */
    private void hidePinnedBar() {
        SharedPreferences.Editor e = getSharedPreferences("pinned", MODE_PRIVATE).edit();
        e.putString("hide_" + peerId, String.valueOf(pinnedItems.isEmpty() ? 0 : pinnedItems.get(0).id));
        Api.apply(e);
        pinnedBar.setVisibility(View.GONE);
    }

    /** Касание плашки: прыжок к закреплённому сообщению, затем показываем следующий. */
    private void jumpToPinned() {
        MsgItem shown = pinnedShown();
        if (shown == null || sending || pendingItem != null || jumpLoading || loadingNewer) {
            return;
        }
        final int target = shown.id;
        // Следующий закреп покажем после запуска прыжка, как в настоящем Telegram
        if (pinnedItems.size() > 1) {
            pinnedIndex = (pinnedIndex + 1) % pinnedItems.size();
            updatePinnedBar();
        }
        jumpTo(target);
    }

    /** Переходит к сообщению: если оно уже в списке, прокручивает, иначе грузит окно вокруг него. */
    private void jumpTo(final int target) {
        if (goToMessage(target)) {
            return;
        }
        if (sending || pendingItem != null || jumpLoading || loadingNewer) {
            return;
        }
        jumpLoading = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    // Окно вокруг закрепа: половина страницы новее него, он сам и более ранние
                    final Page page = loadPage(target + 1, PAGE, -(PAGE / 2));
                    runOnUiThread(new Runnable() {
                        public void run() {
                            jumpLoading = false;
                            if (destroyed || page.list.isEmpty()) {
                                return;
                            }
                            items.clear();
                            items.addAll(page.list);
                            pendingItem = null;
                            listGen++;
                            oldestRawId = page.minId > 0 ? page.minId
                                    : (items.isEmpty() ? 0 : items.get(0).id);
                            // Окно могло упереться в верх переписки, поэтому судим по id, а не по размеру
                            hasMore = oldestRawId > 1;
                            // Ниже окна переписка продолжается; будет ли ещё что-то, выяснится при прокрутке
                            hasMoreNewer = true;
                            newerMark = Math.max(page.maxId, newestLoadedId());
                            recomputeDates();
                            adapter.notifyDataSetChanged();
                            textInfo.setVisibility(View.GONE);
                            if (!goToMessage(target)) {
                                listMessages.setSelection(Math.max(0, items.size() / 2));
                            }
                            listMessages.post(new Runnable() {
                                public void run() {
                                    checkLoadNewer();
                                }
                            });
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            jumpLoading = false;
                            if (destroyed) {
                                return;
                            }
                            if (Errors.isSessionError(e)) {
                                Api.clearSession(ChatActivity.this);
                                setResult(RESULT_SESSION_EXPIRED);
                                finish();
                                return;
                            }
                            Toast.makeText(ChatActivity.this,
                                    Errors.text(ChatActivity.this, e),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    /** Прокручивает список к сообщению с таким id. false, если его в списке нет. */
    private boolean goToMessage(int msgId) {
        for (int i = 0; i < items.size(); i++) {
            if (hasMessage(items.get(i), msgId)) {
                stopFling();
                listMessages.setSelection(i);
                return true;
            }
        }
        return false;
    }

    // ---------- Догрузка свежих сообщений (мост после прыжка к закрепу) ----------

    /** Самый новый настоящий id в списке: заглушка отправляемого (id MAX_VALUE) не в счёт. */
    private int newestLoadedId() {
        int max = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            MsgItem it = items.get(i);
            if (!it.pending && it.id > max) {
                max = it.id;
            }
        }
        return max;
    }

    /**
     * Список слишком длинный после подгрузки вверх: выбрасываем самые новые сообщения далеко
     * внизу. Они останутся доступными: переписка станет "оборванной снизу" и подтянется
     * тем же способом, что после прыжка к закрепу. shift: сколько строк только что добавили сверху.
     */
    private void trimNewest(int shift) {
        int excess = items.size() - MAX_ITEMS;
        if (excess <= 0 || pendingItem != null) {
            return;
        }
        int keep = items.size() - excess;
        // Режем, только если до экрана ещё далеко
        if (listMessages.getLastVisiblePosition() + shift >= keep - PAGE) {
            return;
        }
        items.subList(keep, items.size()).clear();
        hasMoreNewer = true;
        newerMark = newestLoadedId();
        newerFailAt = 0;
    }

    /**
     * Список слишком длинный после подгрузки вниз: выбрасываем самые старые сообщения далеко
     * вверху. Возвращает {позиция, отступ} для восстановления прокрутки или null, если не резали.
     */
    private int[] trimOldest() {
        int excess = items.size() - MAX_ITEMS;
        if (excess <= 0) {
            return null;
        }
        int first = listMessages.getFirstVisiblePosition();
        if (first < excess + PAGE) {
            return null; // пользователь ещё близко к верху
        }
        View v = listMessages.getChildAt(0);
        int top = v == null ? 0 : v.getTop();
        items.subList(0, excess).clear();
        MsgItem head = items.get(0);
        // Следующая подгрузка вверх начнётся с самой ранней части первого бабла
        oldestRawId = head.parts != null && !head.parts.isEmpty() ? head.parts.get(0).id : head.id;
        hasMore = oldestRawId > 1;
        return new int[] {first - excess, top};
    }

    /** Запрос новее, когда список докручен почти до низа загруженного окна. */
    private void checkLoadNewer() {
        if (!positioned || loadingInitial || jumpLoading || loadingNewer || !hasMoreNewer
                || items.isEmpty()
                || System.currentTimeMillis() - newerFailAt < OLDER_RETRY_MS) {
            return;
        }
        if (listMessages.getLastVisiblePosition() < items.size() - 1 - NEWER_PREFETCH) {
            return;
        }
        loadNewer();
    }

    /**
     * Следующая порция сообщений новее уже загруженных, как это делает сам Telegram:
     * offset_id = последний полученный id + 1 и add_offset = -PAGE дают PAGE сообщений
     * сразу после него. Так окно вокруг закрепа растёт вниз по хронологии, и качается
     * только то, до чего пользователь дошёл. Кнопка "вниз" вместо этого берёт настоящий низ.
     */
    private void loadNewer() {
        if (loadingNewer || loadingInitial || jumpLoading || !hasMoreNewer || items.isEmpty()
                || newerMark <= 0) {
            return;
        }
        final int after = newerMark;
        final int gen = listGen;
        loadingNewer = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    final Page page = loadPage(after + 1, PAGE, -PAGE);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            loadingNewer = false;
                            if (!destroyed && gen == listGen) {
                                commitNewer(page, after);
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            loadingNewer = false;
                            if (destroyed || gen != listGen) {
                                return;
                            }
                            if (Errors.isSessionError(e)) {
                                Api.clearSession(ChatActivity.this);
                                setResult(RESULT_SESSION_EXPIRED);
                                finish();
                                return;
                            }
                            // Повторим при следующей прокрутке, но не чаще раза в несколько секунд
                            newerFailAt = System.currentTimeMillis();
                            Toast.makeText(ChatActivity.this,
                                    getString(R.string.err_load_newer) + ": "
                                            + Errors.text(ChatActivity.this, e),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    /** Дописывает порцию новее в конец списка. Только из UI-потока. */
    private void commitNewer(Page page, int after) {
        // Конец переписки: сервер отдал меньше страницы или ничего новее нашей границы
        boolean progress = page.rawCount > 0 && page.maxId > after;
        if (progress) {
            newerMark = page.maxId;
        }
        if (!progress || page.rawCount < PAGE) {
            hasMoreNewer = false;
        }
        // Защита от дублей: берём только то, что новее уже загруженного
        ArrayList<MsgItem> chunk = new ArrayList<MsgItem>();
        for (int i = 0; i < page.list.size(); i++) {
            MsgItem m = page.list.get(i);
            if (m.id > after && !containsId(items, m.id)) {
                chunk.add(m);
            }
        }
        if (!chunk.isEmpty()) {
            // Вставляем перед заглушкой отправляемого сообщения, если она есть
            int p = pendingItem != null && items.contains(pendingItem) ? items.indexOf(pendingItem) : items.size();
            // Альбом, разрезанный границей порций, склеиваем обратно
            if (p > 0) {
                ArrayList<MsgItem> seam = new ArrayList<MsgItem>();
                seam.add(items.get(p - 1));
                seam.addAll(chunk);
                ArrayList<MsgItem> merged = groupItems(seam);
                chunk = new ArrayList<MsgItem>(merged.subList(1, merged.size()));
            }
            items.addAll(p, chunk);
            int[] anchor = trimOldest();
            recomputeDates();
            notePinEvents(chunk);
            adapter.notifyDataSetChanged();
            if (anchor != null) {
                listMessages.setSelectionFromTop(anchor[0], anchor[1]);
            }
        }
        // Если после вставки всё ещё у низа (короткая порция), берём следующую сразу
        checkLoadNewer();
    }

    /** Есть ли в списке сообщение с таким id (защита от дублей при догрузке). */
    private static boolean containsId(ArrayList<MsgItem> list, int id) {
        for (int i = 0; i < list.size(); i++) {
            if (hasMessage(list.get(i), id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Этот элемент списка и есть сообщение с таким id или содержит его среди частей склеенного
     * альбома (у склеенного бабла id равен id последней части).
     */
    private static boolean hasMessage(MsgItem m, int id) {
        if (m.id == id) {
            return true;
        }
        if (m.parts != null) {
            for (int i = 0; i < m.parts.size(); i++) {
                if (m.parts.get(i).id == id) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Следит за служебными "закреплено": если среди загруженных сообщений появилось новое,
     * плашку закрепа нужно перечитать. Первый вызов только запоминает текущее состояние.
     */
    private void notePinEvents(ArrayList<MsgItem> list) {
        int max = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).pinEvent && list.get(i).id > max) {
                max = list.get(i).id;
            }
        }
        if (lastPinEventId < 0) {
            lastPinEventId = max;
        } else if (max > lastPinEventId) {
            lastPinEventId = max;
            loadPinned();
        }
    }

    // ---------- Автообновление, "печатает" ----------

    private volatile boolean polling;
    private volatile int pollGen; // меняется в UI-потоке, читается циклом опроса
    private volatile int newestId; // самое новое загруженное сообщение: старее него обновления не нужны
    private boolean pausedOnce;
    private boolean visible; // экран на виду (между onResume и onPause)
    private final Runnable clearTyping = new Runnable() {
        public void run() {
            setHeaderTyping(null);
        }
    };

    private void startPolling() {
        if (polling || destroyed) {
            return;
        }
        polling = true;
        final int gen = ++pollGen;
        new Thread(new Runnable() {
            public void run() {
                pollLoop(gen);
            }
        }).start();
    }

    private void stopPolling() {
        polling = false;
        pollGen++; // запрос в пути ещё вернётся, но его результат уже не нужен
    }

    /**
     * Длинный опрос методом updates по этому чату (до 8 секунд на запрос): приходят новые
     * сообщения, правки, удаления и "печатает". Работает только пока экран на виду.
     */
    private void pollLoop(int gen) {
        int offset = 0;
        boolean haveOffset = false;
        while (polling && gen == pollGen && !destroyed) {
            try {
                if (!haveOffset) {
                    // Начинаем после последнего обновления, чтобы не ловить старые
                    JSONObject last = api.call("getLastUpdate").optJSONObject("res");
                    offset = last != null && last.has("update_id") ? last.optInt("update_id") + 1 : 0;
                    haveOffset = true;
                    continue;
                }
                JSONObject r = api.call("updates",
                        "offset", String.valueOf(offset),
                        "peer", peerId,
                        "timeout", "8",
                        "media", "1",
                        "read", "1",
                        "message", String.valueOf(newestId),
                        "top_msg", topicId > 0 ? String.valueOf(topicId) : null);
                final JSONArray arr = r.optJSONArray("res");
                if (gen != pollGen) {
                    continue;
                }
                if (arr == null) {
                    Thread.sleep(1000); // ответ без res: не долбим сервер без паузы
                    continue;
                }
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject u = arr.optJSONObject(i);
                    if (u != null && u.has("update_id")) {
                        offset = Math.max(offset, u.optInt("update_id") + 1);
                    }
                }
                if (arr.length() > 0) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (!destroyed) {
                                handleUpdates(arr);
                            }
                        }
                    });
                }
            } catch (Exception e) {
                if (Errors.isSessionError(e)) {
                    return;
                }
                try {
                    Thread.sleep(3000); // сеть пропала: пробуем снова не чаще раза в 3 секунды
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /** Разбор пачки обновлений в UI-потоке. */
    private void handleUpdates(JSONArray arr) {
        boolean newMessage = false;
        boolean changed = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject u = arr.optJSONObject(i);
            JSONObject up = u == null ? null : u.optJSONObject("update");
            if (up == null) {
                continue;
            }
            String type = Api.str(up, "_");
            if ("updateNewMessage".equals(type) || "updateNewChannelMessage".equals(type)) {
                newMessage = true;
                listMessages.removeCallbacks(clearTyping);
                setHeaderTyping(null); // человек отправил сообщение, значит печатать перестал
            } else if ("updateEditMessage".equals(type) || "updateEditChannelMessage".equals(type)) {
                changed |= applyEdit(up.optJSONObject("message"));
            } else if ("updateDeleteChannelMessages".equals(type)) {
                changed |= applyDelete(up.optJSONArray("messages"));
            } else if (type.endsWith("UserTyping")) {
                showTyping(up);
            }
        }
        if (changed) {
            recomputeDates();
            adapter.notifyDataSetChanged();
        }
        if (newMessage && !hasMoreNewer) {
            refreshAfterSend(0); // подтягиваем новые сообщения вместе с именами и вложениями
        }
    }

    /** Заменяет показанное сообщение отредактированным (склеенные альбомы не трогаем). */
    private boolean applyEdit(JSONObject msg) {
        if (msg == null) {
            return false;
        }
        MsgItem n = toItem(msg, broadcast);
        if (n == null) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            MsgItem old = items.get(i);
            if (old.id == n.id && old.parts == null) {
                if (n.sender.length() == 0) {
                    n.sender = old.sender;
                    n.senderColor = old.senderColor;
                    n.senderId = old.senderId;
                }
                items.set(i, n);
                return true;
            }
        }
        return false;
    }

    private boolean applyDelete(JSONArray ids) {
        if (ids == null) {
            return false;
        }
        boolean any = false;
        for (int k = 0; k < ids.length(); k++) {
            int id = ids.optInt(k);
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id == id && items.get(i) != pendingItem) {
                    items.remove(i);
                    any = true;
                    break;
                }
            }
        }
        return any;
    }

    /** "печатает…" под названием; сбрасывается через 6 секунд без новых сигналов. */
    private void showTyping(JSONObject up) {
        JSONObject action = up.optJSONObject("action");
        String a = action == null ? "" : Api.str(action, "_");
        listMessages.removeCallbacks(clearTyping);
        if (a.indexOf("Cancel") >= 0) {
            setHeaderTyping(null);
            return;
        }
        String who = "";
        if (peerId.startsWith("-")) {
            who = nameFor(Api.str(up, "from_id")); // в группе пишем, кто именно
        }
        boolean named = who.length() > 0;
        String text;
        if (a.indexOf("Record") >= 0) {
            text = named ? getString(R.string.voice_other, who) : getString(R.string.voice_self);
        } else if (a.indexOf("Upload") >= 0) {
            text = named ? getString(R.string.file_other, who) : getString(R.string.file_self);
        } else {
            text = named ? getString(R.string.typing_other, who) : getString(R.string.typing_self);
        }
        setHeaderTyping(text);
        listMessages.postDelayed(clearTyping, 6000);
    }

    // ---------- Эмодзи ----------

    /**
     * Поле ввода: эмодзи показываются картинками, а при удалении убираются целиком (иначе от
     * пары суррогатов остался бы "хвост"). Сам текст остаётся обычным Юникодом.
     */
    private final TextWatcher emojiWatcher = new TextWatcher() {
        private int delStart = -1;
        private int delEnd = -1;
        private boolean busy;

        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            delStart = -1;
            if (after == 0 && count == 1 && s instanceof Spanned) {
                Spanned sp = (Spanned) s;
                EmojiMap.EmojiSpan[] spans = sp.getSpans(start, start + 1, EmojiMap.EmojiSpan.class);
                if (spans.length > 0) {
                    delStart = sp.getSpanStart(spans[0]);
                    delEnd = sp.getSpanEnd(spans[0]);
                }
            }
        }

        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        public void afterTextChanged(Editable e) {
            if (busy) {
                return;
            }
            busy = true;
            try {
                if (delStart >= 0) {
                    // Одна позиция уже удалена: стираем остаток эмодзи
                    int end = Math.min(delEnd - 1, e.length());
                    if (end > delStart) {
                        e.delete(delStart, end);
                    }
                    delStart = -1;
                }
                EmojiMap.EmojiSpan[] old = e.getSpans(0, e.length(), EmojiMap.EmojiSpan.class);
                if (old.length == 0 && !EmojiMap.maybeHas(e)) {
                    return; // обычный текст: на каждое нажатие клавиши ничего считать не нужно
                }
                for (int i = 0; i < old.length; i++) {
                    e.removeSpan(old[i]);
                }
                EmojiMap.addSpans(e);
            } finally {
                busy = false;
            }
        }
    };

    private void toggleEmojiPanel() {
        if (emojiPanel.getVisibility() == View.VISIBLE) {
            hideEmojiPanel(true);
        } else {
            showEmojiPanel();
        }
    }

    private void showEmojiPanel() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(editMessage.getWindowToken(), 0);
        // Клавиатуре под стать, но чтобы на 480 пикселях высоты в чате оставалось место для сообщений
        emojiPanel.getLayoutParams().height = Math.max(dp(150), getResources().getDisplayMetrics().heightPixels * 34 / 100);
        if (!emojiBuilt) {
            buildEmojiPanel();
        }
        emojiPanel.setVisibility(View.VISIBLE);
        if (emojiTab < 0) {
            selectEmojiTab(EmojiMap.recent(this).isEmpty() ? 1 : 0);
        }
        scrollToBottom();
    }

    /** Убирает панель; showKeyboard возвращает клавиатуру (кнопка-смайлик нажата повторно). */
    private void hideEmojiPanel(boolean showKeyboard) {
        if (emojiPanel == null || emojiPanel.getVisibility() != View.VISIBLE) {
            return;
        }
        emojiPanel.setVisibility(View.GONE);
        if (showKeyboard) {
            editMessage.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(editMessage, 0);
        }
    }

    /** Вкладки: недавние и 8 категорий (значки это сами эмодзи), справа "стереть". */
    private void buildEmojiPanel() {
        emojiBuilt = true;
        emojiTabs = (LinearLayout) findViewById(R.id.emojiTabs);
        emojiGrid = (GridView) findViewById(R.id.emojiGrid);
        emojiGrid.setColumnWidth(dp(44));
        String[] icons = {"1F552", "1F600", "1F43B", "1F354", "26BD", "1F697", "1F4A1", "2764", "1F3C1"};
        emojiTabViews = new ImageView[icons.length];
        for (int i = 0; i < icons.length; i++) {
            final int tab = i;
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(5), dp(5), dp(5), dp(5));
            int id = EmojiMap.idOf(icons[i]);
            if (id >= 0) {
                iv.setImageBitmap(EmojiMap.bitmap(id));
            }
            iv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectEmojiTab(tab);
                }
            });
            emojiTabViews[i] = iv;
            emojiTabs.addView(iv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.FILL_PARENT, 1f));
        }
        ImageButton del = new ImageButton(this);
        del.setImageResource(R.drawable.ic_backspace_w);
        del.setBackgroundColor(0x00000000);
        if (Api.sdkLevel() >= 4) {
            del.setContentDescription(getString(R.string.emoji_delete));
        }
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Обычное нажатие "забой" в поле ввода
                long t = SystemClock.uptimeMillis();
                editMessage.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0));
                editMessage.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0));
            }
        });
        emojiTabs.addView(del, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.FILL_PARENT, 1f));

        emojiAdapter = new BaseAdapter() {
            public int getCount() {
                return emojiShown.size();
            }

            public Object getItem(int position) {
                return emojiShown.get(position);
            }

            public long getItemId(int position) {
                return position;
            }

            public View getView(int position, View convertView, ViewGroup parent) {
                ImageView iv = (ImageView) convertView;
                if (iv == null) {
                    iv = new ImageView(ChatActivity.this);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iv.setPadding(dp(4), dp(4), dp(4), dp(4));
                    iv.setLayoutParams(new AbsListView.LayoutParams(dp(44), dp(44)));
                }
                iv.setImageBitmap(EmojiMap.bitmap(emojiShown.get(position).id));
                return iv;
            }
        };
        emojiGrid.setAdapter(emojiAdapter);
        emojiGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                insertEmoji(emojiShown.get(position));
            }
        });
    }

    private void selectEmojiTab(int tab) {
        emojiTab = tab;
        emojiShown.clear();
        emojiShown.addAll(tab == 0 ? EmojiMap.recent(this) : EmojiMap.category(tab - 1));
        EmojiMap.prefetch(emojiShown, 48);
        emojiAdapter.notifyDataSetChanged();
        emojiGrid.setSelection(0);
        for (int i = 0; i < emojiTabViews.length; i++) {
            emojiTabViews[i].setBackgroundColor(i == tab ? 0x33FFFFFF : 0x00000000);
        }
    }

    /** Вставляет эмодзи в позицию курсора (или поверх выделения). */
    private void insertEmoji(EmojiMap.Pick p) {
        int a = Math.max(0, editMessage.getSelectionStart());
        int b = Math.max(0, editMessage.getSelectionEnd());
        if (a > b) {
            int t = a;
            a = b;
            b = t;
        }
        editMessage.getText().replace(a, b, p.text);
        int pos = Math.min(a + p.text.length(), editMessage.getText().length());
        editMessage.setSelection(pos);
        EmojiMap.addRecent(this, p);
    }

    /** "Назад" при открытой панели эмодзи только закрывает её. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && emojiPanel != null
                && emojiPanel.getVisibility() == View.VISIBLE) {
            hideEmojiPanel(false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------- Вложения от себя ----------

    /** Меню прикрепления: галерея (фото и видео) или проводник по файловой системе. */
    private void showAttachMenu() {
        if (uploading) {
            return;
        }
        final int[] icons = {R.drawable.ic_gallery, R.drawable.ic_files};
        final int[] labels = {R.string.attach_gallery, R.string.attach_files};
        final LayoutInflater inflater = getLayoutInflater();
        ListAdapter ad = new BaseAdapter() {
            public int getCount() {
                return icons.length;
            }

            public Object getItem(int position) {
                return Integer.valueOf(position);
            }

            public long getItemId(int position) {
                return position;
            }

            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView != null ? convertView : Ui.scaleTree(inflater.inflate(R.layout.attach_row, null));
                ((ImageView) v.findViewById(R.id.attachIcon)).setImageResource(icons[position]);
                ((TextView) v.findViewById(R.id.attachText)).setText(labels[position]);
                return v;
            }
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.attach_title)
                .setAdapter(ad, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            pickFromGallery();
                        } else {
                            startPicker(new Intent(ChatActivity.this, FilePickerActivity.class), REQ_FILE);
                        }
                    }
                })
                .show();
    }

    private void pickFromGallery() {
        if (Api.sdkLevel() >= 19) {
            // Android 4.4+: один выбор сразу и фото, и видео
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra("android.intent.extra.MIME_TYPES", new String[] {"image/*", "video/*"});
            i.putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
            startPicker(i, REQ_GALLERY);
            return;
        }
        // На старых Android галерея выбирает один тип за раз
        new AlertDialog.Builder(this)
                .setTitle(R.string.attach_gallery)
                .setItems(new CharSequence[] {getString(R.string.attach_photos),
                        getString(R.string.attach_videos)}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.setType(which == 0 ? "image/*" : "video/*");
                        startPicker(i, REQ_GALLERY);
                    }
                })
                .show();
    }

    private void startPicker(Intent i, int request) {
        try {
            startActivityForResult(i, request);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.attach_no_app, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null
                || (requestCode != REQ_GALLERY && requestCode != REQ_FILE)) {
            return;
        }
        // Из галереи фото уходит фото (сжатым), из проводника всегда файлом как есть
        boolean asFile = requestCode == REQ_FILE;
        ArrayList<Uri> many = requestCode == REQ_GALLERY && Api.sdkLevel() >= 16 ? MultiPick.uris(data) : null;
        if (many != null) {
            for (int i = 0; i < many.size(); i++) {
                addAttachment(many.get(i), asFile);
            }
        } else if (data.getData() != null) {
            addAttachment(data.getData(), asFile);
        }
    }

    /** Определяет имя, размер и тип файла. Запросы к провайдеру короткие, поэтому в UI-потоке. */
    private Attachment describe(Uri uri, boolean asFile) {
        String name = null;
        long size = -1;
        if ("file".equals(uri.getScheme())) {
            File f = new File(uri.getPath());
            name = f.getName();
            size = f.length();
        } else {
            Cursor c = null;
            try {
                c = getContentResolver().query(uri, new String[] {"_display_name", "_size"}, null, null, null);
                if (c != null && c.moveToFirst()) {
                    name = c.getString(0);
                    if (!c.isNull(1)) {
                        size = c.getLong(1);
                    }
                }
            } catch (Exception e) {
                // Не все провайдеры отдают имя и размер
            } finally {
                if (c != null) {
                    c.close();
                }
            }
            // Если провайдер не сказал размер, он определится при отправке в фоновом потоке:
            // запрос к облачным провайдерам в UI-потоке мог бы подвесить экран
        }
        if (size <= 0) {
            size = -1;
        }
        if (name == null || name.length() == 0) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.length() == 0) {
            name = "file";
        }
        String mime = "file".equals(uri.getScheme()) ? null : getContentResolver().getType(uri);
        int dot = name.lastIndexOf('.');
        if (mime == null && dot >= 0 && dot < name.length() - 1) {
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
        }
        if (mime == null) {
            mime = "application/octet-stream";
        }
        if (dot < 0) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null) {
                name = name + "." + ext;
            }
        }
        Attachment a = new Attachment();
        a.uri = uri;
        a.name = name;
        a.size = size;
        a.mime = mime;
        a.asFile = asFile;
        return a;
    }

    private void addAttachment(Uri uri, boolean asFile) {
        if (attachments.size() >= MAX_ATTACHMENTS) {
            Toast.makeText(this, getString(R.string.attach_limit, MAX_ATTACHMENTS), Toast.LENGTH_SHORT).show();
            return;
        }
        attachments.add(describe(uri, asFile));
        rebuildTray();
    }

    /** Полоса плашек над полем ввода: значок, имя и крестик, которым вложение убирается. */
    private void rebuildTray() {
        attachTray.removeAllViews();
        for (int i = 0; i < attachments.size(); i++) {
            final Attachment a = attachments.get(i);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackgroundResource(R.drawable.chip_bg);
            chip.setPadding(dp(6), 0, 0, 0);

            ImageView icon = new ImageView(this);
            icon.setImageResource(a.asFile ? R.drawable.ic_attach_w : R.drawable.ic_gallery_w);
            chip.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

            TextView name = new TextView(this);
            name.setText(a.name);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setMaxWidth(dp(130));
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(13);
            name.setPadding(dp(6), 0, 0, 0);
            chip.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageButton remove = new ImageButton(this);
            remove.setImageResource(R.drawable.ic_remove_w);
            remove.setBackgroundColor(0x00000000);
            if (Api.sdkLevel() >= 4) {
                remove.setContentDescription(getString(R.string.attach_remove)); // метод есть с API 4
            }
            remove.setPadding(dp(8), dp(8), dp(8), dp(8));
            remove.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    attachments.remove(a);
                    rebuildTray();
                }
            });
            chip.addView(remove, new LinearLayout.LayoutParams(dp(34), dp(34)));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            attachTray.addView(chip, lp);
        }
        attachScroll.setVisibility(attachments.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---------- Отправка вложений (в фоновом сервисе) ----------

    /** Диалог прогресса; "Назад" его прячет, отправка при этом идёт дальше. */
    private TransferDialog uploadDialog;

    private final UploadService.Listener uploadListener = new UploadService.Listener() {
        public boolean accepts(UploadService.Job job) {
            return !destroyed && job.peer.equals(peerId);
        }

        public void onFileStart(UploadService.Job job, int index, int total, String name, boolean sizeKnown) {
            showUploadDialog();
            if (uploadDialog != null) {
                uploadDialog.begin(getString(R.string.upload_progress, index, total, name), sizeKnown);
            }
        }

        public void onProgress(UploadService.Job job, long done, long total) {
            if (uploadDialog != null) {
                uploadDialog.update(done, total);
            }
        }

        public void onFinished(UploadService.Job job, int sent, ArrayList<UploadService.Item> remaining,
                               boolean captionUnsent, Exception error, boolean cancelled) {
            if (uploadDialog != null) {
                uploadDialog.dismiss();
                uploadDialog = null;
            }
            uploading = false;
            // Что не ушло (ошибка, отмена), возвращаем на плашки, чтобы отправить повторно
            for (int i = 0; i < remaining.size() && attachments.size() < MAX_ATTACHMENTS; i++) {
                attachments.add(toAttachment(remaining.get(i)));
            }
            rebuildTray();
            if (captionUnsent && job.caption.length() > 0 && editMessage.getText().toString().trim().length() == 0) {
                editMessage.setText(job.caption);
                editMessage.setSelection(job.caption.length());
            }
            if (sent > 0) {
                groupAfterSend = sent > 1 ? sent : 0;
                refreshAfterSend(0);
            }
            if (error == null || cancelled) {
                return;
            }
            if (Errors.isSessionError(error)) {
                Api.clearSession(ChatActivity.this);
                setResult(RESULT_SESSION_EXPIRED);
                finish();
                return;
            }
            Toast.makeText(ChatActivity.this,
                    getString(R.string.err_send_failed) + ": " + Errors.text(ChatActivity.this, error),
                    Toast.LENGTH_LONG).show();
        }
    };

    private void showUploadDialog() {
        if (uploadDialog != null) {
            return;
        }
        uploadDialog = new TransferDialog(this, R.string.uploading);
        uploadDialog.setBackHides(true);      // "Назад" прячет диалог, отправка идёт в фоне
        uploadDialog.setKeepOnRelease(true);  // закрытие чата отправку не отменяет
        uploadDialog.setOnCancel(new Runnable() {
            public void run() {
                UploadService.cancelCurrent();
            }
        });
        uploadDialog.show();
    }

    private static Attachment toAttachment(UploadService.Item it) {
        Attachment a = new Attachment();
        a.uri = it.uri;
        a.name = it.name;
        a.size = it.size;
        a.mime = it.mime;
        a.asFile = it.asFile;
        return a;
    }

    /**
     * Отправляет выбранные вложения. Работу берёт на себя UploadService: файлы уходят по очереди
     * (по одному сообщению на файл), а экран только показывает прогресс. Текст из поля ввода
     * уходит подписью к первому файлу.
     */
    private void sendAttachments() {
        if (uploading || attachments.isEmpty()) {
            return;
        }
        UploadService.Job job = new UploadService.Job();
        job.peer = peerId;
        job.topicId = topicId;
        job.replyId = replyTo != null ? replyTo.id : 0;
        job.caption = editMessage.getText().toString().trim();
        // Тексты переводим здесь: язык приложения применяется к ресурсам экрана, а не сервиса
        job.sTitle = getString(R.string.uploading);
        job.sProgress = getString(R.string.upload_progress);
        job.sDone = getString(R.string.upload_done);
        job.sFailed = getString(R.string.err_send_failed);
        for (int i = 0; i < attachments.size(); i++) {
            Attachment a = attachments.get(i);
            UploadService.Item it = new UploadService.Item();
            it.uri = a.uri;
            it.name = a.name;
            it.size = a.size;
            it.mime = a.mime;
            it.asFile = a.asFile;
            job.items.add(it);
        }
        uploading = true;
        attachments.clear();
        setReplyTo(null);
        rebuildTray();
        if (job.caption.length() > 0) {
            editMessage.setText("");
        }
        scrollToBottom();
        showUploadDialog();
        UploadService.enqueue(this, job);
    }

    // ---------- Отправка ----------

    private void sendMessage() {
        if (sending) {
            return;
        }
        final String text = editMessage.getText().toString().trim();
        if (text.length() == 0) {
            return;
        }
        sending = true;
        final String seed = (text.equals(lastFailedText) && lastFailedSeed != null)
                ? lastFailedSeed : String.valueOf(new Random().nextInt(Integer.MAX_VALUE));
        final MsgItem sentReply = replyTo;
        final String replyParam = sentReply != null ? String.valueOf(sentReply.id)
                : (topicId > 0 ? String.valueOf(topicId) : null);
        setReplyTo(null);
        editMessage.setText("");
        addPending(text);

        new Thread(new Runnable() {
            public void run() {
                try {
                    api.call("sendMessage",
                            "peer", peerId,
                            "text", text,
                            // Отправка в топик форума это ответ на корневое сообщение топика
                            "reply", replyParam,
                            "r", seed);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            sending = false;
                            lastFailedText = null;
                            lastFailedSeed = null;
                            refreshAfterSend(0);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            sending = false;
                            if (sentReply != null && replyTo == null) {
                                setReplyTo(sentReply); // не ушло: ответ остаётся выбранным
                            }
                            if (Errors.isSessionError(e)) {
                                Api.clearSession(ChatActivity.this);
                                setResult(RESULT_SESSION_EXPIRED);
                                finish();
                                return;
                            }
                            removePending();
                            lastFailedText = text;
                            lastFailedSeed = seed;
                            // Возвращаем текст в поле, если там ничего не набрано за это время
                            if (editMessage.getText().length() == 0) {
                                editMessage.setText(text);
                                editMessage.setSelection(text.length());
                            }
                            Toast.makeText(ChatActivity.this,
                                    getString(R.string.err_send_failed) + ": "
                                            + Errors.text(ChatActivity.this, e),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /** Сразу показываем отправляемое сообщение серым временем "отправка...". */
    private void addPending(String text) {
        long now = System.currentTimeMillis() / 1000L;
        MsgItem p = new MsgItem();
        p.id = Integer.MAX_VALUE;
        p.pending = true;
        p.out = true;
        p.date = now;
        p.dayKey = dayKey(now);
        p.time = getString(R.string.sending);
        if (EmojiMap.maybeHas(text)) {
            // Иначе на время отправки эмодзи превращались бы в прямоугольники системного шрифта
            SpannableStringBuilder sb = new SpannableStringBuilder(text);
            EmojiMap.addSpans(sb);
            p.body = sb;
            p.emojiOnly = EmojiMap.onlyEmojiCount(sb);
        } else {
            p.body = text;
        }
        items.add(p);
        pendingItem = p;
        textInfo.setVisibility(View.GONE);
        recomputeDates();
        adapter.notifyDataSetChanged();
        scrollToBottom();
    }

    private void removePending() {
        if (pendingItem != null) {
            items.remove(pendingItem);
            pendingItem = null;
            recomputeDates();
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * Сервер на sendMessage отвечает только "ок", поэтому настоящее сообщение берём из свежей истории.
     * Если оно ещё не появилось, пробуем ещё раз через полторы секунды.
     */
    private void refreshAfterSend(final int attempt) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    final Page page = loadPage(0, INITIAL);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            if (hasMoreNewer) {
                                // Список оборван посреди переписки: новые сообщения встали бы с дырой,
                                // поэтому берём настоящий низ целиком
                                forceBottom = true;
                                loadInitial(false);
                                return;
                            }
                            int lastId = 0;
                            for (int i = items.size() - 1; i >= 0; i--) {
                                if (!items.get(i).pending) {
                                    lastId = items.get(i).id;
                                    break;
                                }
                            }
                            ArrayList<MsgItem> fresh = new ArrayList<MsgItem>();
                            for (int i = 0; i < page.list.size(); i++) {
                                if (page.list.get(i).id > lastId) {
                                    fresh.add(page.list.get(i));
                                }
                            }
                            int ownMedia = 0;
                            for (int i = 0; i < fresh.size(); i++) {
                                if (fresh.get(i).out && fresh.get(i).downloadable) {
                                    ownMedia += fresh.get(i).parts != null ? fresh.get(i).parts.size() : 1;
                                }
                            }
                            if ((fresh.isEmpty() || ownMedia < groupAfterSend) && attempt < 2) {
                                listMessages.postDelayed(new Runnable() {
                                    public void run() {
                                        if (!destroyed) {
                                            refreshAfterSend(attempt + 1);
                                        }
                                    }
                                }, 1500);
                                return;
                            }
                            if (pendingItem != null) {
                                items.remove(pendingItem);
                                pendingItem = null;
                            }
                            if (groupAfterSend > 1 && ownMedia > 1) {
                                // Последние отправленные файлы запоминаем как одну группу
                                int want = Math.min(groupAfterSend, ownMedia);
                                int[] ids = new int[want];
                                int n = want;
                                for (int i = fresh.size() - 1; i >= 0 && n > 0; i--) {
                                    if (fresh.get(i).out && fresh.get(i).downloadable) {
                                        ids[--n] = fresh.get(i).id;
                                    }
                                }
                                long key = LocalGroups.remember(ChatActivity.this, peerId, ids);
                                for (int i = 0; i < fresh.size(); i++) {
                                    for (int k = 0; k < ids.length; k++) {
                                        if (fresh.get(i).id == ids[k]) {
                                            fresh.get(i).groupKey = key;
                                        }
                                    }
                                }
                                fresh = groupItems(fresh);
                            }
                            groupAfterSend = 0;
                            items.addAll(fresh);
                            notePinEvents(fresh);
                            recomputeDates();
                            adapter.notifyDataSetChanged();
                            scrollToBottom();
                        }
                    });
                } catch (Exception e) {
                    // Сообщение отправлено, но обновить не вышло: перезагружаем переписку целиком
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            pendingItem = null;
                            loadInitial(false);
                        }
                    });
                }
            }
        }).start();
    }

    // ---------- Разбор ответа ----------

    private Page parsePage(JSONObject res) {
        Page page = new Page();

        JSONObject users = res.optJSONObject("users");
        if (users != null) {
            Iterator<?> keys = users.keys();
            while (keys.hasNext()) {
                String k = (String) keys.next();
                String n = nameOf(users.optJSONObject(k));
                if (n.length() > 0) {
                    names.put(k, n);
                }
            }
        }
        JSONObject chats = res.optJSONObject("chats");
        JSONObject chat = chats == null ? null : chats.optJSONObject(peerId);
        page.broadcast = chat != null && chat.optBoolean("c", false);

        JSONArray arr = res.optJSONArray("messages");
        if (arr != null) {
            page.rawCount = arr.length();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) {
                    continue;
                }
                int mid = m.optInt("id", 0);
                if (mid > 0 && (page.minId == 0 || mid < page.minId)) {
                    page.minId = mid;
                }
                if (mid > page.maxId) {
                    page.maxId = mid;
                }
                try {
                    MsgItem it = toItem(m, page.broadcast);
                    if (it != null) {
                        page.list.add(it);
                    }
                } catch (RuntimeException e) {
                    // Одно "кривое" сообщение не должно ломать весь чат
                }
            }
        }
        // Сервер отдаёт от новых к старым, а показываем от старых к новым
        Collections.sort(page.list, new Comparator<MsgItem>() {
            public int compare(MsgItem a, MsgItem b) {
                return a.id < b.id ? -1 : (a.id == b.id ? 0 : 1);
            }
        });
        ArrayList<MsgItem> grouped = groupItems(page.list);
        page.list.clear();
        page.list.addAll(grouped);
        return page;
    }

    /** Склеивает подряд идущие сообщения одной группы (альбома) в один бабл. */
    private ArrayList<MsgItem> groupItems(ArrayList<MsgItem> list) {
        ArrayList<MsgItem> out = new ArrayList<MsgItem>();
        MsgItem prev = null;
        for (int i = 0; i < list.size(); i++) {
            MsgItem m = list.get(i);
            if (prev != null && m.groupKey != 0 && prev.groupKey == m.groupKey && !m.service
                    && !prev.service && prev.out == m.out && prev.sender.equals(m.sender)) {
                prev.merge(m);
            } else {
                out.add(m);
                prev = m;
            }
        }
        return out;
    }

    private static String nameOf(JSONObject u) {
        if (u == null) {
            return "";
        }
        String n = (Api.str(u, "fn") + " " + Api.str(u, "ln")).trim();
        if (n.length() == 0 && Api.str(u, "name").length() > 0) {
            n = "@" + Api.str(u, "name");
        }
        return n;
    }

    private String nameFor(String id) {
        String n = id.length() == 0 ? null : names.get(id);
        return n == null ? "" : n;
    }

    /** Реакции сообщения из любого из известных видов ответа сервера. */
    private static void parseReacts(Object rx, MsgItem it) {
        if (rx == null || it.reactCount > 0) {
            return;
        }
        ArrayList<String> emo = new ArrayList<String>();
        ArrayList<Integer> num = new ArrayList<Integer>();
        int total = 0;
        JSONArray list = null;
        if (rx instanceof JSONArray) {
            list = (JSONArray) rx;
        } else if (rx instanceof JSONObject) {
            JSONObject o = (JSONObject) rx;
            total = o.optInt("count", 0);
            Object inner = o.opt("results");
            if (!(inner instanceof JSONArray)) {
                inner = o.opt("list");
            }
            if (!(inner instanceof JSONArray)) {
                inner = o.opt("reactions");
            }
            if (inner instanceof JSONArray) {
                list = (JSONArray) inner;
            } else {
                // Объект вида {"👍": 3, "❤": 1}
                java.util.Iterator keys = o.keys();
                while (keys.hasNext()) {
                    String k = String.valueOf(keys.next());
                    if ("count".equals(k) || "total".equals(k) || "min".equals(k) || "can_see_list".equals(k)) {
                        continue;
                    }
                    int c = o.optInt(k, 0);
                    if (c > 0) {
                        emo.add(k);
                        num.add(Integer.valueOf(c));
                    }
                }
            }
        }
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                int c = r.optInt("count", r.optInt("n", r.optInt("c", 0)));
                if (c <= 0) {
                    continue;
                }
                String e = Api.str(r, "e");
                if (e.length() == 0) {
                    e = Api.str(r, "emoji");
                }
                if (e.length() == 0) {
                    e = Api.str(r, "emoticon");
                }
                if (e.length() == 0) {
                    Object inner = r.opt("reaction");
                    if (inner instanceof JSONObject) {
                        e = Api.str((JSONObject) inner, "emoticon");
                    } else if (inner != null) {
                        e = String.valueOf(inner);
                    }
                }
                emo.add(e.length() > 0 ? e : "\u2605");
                num.add(Integer.valueOf(c));
            }
        }
        int sum = 0;
        for (int i = 0; i < num.size(); i++) {
            sum += num.get(i).intValue();
        }
        it.reactCount = Math.max(total, sum);
        if (!emo.isEmpty()) {
            it.reactEmoji = emo.toArray(new String[emo.size()]);
            it.reactNum = new int[num.size()];
            for (int i = 0; i < it.reactNum.length; i++) {
                it.reactNum[i] = num.get(i).intValue();
            }
        }
    }

    private MsgItem toItem(JSONObject m, boolean isBroadcast) {
        MsgItem it = new MsgItem();
        it.id = m.optInt("id", 0);
        if (it.id <= 0) {
            return null;
        }
        it.date = m.optLong("date", 0);
        it.dayKey = dayKey(it.date);
        it.time = formatTime(it.date);
        it.out = m.optBoolean("out", false);

        JSONObject act = m.optJSONObject("act");
        if (act != null) {
            it.service = true;
            it.serviceText = serviceText(act);
            it.pinEvent = Api.str(act, "_").indexOf("PinMessage") >= 0;
            return it;
        }

        // В группах над входящими сообщениями показываем имя отправителя
        String from = Api.str(m, "from_id");
        if (!it.out && !isBroadcast && peerId.startsWith("-") && from.length() > 0) {
            it.sender = nameFor(from);
            it.senderId = from;
            it.senderColor = NAME_COLORS[Math.abs(from.hashCode()) % NAME_COLORS.length];
        }

        // Реакции: сервер может отдать только общее число, либо список эмодзи со счётчиками
        // (массив или объект "эмодзи: число"); разбираем все известные виды
        parseReacts(m.opt("reacts"), it);
        if (it.reactCount <= 0) {
            parseReacts(m.opt("reactions"), it);
        }

        JSONObject fwd = m.optJSONObject("fwd");
        if (fwd != null) {
            String n = Api.str(fwd, "from_name");
            if (n.length() == 0) {
                n = nameFor(Api.str(fwd, "from_id"));
            }
            it.fwd = getString(R.string.fwd_from, n.length() > 0 ? n : getString(R.string.fwd_unknown));
        }

        JSONObject reply = m.optJSONObject("reply");
        if (reply != null) {
            JSONObject rm = reply.optJSONObject("msg");
            it.replyId = reply.optInt("id", rm != null ? rm.optInt("id", 0) : 0);
            String q = rm != null ? Api.str(rm, "text") : Api.str(reply, "quote");
            q = q.replace('\n', ' ').trim();
            if (q.length() > 80) {
                q = q.substring(0, 80) + "\u2026";
            }
            if (q.length() > 0) {
                String who = rm == null ? "" : nameFor(Api.str(rm, "from_id"));
                it.quote = who.length() > 0 ? who + ": " + q : q;
            }
        }

        JSONObject media = m.optJSONObject("media");
        if (media != null) {
            it.mediaLabel = mediaLabel(media);
            String mt = Api.str(media, "type");
            if ("photo".equals(mt) || "document".equals(mt)) {
                it.downloadable = true;
                it.fileName = fileNameFor(media, it.id);
                it.fileSize = media.optLong("size", 0);
            }
        }
        if (it.downloadable) {
            // Настоящий альбом (group) или пачка, отправленная из этого приложения
            it.groupKey = m.optLong("group", 0);
            if (it.groupKey == 0) {
                it.groupKey = LocalGroups.keyOf(this, peerId, it.id);
            }
        }
        if (media != null && isImage(media)) {
            it.image = true;
            it.imgW = media.optInt("w", 0);
            it.imgH = media.optInt("h", 0);
        }

        it.body = buildBody(m, it);
        if (EmojiMap.maybeHas(it.body)) {
            Spannable sp = it.body instanceof Spannable ? (Spannable) it.body : new SpannableStringBuilder(it.body);
            EmojiMap.addSpans(sp);
            it.body = sp;
            // Из одних эмодзи (без ссылок, ответа, пересылки и вложений): рисуем без пузыря
            if (!it.downloadable && !it.hasLinks && it.quote.length() == 0 && it.fwd.length() == 0) {
                it.emojiOnly = EmojiMap.onlyEmojiCount(sp);
            }
        }
        return it;
    }

    private String serviceText(JSONObject act) {
        String t = Api.str(act, "_");
        String user = nameFor(Api.str(act, "user"));
        if (t.indexOf("EditTitle") >= 0) {
            return getString(R.string.svc_renamed, Api.str(act, "t"));
        }
        if (t.indexOf("AddUser") >= 0 || t.indexOf("JoinedBy") >= 0) {
            return user.length() > 0 ? getString(R.string.svc_joined, user)
                    : getString(R.string.preview_service);
        }
        if (t.indexOf("DeleteUser") >= 0) {
            return user.length() > 0 ? getString(R.string.svc_left, user)
                    : getString(R.string.preview_service);
        }
        if (t.indexOf("PinMessage") >= 0) {
            return getString(R.string.svc_pinned);
        }
        return getString(R.string.preview_service);
    }

    /** Текст пузыря: строка о вложении (курсивом) и сам текст с оформлением. */
    private CharSequence buildBody(JSONObject m, MsgItem it) {
        SpannableStringBuilder sb = new SpannableStringBuilder();

        JSONObject media = m.optJSONObject("media");
        if (media != null && !it.image) {
            String label = mediaLabel(media);
            if (label.length() > 0) {
                sb.append(label);
                sb.setSpan(new StyleSpan(Typeface.ITALIC), 0, sb.length(), FLAG);
                sb.setSpan(new ForegroundColorSpan(0xFF555555), 0, sb.length(), FLAG);
            }
        }

        String text = Api.str(m, "text");
        if (text.length() > 0) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            int base = sb.length();
            sb.append(text);
            applyEntities(sb, base, text.length(), m.optJSONArray("entities"), it);
        }
        if (sb.length() == 0 && media == null) {
            sb.append(getString(R.string.preview_media));
            sb.setSpan(new StyleSpan(Typeface.ITALIC), 0, sb.length(), FLAG);
        }
        // Неизменяемая копия: TextView не копирует её при каждом setText.
        // Ссылки кликабельны через LinkMovementMethod, ему нужен Spannable, и TextView сделает копию сам.
        return new SpannedString(sb);
    }

    /**
     * Гасит инерцию прокрутки: иначе после прыжка вниз список "доезжает" по инерции прежнего
     * свайпа. Касание списка во время инерции её останавливает, затем сразу отменяем касание.
     */
    private int dp(int v) {
        return Ui.px(this, v); // с учётом множителя интерфейса: на больших экранах всё пропорционально крупнее
    }

    /**
     * Кнопки "эмодзи", "прикрепить", "отправить" равны полю ввода по высоте: сторона квадрата это
     * высота однострочного поля (строка шрифта плюс отступы, не меньше 48dp с множителем экрана).
     * Значок в кнопке растёт вместе с ней. Так всё вместе подстраивается и под крупный шрифт, и под
     * большой экран, а при многострочном вводе кнопки остаются внизу своего размера.
     */
    /** Листает чат к последнему сообщению, погасив инерцию прежнего свайпа. */
    private void scrollToBottom() {
        if (items.isEmpty()) {
            return;
        }
        stopFling();
        listMessages.setSelection(items.size() - 1);
        // Ещё раз после раскладки: список мог только что измениться
        listMessages.post(new Runnable() {
            public void run() {
                if (!destroyed && !items.isEmpty()) {
                    listMessages.setSelection(items.size() - 1);
                }
            }
        });
    }

    private void stopFling() {
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 0f, 0f, 0);
        listMessages.dispatchTouchEvent(down);
        down.recycle();
        MotionEvent cancel = MotionEvent.obtain(t, t, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        listMessages.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    /** Имя файла для шапки просмотра и сохранения: исходное, а если его нет, придумываем по типу. */
    private String fileNameFor(JSONObject media, int msgId) {
        String name = Api.str(media, "name");
        if (name.length() > 0) {
            return name;
        }
        String mime = Api.str(media, "mime");
        String ext = "";
        if ("photo".equals(Api.str(media, "type")) || "image/jpeg".equals(mime)) {
            ext = ".jpg";
        } else if ("image/png".equals(mime)) {
            ext = ".png";
        } else if ("image/gif".equals(mime)) {
            ext = ".gif";
        } else if ("image/webp".equals(mime)) {
            ext = ".webp";
        } else if ("audio/ogg".equals(mime)) {
            ext = ".ogg";
        } else if ("audio/mpeg".equals(mime)) {
            ext = ".mp3";
        } else if ("video/mp4".equals(mime)) {
            ext = ".mp4";
        } else if ("application/pdf".equals(mime)) {
            ext = ".pdf";
        }
        String base = "photo".equals(Api.str(media, "type")) ? "photo_" : "file_";
        return base + msgId + ext;
    }

    /** Касание бабла: фото открывается на весь экран, файл предлагается скачать (если их несколько, на выбор). */
    private void openAttachment(MsgItem m) {
        ArrayList<MsgItem.Part> all = partsOf(m);
        final ArrayList<MsgItem.Part> files = new ArrayList<MsgItem.Part>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).downloadable && !all.get(i).image) {
                files.add(all.get(i));
            }
        }
        if (files.isEmpty()) {
            if (all.size() == 1 && all.get(0).image) {
                openViewer(all.get(0));
            }
            return;
        }
        if (files.size() == 1) {
            confirmDownload(files.get(0));
            return;
        }
        CharSequence[] names = new CharSequence[files.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = files.get(i).fileName;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dl_title)
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        confirmDownload(files.get(which));
                    }
                })
                .show();
    }

    private void openViewer(MsgItem.Part p) {
        Intent i = new Intent(this, ViewerActivity.class);
        i.putExtra(ViewerActivity.EXTRA_PEER, peerId);
        i.putExtra(ViewerActivity.EXTRA_ID, p.id);
        i.putExtra(ViewerActivity.EXTRA_NAME, p.fileName);
        i.putExtra(ViewerActivity.EXTRA_SIZE, p.fileSize);
        startActivity(i);
    }

    private void confirmDownload(final MsgItem.Part p) {
        String msg = p.fileSize > 0 ? p.fileName + " (" + formatSize(p.fileSize) + ")" : p.fileName;
        new AlertDialog.Builder(this)
                .setTitle(R.string.dl_title)
                .setMessage(msg)
                .setPositiveButton(R.string.btn_download, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Downloader.start(ChatActivity.this, api, peerId, p.id, p.fileName, p.fileSize);
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    /** Фото или файл-картинка, которую сервер умеет уменьшить (file.php). */
    private static boolean isImage(JSONObject media) {
        String type = Api.str(media, "type");
        if ("photo".equals(type)) {
            return true;
        }
        if ("document".equals(type)) {
            String mime = Api.str(media, "mime");
            return "image/jpeg".equals(mime) || "image/png".equals(mime)
                    || "image/gif".equals(mime) || "image/webp".equals(mime);
        }
        return false;
    }

    private String mediaLabel(JSONObject media) {
        String type = Api.str(media, "type");
        if ("photo".equals(type)) {
            return getString(R.string.preview_photo);
        }
        if ("document".equals(type)) {
            int dur = media.optInt("time", 0);
            String d = dur > 0 ? " " + (dur / 60) + ":" + (dur % 60 < 10 ? "0" : "") + (dur % 60) : "";
            if (media.optBoolean("voice", false)) {
                return getString(R.string.preview_voice) + d;
            }
            if (media.optBoolean("audio", false)) {
                String title = Api.str(media, "title");
                String artist = Api.str(media, "artist");
                String t = (artist.length() > 0 && title.length() > 0) ? artist + " - " + title : title;
                return (t.length() > 0 ? t : getString(R.string.preview_audio)) + d;
            }
            String name = Api.str(media, "name");
            String label = name.length() > 0 ? name : getString(R.string.preview_file);
            long size = media.optLong("size", 0);
            return size > 0 ? label + " (" + formatSize(size) + ")" : label;
        }
        if ("webpage".equals(type)) {
            String t = Api.str(media, "title");
            String u = Api.str(media, "url");
            String tail = t.length() > 0 ? t : u;
            return getString(R.string.preview_link) + (tail.length() > 0 ? ": " + tail : "");
        }
        if ("geo".equals(type)) {
            return getString(R.string.preview_location) + ": "
                    + Api.str(media, "lat") + ", " + Api.str(media, "long");
        }
        if ("poll".equals(type)) {
            StringBuffer sb = new StringBuffer();
            sb.append(getString(R.string.preview_poll)).append(": ").append(Api.str(media, "text"));
            JSONArray opts = media.optJSONArray("options");
            if (opts != null) {
                for (int i = 0; i < opts.length() && i < 10; i++) {
                    JSONObject o = opts.optJSONObject(i);
                    if (o != null) {
                        sb.append("\n\u2022 ").append(Api.str(o, "text"));
                    }
                }
            }
            return sb.toString();
        }
        return getString(R.string.preview_media);
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return (bytes * 10 / (1024L * 1024L) / 10.0) + " " + getString(R.string.unit_mb);
        }
        return Math.max(1, bytes / 1024) + " " + getString(R.string.unit_kb);
    }

    /**
     * Ссылка из сообщения. Открываются только http, https, mailto и tel: адреса со схемами
     * file, content, intent и прочими блокируются. Если видимый текст не показывает адрес
     * (ссылка спрятана за словами), перед открытием показывается настоящий адрес.
     */
    private class SafeLinkSpan extends ClickableSpan {
        private final String url;
        private final boolean confirm;

        SafeLinkSpan(String url, boolean confirm) {
            this.url = url;
            this.confirm = confirm;
        }

        @Override
        public void onClick(View widget) {
            final Uri uri = linkUri(url);
            if (uri == null) {
                Toast.makeText(ChatActivity.this, R.string.link_blocked, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!confirm) {
                openLink(uri);
                return;
            }
            new AlertDialog.Builder(ChatActivity.this)
                    .setTitle(R.string.link_open_title)
                    .setMessage(uri.toString())
                    .setPositiveButton(R.string.btn_open, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            openLink(uri);
                        }
                    })
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show();
        }
    }

    /** Разрешённый адрес или null, если схема не из белого списка. Без схемы это http. */
    private static Uri linkUri(String raw) {
        String u = raw.trim();
        if (u.length() == 0) {
            return null;
        }
        Uri uri = Uri.parse(u);
        String scheme = uri.getScheme();
        if (scheme == null) {
            uri = Uri.parse("http://" + u);
            scheme = "http";
        }
        scheme = scheme.toLowerCase();
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto")
                || scheme.equals("tel")) {
            return uri;
        }
        return null;
    }

    /** Видимый текст содержит хост адреса (например, "site.com" для http://site.com/page). */
    private static boolean textShowsHost(String shown, String url) {
        Uri uri = linkUri(url);
        String host = uri == null ? null : uri.getHost();
        return host != null && shown.toLowerCase().indexOf(host.toLowerCase()) >= 0;
    }

    private void openLink(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.link_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    /** Жирный, курсив, код, подчёркнутый, зачёркнутый и ссылки из массива entities. */
    private void applyEntities(SpannableStringBuilder sb, int base, int len,
                               JSONArray entities, MsgItem it) {
        if (entities == null) {
            return;
        }
        for (int i = 0; i < entities.length(); i++) {
            try {
                JSONObject e = entities.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                int off = e.optInt("offset", 0);
                int s = base + Math.max(0, off);
                int en = base + Math.min(len, off + e.optInt("length", 0));
                if (s >= en || en > sb.length()) {
                    continue;
                }
                String t = Api.str(e, "_");
                if ("messageEntityBold".equals(t)) {
                    sb.setSpan(new StyleSpan(Typeface.BOLD), s, en, FLAG);
                } else if ("messageEntityItalic".equals(t)) {
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), s, en, FLAG);
                } else if ("messageEntityCode".equals(t) || "messageEntityPre".equals(t)) {
                    sb.setSpan(new TypefaceSpan("monospace"), s, en, FLAG);
                } else if ("messageEntityUnderline".equals(t)) {
                    sb.setSpan(new UnderlineSpan(), s, en, FLAG);
                } else if ("messageEntityStrike".equals(t)) {
                    sb.setSpan(new StrikethroughSpan(), s, en, FLAG);
                } else if ("messageEntityTextUrl".equals(t)) {
                    String url = Api.str(e, "url");
                    if (url.length() > 0) {
                        // Скрытый адрес: если видимый текст не содержит хост, спросим перед открытием
                        sb.setSpan(new SafeLinkSpan(url, !textShowsHost(sb.subSequence(s, en).toString(), url)),
                                s, en, FLAG);
                        it.hasLinks = true;
                    }
                } else if ("messageEntityUrl".equals(t)) {
                    String url = sb.subSequence(s, en).toString();
                    if (url.indexOf("://") < 0) {
                        url = "http://" + url;
                    }
                    sb.setSpan(new SafeLinkSpan(url, false), s, en, FLAG);
                    it.hasLinks = true;
                }
            } catch (RuntimeException ex) {
                // Битая сущность просто не оформляется
            }
        }
    }

    // ---------- Даты ----------

    private static synchronized String formatTime(long seconds) {
        return seconds <= 0 ? "" : FMT_TIME.format(new Date(seconds * 1000L));
    }

    private static synchronized String formatDate(long seconds) {
        return FMT_DATE.format(new Date(seconds * 1000L));
    }

    // Calendar.getInstance() на старых Android дорогой: используем один экземпляр
    private static final Calendar CAL = Calendar.getInstance();

    private static synchronized long dayKey(long seconds) {
        CAL.setTimeZone(java.util.TimeZone.getDefault());
        CAL.setTimeInMillis(seconds * 1000L);
        return CAL.get(Calendar.YEAR) * 1000L + CAL.get(Calendar.DAY_OF_YEAR);
    }

    /** Расставляет разделители дней. Вызывать после каждого изменения списка. */
    private void recomputeDates() {
        newestId = newestLoadedId();
        long now = System.currentTimeMillis() / 1000L;
        long today = dayKey(now);
        long yesterday = dayKey(now - 24L * 60 * 60);
        for (int i = 0; i < items.size(); i++) {
            MsgItem it = items.get(i);
            boolean show = i == 0 || it.dayKey != items.get(i - 1).dayKey;
            it.showDate = show;
            if (show) {
                if (it.dayKey == today) {
                    it.dateLabel = getString(R.string.date_today);
                } else if (it.dayKey == yesterday) {
                    it.dateLabel = getString(R.string.date_yesterday);
                } else {
                    it.dateLabel = formatDate(it.date);
                }
            }
        }
        // Граница снизу для догрузки свежих не нужна: хвост отслеживается флагом hasMoreNewer
    }

    // ---------- Отрисовка ----------

    private class MsgAdapter extends ArrayAdapter<MsgItem> {

        MsgAdapter() {
            super(ChatActivity.this, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            Holder h;
            if (v == null) {
                v = Ui.scaleTree(getLayoutInflater().inflate(R.layout.msg_item, null));
                h = new Holder();
                h.date = (TextView) v.findViewById(R.id.textDate);
                h.service = (TextView) v.findViewById(R.id.textService);
                h.row = (LinearLayout) v.findViewById(R.id.row);
                h.bubble = (LinearLayout) v.findViewById(R.id.bubble);
                h.sender = (TextView) v.findViewById(R.id.textSender);
                h.fwd = (TextView) v.findViewById(R.id.textFwd);
                h.quote = (TextView) v.findViewById(R.id.textQuote);
                h.quote.setOnClickListener(quoteClick);
                h.quote.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        Object t = v.getTag();
                        return t instanceof MsgItem && showMessageMenu((MsgItem) t);
                    }
                });
                h.media = (LinearLayout) v.findViewById(R.id.mediaBox);
                h.btnAll = (TextView) v.findViewById(R.id.btnDownloadAll);
                h.reactBox = (ReactFlow) v.findViewById(R.id.reactBox);
                h.flowTime = new TextView(ChatActivity.this);
                h.flowTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * uiScale);
                h.flowTime.setTextColor(0xFF777777);
                h.flowTime.setPadding(dp(6), 0, 0, 0);
                h.reactBox.setTime(h.flowTime);
                h.body = (TextView) v.findViewById(R.id.textBody);
                h.time = (TextView) v.findViewById(R.id.textTime);
                v.setTag(h);
            } else {
                h = (Holder) v.getTag();
            }
            bind(h, getItem(position));
            return v;
        }
    }

    private void bind(Holder h, MsgItem m) {
        if (m.showDate) {
            h.date.setText(m.dateLabel);
            h.date.setVisibility(View.VISIBLE);
        } else {
            h.date.setVisibility(View.GONE);
        }

        if (m.service) {
            h.row.setVisibility(View.GONE);
            h.service.setText(m.serviceText);
            h.service.setVisibility(View.VISIBLE);
            return;
        }
        h.service.setVisibility(View.GONE);
        h.row.setVisibility(View.VISIBLE);

        // Сторона и фон пузыря меняются только когда строка переиспользуется для другого вида.
        // Сообщение из одних эмодзи рисуется без пузыря: на тёмном фоне окна, время светлее.
        int dir = m.out ? 2 : 1;
        boolean bare = m.emojiOnly > 0;
        int look = dir * 2 + (bare ? 1 : 0);
        if (h.look != look) {
            h.look = look;
            h.row.setGravity(m.out ? Gravity.RIGHT : Gravity.LEFT);
            if (bare) {
                h.bubble.setBackgroundDrawable(null);
                h.bubble.setPadding(dp(4), 0, dp(4), 0);
                h.time.setTextColor(0xFFA0AABB);
            } else {
                h.bubble.setBackgroundResource(m.out ? R.drawable.bubble_out : R.drawable.bubble_in);
                h.time.setTextColor(0xFF777777);
            }
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.bubble.getLayoutParams();
            lp.leftMargin = m.out ? farMargin : nearMargin;
            lp.rightMargin = m.out ? nearMargin : farMargin;
            h.bubble.setLayoutParams(lp);
        }

        if (m.sender.length() > 0) {
            h.sender.setText(m.sender);
            h.sender.setTextColor(bare ? lighten(m.senderColor) : m.senderColor);
            if (m.senderId.length() > 0 && !m.senderId.startsWith("-")) {
                // Касание имени человека открывает его профиль с кнопкой "Написать"
                h.sender.setTag(m);
                if (!h.senderClickable) {
                    h.senderClickable = true;
                    h.sender.setOnClickListener(senderClick);
                }
            } else if (h.senderClickable) {
                h.senderClickable = false;
                h.sender.setOnClickListener(null);
                h.sender.setClickable(false);
            }
            h.sender.setVisibility(View.VISIBLE);
        } else {
            h.sender.setVisibility(View.GONE);
        }
        if (m.fwd.length() > 0) {
            h.fwd.setText(m.fwd);
            h.fwd.setVisibility(View.VISIBLE);
        } else {
            h.fwd.setVisibility(View.GONE);
        }
        if (m.quote.length() > 0) {
            h.quote.setText(m.quote);
            h.quote.setTag(m);
            h.quote.setVisibility(View.VISIBLE);
        } else {
            h.quote.setVisibility(View.GONE);
        }

        // Ссылки кликабельны только там, где они есть, чтобы не мешать прокрутке списка.
        // setMovementMethod на старых Android заново вызывает setText, поэтому только при смене.
        if (m.hasLinks != h.linksOn) {
            h.linksOn = m.hasLinks;
            h.body.setMovementMethod(m.hasLinks ? LinkMovementMethod.getInstance() : null);
            h.body.setFocusable(false);
            h.body.setClickable(false);
        }
        bindMedia(h, m);
        // Одно эмодзи крупно, несколько помельче; размер картинки берётся из шрифта
        float sp = (bare ? emojiSp(m.emojiOnly) : 16f) * uiScale;
        if (h.bodySp != sp) {
            h.bodySp = sp;
            h.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }
        // Под фото без подписи пустое поле текста не нужно; время рисуется в углу текста,
        // для него в конце оставлен просвет
        // С реакциями время стоит в их блоке (внизу пузыря), без них рисуется в углу текста
        boolean timeInFlow = m.reactCount > 0;
        h.time.setVisibility(timeInFlow ? View.GONE : View.VISIBLE);
        if (m.body.length() == 0) {
            h.body.setVisibility(View.GONE);
            h.body.setText(m.body);
        } else {
            h.body.setVisibility(View.VISIBLE);
            h.body.setText(timeInFlow ? m.body : withTimeGap(m, h));
        }
        h.time.setText(m.time);
        h.flowTime.setText(m.time);
        h.flowTime.setTextColor(bare ? 0xFFA0AABB : 0xFF777777);
        bindDownloadAll(h, m);
        bindReacts(h, m);
    }

    /**
     * Текст с невидимым просветом в конце, равным ширине времени: время рисуется поверх него в
     * правом нижнем углу, и под текстом не нужна отдельная строка. Обычный пробел перед просветом
     * позволяет ему уйти на следующую строку, не унося с собой последнее слово.
     */
    private CharSequence withTimeGap(MsgItem m, Holder h) {
        int w = (int) Math.ceil(h.time.getPaint().measureText(m.time)) + dp(2);
        if (m.bodyGap != null && m.gapWidth == w) {
            return m.bodyGap;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(m.body);
        sb.append(' ');
        sb.append('\u00A0');
        sb.setSpan(new FitTextView.Gap(w), sb.length() - 1, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        SpannedString done = new SpannedString(sb); // неизменяемый: TextView не копирует его при каждом показе
        m.bodyGap = done;
        m.gapWidth = w;
        return done;
    }

    /** У всех превью в блоке уже есть картинка (иначе пересборка заново запросит недогруженные). */
    private static boolean allThumbsLoaded(ViewGroup box) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View c = box.getChildAt(i);
            if (c instanceof ViewGroup) {
                if (!allThumbsLoaded((ViewGroup) c)) {
                    return false;
                }
            } else if (c instanceof ImageView && ((ImageView) c).getDrawable() == null) {
                return false;
            }
        }
        return true;
    }

    /** Что должен показывать блок превью у сообщения: id картинок и их размеры. Пустая строка: ничего. */
    private String mediaKey(MsgItem m) {
        if (!m.downloadable && m.parts == null) {
            return "";
        }
        ArrayList<MsgItem.Part> all = partsOf(m);
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < all.size(); i++) {
            MsgItem.Part p = all.get(i);
            if (p.image) {
                sb.append(p.id).append(':').append(p.w).append('x').append(p.h).append(';');
            }
        }
        return sb.length() == 0 ? "" : sb.append(imgMax).toString();
    }

    /** Один обработчик на все имена авторов: какое сообщение, берётся из тега вьюхи. */
    private final View.OnClickListener senderClick = new View.OnClickListener() {
        public void onClick(View v) {
            MsgItem m = (MsgItem) v.getTag();
            if (m == null) {
                return;
            }
            Intent i = new Intent(ChatActivity.this, ChatInfoActivity.class);
            i.putExtra(ChatInfoActivity.EXTRA_ID, m.senderId);
            i.putExtra(ChatInfoActivity.EXTRA_TITLE, m.sender);
            i.putExtra(ChatInfoActivity.EXTRA_WRITE, true);
            startActivity(i);
        }
    };

    private void bindReacts(Holder h, MsgItem m) {
        if (m.reactCount <= 0) {
            if (h.reactLook != 0) {
                h.reactLook = 0;
                h.reactBox.setVisibility(View.GONE);
            }
            return;
        }
        boolean bare = m.emojiOnly > 0;
        int look = bare ? 2 : 1;
        CharSequence[] labels = reactLabels(m);
        // Капсул ровно столько, сколько реакций: недостающие создаём, лишние прячем
        while (h.reactChips.size() < labels.length) {
            TextView chip = new TextView(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(7), dp(2), dp(9), dp(2));
            chip.setSingleLine(true);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * uiScale);
            h.reactBox.addView(chip, h.reactBox.getChildCount() - 1); // время остаётся последним
            h.reactChips.add(chip);
            h.reactLook = 0; // новый вид капсулы надо оформить
        }
        if (h.reactLook != look) {
            h.reactLook = look;
            for (int i = 0; i < h.reactChips.size(); i++) {
                h.reactChips.get(i).setBackgroundResource(bare ? R.drawable.react_bg_bare : R.drawable.react_bg);
                h.reactChips.get(i).setTextColor(bare ? 0xFFDDE6F5 : 0xFF333333);
                h.reactChips.get(i).setPadding(dp(7), dp(2), dp(9), dp(2)); // фон сбрасывает отступы
            }
        }
        for (int i = 0; i < h.reactChips.size(); i++) {
            TextView chip = h.reactChips.get(i);
            if (i < labels.length) {
                chip.setText(labels[i]);
                chip.setVisibility(View.VISIBLE);
            } else {
                chip.setVisibility(View.GONE);
            }
        }
        h.reactBox.setVisibility(View.VISIBLE);
        h.reactBox.invalidateRows();
    }

    /** Подписи капсул ("эмодзи число"), собираются один раз на сообщение и переиспользуются. */
    private CharSequence[] reactLabels(MsgItem m) {
        if (m.reactLabels != null) {
            return m.reactLabels;
        }
        CharSequence[] out;
        if (m.reactEmoji == null || m.reactEmoji.length == 0) {
            out = new CharSequence[] {reactLabel("\u2764\uFE0F", m.reactCount)};
        } else {
            int n = m.reactEmoji.length;
            out = new CharSequence[n];
            for (int i = 0; i < n; i++) {
                out[i] = reactLabel(m.reactEmoji[i], m.reactNum[i]);
            }
        }
        m.reactLabels = out;
        return out;
    }

    private static CharSequence reactLabel(String emoji, int count) {
        SpannableStringBuilder sb = new SpannableStringBuilder(EmojiMap.forceImage(emoji));
        sb.append(' ').append(String.valueOf(count));
        EmojiMap.addSpans(sb);
        return new SpannedString(sb);
    }

    /** Сколько вложений группы можно скачать (кнопка "скачать все" нужна, если больше одного). */
    private static int downloadableCount(MsgItem m) {
        if (m.parts == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < m.parts.size(); i++) {
            if (m.parts.get(i).downloadable) {
                n++;
            }
        }
        return n;
    }

    private void bindDownloadAll(Holder h, final MsgItem m) {
        int n = downloadableCount(m);
        if (n < 2) {
            h.btnAll.setVisibility(View.GONE);
            h.allFor = null;
            return;
        }
        h.btnAll.setVisibility(View.VISIBLE);
        if (h.allFor != m) {
            h.allFor = m;
            h.btnAll.setText(getString(R.string.btn_download_all) + " (" + n + ")");
            h.btnAll.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    ArrayList<MsgItem.Part> all = new ArrayList<MsgItem.Part>();
                    for (int i = 0; i < m.parts.size(); i++) {
                        if (m.parts.get(i).downloadable) {
                            all.add(m.parts.get(i));
                        }
                    }
                    Downloader.startAll(ChatActivity.this, api, peerId, all);
                }
            });
        }
    }

    /** Размер шрифта (sp) для сообщения из n эмодзи: 1 крупно, чем больше, тем мельче. */
    private static float emojiSp(int n) {
        return n == 1 ? 40f : n == 2 ? 32f : n == 3 ? 28f : 24f;
    }

    /** Осветляет цвет: подпись автора рассчитана на светлый пузырь, а без него фон тёмный. */
    private static int lighten(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r += (255 - r) * 55 / 100;
        g += (255 - g) * 55 / 100;
        b += (255 - b) * 55 / 100;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Все части сообщения: у склеенной группы её вложения, у одиночного сообщения оно само. */
    private static ArrayList<MsgItem.Part> partsOf(MsgItem m) {
        if (m.parts != null) {
            return m.parts;
        }
        ArrayList<MsgItem.Part> one = new ArrayList<MsgItem.Part>();
        if (m.downloadable) {
            one.add(m.toPart());
        }
        return one;
    }

    /** Отпускает загрузки картинок, которые больше не нужны, и убирает старые превью из бабла. */
    private void clearMedia(ViewGroup box) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View c = box.getChildAt(i);
            if (c instanceof ViewGroup) {
                clearMedia((ViewGroup) c);
            } else {
                c.setTag(null);
            }
        }
        box.removeAllViews();
    }

    /**
     * Превью фото: одно показывается по пропорциям оригинала (по умолчанию 4:3), несколько
     * складываются сеткой квадратов по два в ряд. Картинки подгружаются в фоне.
     */
    private void bindMedia(Holder h, MsgItem m) {
        // Список обновляется целиком (опрос, реакции, прокрутка), и пересборка одинаковых превью
        // давала мигание: на миг показывалась серая заглушка. Если то же самое уже на месте, не трогаем.
        String key = mediaKey(m);
        if (key.equals(h.mediaKey) && allThumbsLoaded(h.media)) {
            return;
        }
        h.mediaKey = key;
        if (!m.downloadable && m.parts == null) {
            // Обычный текст: вложений нет, ничего не создаём (это самый частый случай)
            if (h.media.getChildCount() > 0) {
                clearMedia(h.media);
            }
            h.media.setVisibility(View.GONE);
            return;
        }
        clearMedia(h.media);
        ArrayList<MsgItem.Part> imgs = new ArrayList<MsgItem.Part>();
        ArrayList<MsgItem.Part> all = partsOf(m);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).image) {
                imgs.add(all.get(i));
            }
        }
        if (imgs.isEmpty()) {
            h.media.setVisibility(View.GONE);
            return;
        }
        h.media.setVisibility(View.VISIBLE);

        if (imgs.size() == 1) {
            MsgItem.Part p = imgs.get(0);
            int w = imgMax;
            int hgt = imgMax * 3 / 4;
            if (p.w > 0 && p.h > 0) {
                if (p.w >= p.h) {
                    hgt = Math.max(1, (int) ((long) imgMax * p.h / p.w));
                } else {
                    hgt = imgMax;
                    w = Math.max(1, (int) ((long) imgMax * p.w / p.h));
                }
            }
            h.media.addView(makeThumb(p, w, hgt, imgMax, ImageView.ScaleType.FIT_CENTER),
                    new LinearLayout.LayoutParams(w, hgt));
            return;
        }

        int gap = dp(2);
        int tile = (imgMax - gap) / 2;
        int request = Math.min(imgMax, tile * 3 / 2);
        for (int i = 0; i < imgs.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 2 && j < imgs.size(); j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tile, tile);
                lp.rightMargin = j == i ? gap : 0;
                row.addView(makeThumb(imgs.get(j), tile, tile, request, ImageView.ScaleType.CENTER_CROP), lp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = i + 2 < imgs.size() ? gap : 0;
            h.media.addView(row, rlp);
        }
    }

    /** Одно превью: касание открывает фото на весь экран. request это размер запроса к серверу. */
    private ImageView makeThumb(final MsgItem.Part p, int w, int hgt, int request, ImageView.ScaleType type) {
        ImageView iv = new ImageView(this);
        iv.setBackgroundColor(0x1F000000); // полупрозрачная: на любом пузыре заглушка почти незаметна
        iv.setScaleType(type);
        if (Api.sdkLevel() >= 4) {
            iv.setContentDescription(getString(R.string.preview_photo)); // метод есть с API 4
        }
        iv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openViewer(p);
            }
        });
        // rprev: JPEG, вписать в request x request
        images.load(iv, peerId + "_" + p.id + "_" + request,
                "file.php?c=" + Api.enc(peerId) + "&m=" + p.id + "&p=rprev&s=" + request);
        return iv;
    }

    // ---------- Меню сообщения (долгое касание) ----------

    private boolean showMessageMenu(final MsgItem m) {
        if (m.service || m.pending || m.id <= 0) {
            return false;
        }
        final ArrayList<Integer> acts = new ArrayList<Integer>();
        final ArrayList<CharSequence> names = new ArrayList<CharSequence>();
        final ArrayList<Integer> icons = new ArrayList<Integer>();   // 0: без иконки
        if (inputBar.getVisibility() == View.VISIBLE) {
            acts.add(ACT_REPLY);
            names.add(getString(R.string.msg_reply));
            icons.add(R.drawable.ic_reply);
        }
        if (m.out && m.body.length() > 0) {
            acts.add(ACT_EDIT);
            names.add(getString(R.string.msg_edit));
            icons.add(R.drawable.ic_edit);
        }
        if (m.out) {
            acts.add(ACT_DELETE);
            names.add(getString(R.string.msg_delete));
            icons.add(R.drawable.ic_delete);
        }
        acts.add(ACT_COPY);
        names.add(getString(R.string.msg_copy));
        icons.add(R.drawable.ic_copy);
        acts.add(ACT_PIN);
        names.add(getString(R.string.msg_pin));
        icons.add(R.drawable.ic_pin);
        final LayoutInflater inflater = getLayoutInflater();
        ListAdapter ad = new BaseAdapter() {
            public int getCount() {
                return names.size();
            }

            public Object getItem(int position) {
                return Integer.valueOf(position);
            }

            public long getItemId(int position) {
                return position;
            }

            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView != null ? convertView : Ui.scaleTree(inflater.inflate(R.layout.attach_row, null));
                ImageView iv = (ImageView) v.findViewById(R.id.attachIcon);
                int icon = icons.get(position).intValue();
                if (icon == 0) {
                    iv.setImageDrawable(null);
                } else {
                    iv.setImageResource(icon);
                }
                ((TextView) v.findViewById(R.id.attachText)).setText(names.get(position));
                return v;
            }
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.msg_actions)
                .setAdapter(ad, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        int act = acts.get(which).intValue();
                        if (act == ACT_REPLY) {
                            setReplyTo(m);
                        } else if (act == ACT_EDIT) {
                            startEdit(m);
                        } else if (act == ACT_DELETE) {
                            confirmDelete(m);
                        } else if (act == ACT_COPY) {
                            copyMessage(m);
                        } else {
                            pinMessage(m);
                        }
                    }
                })
                .show();
        return true;
    }

    /** Режим редактирования: текст сообщения попадает в поле, отправка меняет само сообщение. */
    private void startEdit(MsgItem m) {
        setReplyTo(null);
        editingMsg = m;
        String body = m.body.toString();
        editMessage.setText(body);
        editMessage.setSelection(editMessage.getText().length());
        String snip = body.replace('\n', ' ').trim();
        if (snip.length() > 80) {
            snip = snip.substring(0, 80) + "\u2026";
        }
        replyText.setText(getString(R.string.msg_editing) + ": " + snip);
        replyBar.setVisibility(View.VISIBLE);
        editMessage.requestFocus();
    }

    /** Выход из режима редактирования без сохранения. */
    private void cancelEdit() {
        editingMsg = null;
        editMessage.setText("");
        replyBar.setVisibility(View.GONE);
    }

    private void sendEdit() {
        if (sending || editingMsg == null) {
            return;
        }
        final MsgItem target = editingMsg;
        final String text = editMessage.getText().toString().trim();
        if (text.length() == 0) {
            return;
        }
        if (text.equals(target.body.toString().trim())) {
            cancelEdit();
            return;
        }
        sending = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    api.call("editMessage", "peer", peerId, "id", String.valueOf(target.id), "text", text);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            sending = false;
                            cancelEdit();
                            refreshAfterSend(0);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            sending = false;
                            if (Errors.isSessionError(e)) {
                                Api.clearSession(ChatActivity.this);
                                setResult(RESULT_SESSION_EXPIRED);
                                finish();
                                return;
                            }
                            // Текст остаётся в поле, чтобы его можно было отправить снова
                            Toast.makeText(ChatActivity.this,
                                    getString(R.string.err_edit_failed) + ": "
                                            + Errors.text(ChatActivity.this, e),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /** Показывает панель ответа над полем (null: убрать). */
    private void setReplyTo(MsgItem m) {
        if (m != null && editingMsg != null) {
            editingMsg = null;
            editMessage.setText("");
        }
        replyTo = m;
        if (m == null) {
            replyBar.setVisibility(View.GONE);
            return;
        }
        String snip = m.body != null && m.body.length() > 0 ? m.body.toString()
                : (m.mediaLabel.length() > 0 ? m.mediaLabel : m.fileName);
        snip = snip.replace('\n', ' ').trim();
        if (snip.length() > 80) {
            snip = snip.substring(0, 80) + "\u2026";
        }
        replyText.setText(m.sender.length() > 0 ? m.sender + ": " + snip : snip);
        replyBar.setVisibility(View.VISIBLE);
    }

    @SuppressWarnings("deprecation")
    private void copyMessage(MsgItem m) {
        String text = m.body == null ? "" : m.body.toString().trim();
        if (text.length() == 0) {
            text = m.quote.length() > 0 ? m.quote : (m.fileName.length() > 0 ? m.fileName : m.mediaLabel);
        }
        if (text == null || text.length() == 0) {
            Toast.makeText(this, R.string.msg_nothing_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.text.ClipboardManager cm = (android.text.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setText(text);
            Toast.makeText(this, R.string.msg_copied, Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, String.valueOf(e), Toast.LENGTH_LONG).show();
        }
    }

    /** id всех сообщений бабла: у альбома их несколько. */
    private static String idsOf(MsgItem m) {
        if (m.parts == null) {
            return String.valueOf(m.id);
        }
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < m.parts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(m.parts.get(i).id);
        }
        return sb.toString();
    }

    private void confirmDelete(final MsgItem m) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.msg_delete_confirm)
                .setPositiveButton(R.string.msg_delete, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        deleteMessage(m);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteMessage(final MsgItem m) {
        final String ids = idsOf(m);
        new Thread(new Runnable() {
            public void run() {
                Exception err = null;
                try {
                    api.call("deleteMessage", "peer", peerId, "id", ids, "revoke", "1");
                } catch (Exception e) {
                    err = e;
                }
                final Exception fe = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        if (fe != null) {
                            Toast.makeText(ChatActivity.this, Errors.text(ChatActivity.this, fe), Toast.LENGTH_LONG).show();
                            return;
                        }
                        JSONArray arr = new JSONArray();
                        String[] parts = ids.split(",");
                        for (int i = 0; i < parts.length; i++) {
                            arr.put(Integer.parseInt(parts[i]));
                        }
                        if (applyDelete(arr)) {
                            adapter.notifyDataSetChanged();
                        }
                        loadPinned();
                    }
                });
            }
        }).start();
    }

    private void pinMessage(final MsgItem m) {
        new Thread(new Runnable() {
            public void run() {
                Exception err = null;
                try {
                    api.call("pinMessage", "peer", peerId, "id", String.valueOf(m.id));
                } catch (Exception e) {
                    err = e;
                }
                final Exception fe = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        if (fe != null) {
                            Toast.makeText(ChatActivity.this, Errors.text(ChatActivity.this, fe), Toast.LENGTH_LONG).show();
                            return;
                        }
                        Toast.makeText(ChatActivity.this, R.string.msg_pinned_ok, Toast.LENGTH_SHORT).show();
                        loadPinned();
                    }
                });
            }
        }).start();
    }

    // ---------- Меню ----------

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
            loadInitial(false);
            loadPinned();
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
        visible = true;
        if (positioned) {
            if (pausedOnce && !hasMoreNewer) {
                refreshAfterSend(0); // пока экрана не было, могли прийти сообщения
            }
            startPolling();
        }
    }

    @Override
    protected void onPause() {
        visible = false;
        stopPolling();
        pausedOnce = true;
        super.onPause();
    }
}
