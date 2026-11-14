package lite.abs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Single-activity Audiobookshelf client + kiosk home screen. Plain framework APIs only (minSdk 21). */
public class MainActivity extends Activity {
    private static final int BG = 0xFF1B1B1B, FG = 0xFFEEEEEE, MUTED = 0xFF999999, PANEL = 0xFF2C2C2C, ACCENT = 0xFF3D7A37;

    static final class Book { String id, title = "", author = ""; double progress; }

    /** One open playback session. */
    static final class Session {
        String id; double duration, start; Book book;
        int n; double[] off, dur; String[] url;
        double[] chStart; String[] chTitle;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private boolean testMode, lockStarted;

    // data
    private String libId;
    private final List<Book> all = new ArrayList<Book>();
    private final List<Book> cont = new ArrayList<Book>();
    private String filter = "";

    // views
    private FrameLayout root;
    private LinearLayout listScreen, miniBar;
    private ScrollView playerScreen;
    private RowAdapter adapter;
    private ImageView miniCover, cover;
    private TextView miniTitle, title, author, chapTitle, curLbl, leftLbl, bat;
    private Button miniPlay, playBtn, sleepBtn;
    private SeekBar seek;
    private boolean dragging;

    // playback
    private Session sess;
    private MediaPlayer mp;
    private int ti, pendingMs, retries, ticks, listened, sleepIdx;
    private boolean prepared, playing, wantPlay;
    private double lastPos;
    private Runnable sleepRun;
    private WifiManager.WifiLock wifiLock;
    private static final int[] SLEEPS = {0, 15, 30, 60};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        applyIntentConfig(getIntent());
        buildUi();
        applyImmersive();
        loadLibrary();
        ui.postDelayed(tick, 500);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (applyIntentConfig(i)) { stopSession(); loadLibrary(); }
    }

    /** Test hook: adb can point the app at another server (e.g. the local mock) via intent extras. */
    private boolean applyIntentConfig(Intent i) {
        String base = i == null ? null : i.getStringExtra("base");
        if (base == null) return false;
        Api.configure(base, i.getStringExtra("user"), i.getStringExtra("pass"));
        testMode = true;
        return true;
    }

    // The battery gauge is part of the system bar, which immersive mode and screen pinning hide, so draw it in the app.
    private final BroadcastReceiver batteryRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { showBattery(i); }
    };

    private void showBattery(Intent i) {
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        int pct = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
        String s = pct < 0 ? "" : pct + "%" + (charging ? " +" : "");
        int color = pct >= 0 && pct <= 15 && !charging ? 0xFFE57373 : MUTED;
        if (bat != null) { bat.setText(s); bat.setTextColor(color); bat.setVisibility(s.length() == 0 ? View.INVISIBLE : View.VISIBLE); }
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(batteryRx); } catch (IllegalArgumentException ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        showBattery(registerReceiver(batteryRx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        applyImmersive();
        if (Config.LOCK_TASK && !testMode && !lockStarted) {
            try { startLockTask(); lockStarted = true; } catch (Exception ignored) {}
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override public void onBackPressed() {
        if (playerScreen.getVisibility() == View.VISIBLE) showList(); // list is the home: nothing further back
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (sess != null) { syncNow(); }
        releasePlayer();
        io.shutdown();
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------ UI construction

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label); b.setTextColor(FG); b.setBackgroundColor(PANEL); b.setAllCaps(false);
        b.setMinWidth(dp(56)); b.setMinimumWidth(dp(56)); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight(int w, int h, float wt, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, wt);
        p.setMargins(margin, margin, margin, margin);
        return p;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        // ---- list screen
        listScreen = new LinearLayout(this);
        listScreen.setOrientation(LinearLayout.VERTICAL);
        listScreen.setPadding(dp(10), dp(10), dp(10), 0);
        listScreen.setFocusableInTouchMode(true); // keep the search box from grabbing focus (and the keyboard) on launch

        EditText search = new EditText(this);
        search.setHint("Search"); search.setHintTextColor(MUTED); search.setTextColor(FG); search.setSingleLine(true);
        search.setBackgroundColor(PANEL); search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { filter = e.toString().trim().toLowerCase(Locale.getDefault()); rebuildRows(); }
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.setMargins(0, 0, dp(76), 0); // leave the top-right corner to the battery readout
        listScreen.addView(search, searchLp);

        ListView list = new ListView(this);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Object o = adapter.rows.get(pos);
                if (o instanceof Book) play((Book) o);
            }
        });
        listScreen.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        miniBar = new LinearLayout(this);
        miniBar.setOrientation(LinearLayout.HORIZONTAL);
        miniBar.setGravity(Gravity.CENTER_VERTICAL);
        miniBar.setBackgroundColor(0xFF262626);
        miniBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        miniBar.setVisibility(View.GONE);
        miniCover = new ImageView(this);
        miniCover.setBackgroundColor(PANEL);
        miniCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniBar.addView(miniCover, new LinearLayout.LayoutParams(dp(44), dp(44)));
        miniTitle = text("", 15, FG, true);
        miniTitle.setSingleLine(true);
        miniTitle.setPadding(dp(10), 0, dp(10), 0);
        miniBar.addView(miniTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        miniPlay = button("Play", new View.OnClickListener() { @Override public void onClick(View v) { togglePlay(); } });
        miniBar.addView(miniPlay);
        miniBar.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showPlayer(); } });
        listScreen.addView(miniBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- player screen
        playerScreen = new ScrollView(this);
        playerScreen.setFillViewport(true);
        playerScreen.setVisibility(View.GONE);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        p.setPadding(dp(14), dp(10), dp(14), dp(14));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(button("< Back", new View.OnClickListener() { @Override public void onClick(View v) { showList(); } }));
        chapTitle = text("", 14, MUTED, false);
        chapTitle.setSingleLine(true);
        chapTitle.setPadding(dp(12), 0, 0, 0);
        top.addView(chapTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.setMargins(0, 0, dp(76), 0); // room for the battery readout
        p.addView(top, topLp);

        int side = Math.min(getResources().getDisplayMetrics().widthPixels * 6 / 10, getResources().getDisplayMetrics().heightPixels * 4 / 10);
        cover = new ImageView(this);
        cover.setBackgroundColor(PANEL);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(side, side);
        cp.setMargins(0, dp(10), 0, dp(10));
        p.addView(cover, cp);

        title = text("", 18, FG, true);
        title.setGravity(Gravity.CENTER);
        p.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        author = text("", 14, MUTED, false);
        author.setGravity(Gravity.CENTER);
        p.addView(author, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) { if (fromUser && sess != null) curLbl.setText(fmt(v / 1000.0 * sess.duration)); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; if (sess != null) seekTo(s.getProgress() / 1000.0 * sess.duration); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, dp(14), 0, 0);
        p.addView(seek, sp);

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        curLbl = text("0:00", 13, MUTED, false);
        leftLbl = text("", 13, MUTED, false);
        leftLbl.setGravity(Gravity.RIGHT);
        times.addView(curLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(leftLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        p.addView(times, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        row1.addView(button("-30", new View.OnClickListener() { @Override public void onClick(View v) { skip(-30); } }), weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, dp(3)));
        row1.addView(button("-10", new View.OnClickListener() { @Override public void onClick(View v) { skip(-10); } }), weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, dp(3)));
        playBtn = button("Play", new View.OnClickListener() { @Override public void onClick(View v) { togglePlay(); } });
        playBtn.setBackgroundColor(ACCENT);
        row1.addView(playBtn, weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f, dp(3)));
        row1.addView(button("+10", new View.OnClickListener() { @Override public void onClick(View v) { skip(10); } }), weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, dp(3)));
        row1.addView(button("+30", new View.OnClickListener() { @Override public void onClick(View v) { skip(30); } }), weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, dp(3)));
        LinearLayout.LayoutParams r1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1.setMargins(0, dp(12), 0, 0);
        p.addView(row1, r1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        row2.addView(button("<< Ch", new View.OnClickListener() { @Override public void onClick(View v) { chapterStep(-1); } }), weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, dp(3)));
        row2.addView(button("Chapters", new View.OnClickListener() { @Override public void onClick(View v) { chapterDialog(); } }), weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f, dp(3)));
        sleepBtn = button("Sleep: off", new View.OnClickListener() { @Override public void onClick(View v) { cycleSleep(); } });
        row2.addView(sleepBtn, weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f, dp(3)));
        row2.addView(button("Ch >>", new View.OnClickListener() { @Override public void onClick(View v) { chapterStep(1); } }), weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, dp(3)));
        p.addView(row2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        playerScreen.addView(p, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(listScreen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(playerScreen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bat = text("", 14, MUTED, true);
        bat.setBackgroundColor(0xCC1B1B1B);
        bat.setPadding(dp(8), dp(4), dp(8), dp(4));
        FrameLayout.LayoutParams batLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
        batLp.setMargins(0, dp(14), dp(10), 0);
        root.addView(bat, batLp); // last child: stays on top of both screens, like a status bar
        setContentView(root);
        listScreen.requestFocus();
    }

    private void showPlayer() { listScreen.setVisibility(View.GONE); playerScreen.setVisibility(View.VISIBLE); }

    private void showList() {
        playerScreen.setVisibility(View.GONE); listScreen.setVisibility(View.VISIBLE);
        loadProgress(); // refresh bars / continue list
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------------ list + adapter

    private final class RowAdapter extends BaseAdapter {
        final List<Object> rows = new ArrayList<Object>();
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return rows.get(i) instanceof Book ? 1 : 0; }
        @Override public boolean isEnabled(int i) { return rows.get(i) instanceof Book; }

        @Override public View getView(int pos, View v, ViewGroup parent) {
            Object o = rows.get(pos);
            if (!(o instanceof Book)) {
                TextView h = v instanceof TextView ? (TextView) v : text("", 13, MUTED, true);
                h.setPadding(dp(4), dp(14), 0, dp(6));
                h.setText(((String) o).toUpperCase(Locale.getDefault()));
                return h;
            }
            Book b = (Book) o;
            Holder h;
            if (v == null) {
                LinearLayout r = new LinearLayout(MainActivity.this);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(dp(4), dp(6), dp(4), dp(6));
                h = new Holder();
                h.img = new ImageView(MainActivity.this);
                h.img.setBackgroundColor(PANEL);
                h.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                r.addView(h.img, new LinearLayout.LayoutParams(dp(64), dp(64)));
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(12), 0, 0, 0);
                h.t = text("", 16, FG, true); h.t.setSingleLine(true);
                h.a = text("", 13, MUTED, false); h.a.setSingleLine(true);
                h.p = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
                h.p.setMax(100);
                col.addView(h.t); col.addView(h.a);
                col.addView(h.p, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
                r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                r.setTag(h);
                v = r;
            } else {
                h = (Holder) v.getTag();
            }
            h.t.setText(b.title);
            h.a.setText(b.author);
            h.p.setProgress((int) Math.round(b.progress * 100));
            h.p.setVisibility(b.progress > 0 ? View.VISIBLE : View.INVISIBLE);
            Images.load(b.id, 160, h.img);
            return v;
        }
    }

    private static final class Holder { ImageView img; TextView t, a; ProgressBar p; }

    private void rebuildRows() {
        List<Object> rows = adapter.rows;
        rows.clear();
        if (filter.length() == 0 && !cont.isEmpty()) {
            rows.add("Continue listening");
            rows.addAll(cont);
            rows.add("Library");
        }
        for (Book b : all) {
            if (filter.length() == 0 || b.title.toLowerCase(Locale.getDefault()).contains(filter) || b.author.toLowerCase(Locale.getDefault()).contains(filter)) rows.add(b);
        }
        adapter.notifyDataSetChanged();
    }

    private Book bookFrom(JSONObject it) {
        Book b = new Book();
        b.id = it.optString("id");
        JSONObject m = it.optJSONObject("media");
        JSONObject md = m == null ? null : m.optJSONObject("metadata");
        if (md != null) { b.title = md.optString("title", ""); b.author = md.optString("authorName", ""); }
        return b;
    }

    private void loadLibrary() {
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONArray libs = Api.json("GET", "/api/libraries", null).getJSONArray("libraries");
                    String id = null;
                    for (int i = 0; i < libs.length() && id == null; i++) if ("book".equals(libs.getJSONObject(i).optString("mediaType"))) id = libs.getJSONObject(i).getString("id");
                    if (id == null) throw new Exception("no book library on server");
                    libId = id;
                    final List<Book> items = new ArrayList<Book>();
                    int page = 0, total;
                    do {
                        JSONObject r = Api.json("GET", "/api/libraries/" + id + "/items?limit=200&page=" + page + "&sort=media.metadata.title&minified=1", null);
                        JSONArray res = r.getJSONArray("results");
                        for (int i = 0; i < res.length(); i++) items.add(bookFrom(res.getJSONObject(i)));
                        total = r.optInt("total", res.length());
                        page++;
                        if (res.length() == 0) break;
                    } while (items.size() < total);
                    ui.post(new Runnable() { @Override public void run() { all.clear(); all.addAll(items); loadProgress(); } });
                } catch (final Exception e) {
                    ui.post(new Runnable() { @Override public void run() { toast("Cannot load library: " + e.getMessage()); } });
                }
            }
        });
    }

    /** Refreshes per-book progress and the continue-listening shelf, then redraws the list. */
    private void loadProgress() {
        io.execute(new Runnable() {
            @Override public void run() {
                final java.util.Map<String, Double> prog = new java.util.HashMap<String, Double>();
                final List<Book> shelf = new ArrayList<Book>();
                try {
                    JSONArray mp = Api.json("GET", "/api/me", null).optJSONArray("mediaProgress");
                    if (mp != null) for (int i = 0; i < mp.length(); i++) {
                        JSONObject p = mp.getJSONObject(i);
                        if (!p.has("episodeId") || p.isNull("episodeId")) prog.put(p.optString("libraryItemId"), p.optDouble("progress", 0));
                    }
                } catch (Exception ignored) {}
                try {
                    JSONArray its = Api.json("GET", "/api/me/items-in-progress", null).optJSONArray("libraryItems");
                    if (its != null) for (int i = 0; i < its.length(); i++) {
                        JSONObject it = its.getJSONObject(i);
                        if (libId == null || libId.equals(it.optString("libraryId"))) shelf.add(bookFrom(it));
                    }
                } catch (Exception ignored) {}
                ui.post(new Runnable() {
                    @Override public void run() {
                        for (Book b : all) { Double d = prog.get(b.id); b.progress = d == null ? 0 : d; }
                        for (Book b : shelf) { Double d = prog.get(b.id); b.progress = d == null ? 0 : d; }
                        cont.clear(); cont.addAll(shelf);
                        rebuildRows();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ playback

    private static String fmt(double sec) {
        long s = Math.max(0, (long) sec), h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, x) : String.format(Locale.US, "%d:%02d", m, x);
    }

    private void play(final Book book) {
        if (sess != null && sess.book.id.equals(book.id)) { showPlayer(); return; }
        stopSession();
        toast("Loading...");
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject body = new JSONObject();
                    JSONObject di = new JSONObject();
                    di.put("clientName", "ABS Lite");
                    di.put("deviceId", "abs-lite-android");
                    body.put("deviceInfo", di);
                    body.put("forceDirectPlay", true);
                    body.put("forceTranscode", false);
                    body.put("mediaPlayer", "android-mediaplayer");
                    JSONArray mimes = new JSONArray();
                    for (String m : new String[] {"audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/ogg", "audio/flac", "audio/wav", "audio/opus"}) mimes.put(m);
                    body.put("supportedMimeTypes", mimes);
                    JSONObject s = Api.json("POST", "/api/items/" + book.id + "/play", body);

                    final Session ss = new Session();
                    ss.book = book;
                    ss.id = s.getString("id");
                    ss.duration = s.optDouble("duration", 0);
                    ss.start = s.optDouble("currentTime", s.optDouble("startTime", 0));
                    boolean transcode = s.optInt("playMethod", 0) == 2;
                    JSONArray at = s.getJSONArray("audioTracks");
                    ss.n = at.length();
                    ss.off = new double[ss.n]; ss.dur = new double[ss.n]; ss.url = new String[ss.n];
                    for (int i = 0; i < ss.n; i++) {
                        JSONObject t = at.getJSONObject(i);
                        ss.off[i] = t.optDouble("startOffset", 0);
                        ss.dur[i] = t.optDouble("duration", 0);
                        if (transcode) ss.url[i] = Api.base() + t.getString("contentUrl") + "?token=" + Api.token();
                        else ss.url[i] = Api.base() + "/public/session/" + ss.id + "/track/" + t.optInt("index", i + 1);
                    }
                    JSONArray ch = s.optJSONArray("chapters");
                    int cn = ch == null ? 0 : ch.length();
                    ss.chStart = new double[cn]; ss.chTitle = new String[cn];
                    for (int i = 0; i < cn; i++) { ss.chStart[i] = ch.getJSONObject(i).optDouble("start", 0); ss.chTitle[i] = ch.getJSONObject(i).optString("title", "Chapter " + (i + 1)); }
                    ui.post(new Runnable() { @Override public void run() { startSession(ss); } });
                } catch (final Exception e) {
                    ui.post(new Runnable() { @Override public void run() { toast("Play failed: " + e.getMessage()); } });
                }
            }
        });
    }

    private void startSession(Session s) {
        sess = s; listened = 0; retries = 0;
        title.setText(s.book.title); author.setText(s.book.author); miniTitle.setText(s.book.title);
        Images.load(s.book.id, 480, cover);
        Images.load(s.book.id, 120, miniCover);
        miniBar.setVisibility(View.VISIBLE);
        int i = 0;
        for (int k = 0; k < s.n; k++) if (s.off[k] <= s.start) i = k;
        loadTrack(i, s.start - s.off[i], true);
        showPlayer();
        updateUi();
    }

    private void loadTrack(int i, double offsetSec, boolean autoplay) {
        releasePlayer();
        ti = i; prepared = false; wantPlay = autoplay; pendingMs = (int) Math.max(0, offsetSec * 1000);
        mp = new MediaPlayer();
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer p) {
                prepared = true; retries = 0;
                if (pendingMs > 0) p.seekTo(pendingMs);
                if (wantPlay) startPlayback();
                updateUi();
            }
        });
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer p) { onTrackEnd(); }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer p, int what, int extra) { onPlayError(what, extra); return true; }
        });
        try {
            mp.setDataSource(sess.url[i]);
            mp.prepareAsync();
        } catch (Exception e) {
            toast("Cannot open audio: " + e.getMessage());
        }
    }

    private void releasePlayer() {
        playing = false; prepared = false;
        releaseWifi();
        if (mp != null) { try { mp.reset(); mp.release(); } catch (Exception ignored) {} mp = null; }
    }

    private void acquireWifi() {
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "abslite");
            wifiLock.setReferenceCounted(false);
        }
        if (!wifiLock.isHeld()) wifiLock.acquire();
    }

    private void releaseWifi() { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); }

    private void startPlayback() {
        if (mp == null || !prepared) { wantPlay = true; return; }
        try { mp.start(); playing = true; wantPlay = true; acquireWifi(); } catch (Exception ignored) {}
    }

    private void pausePlayback() {
        wantPlay = false;
        if (mp != null && prepared) { try { mp.pause(); } catch (Exception ignored) {} }
        playing = false; releaseWifi();
        syncNow();
    }

    private void togglePlay() {
        if (sess == null) return;
        if (playing || (wantPlay && !prepared)) pausePlayback(); else startPlayback();
        updateUi();
    }

    private double position() {
        if (sess == null) return 0;
        if (mp != null && prepared) { try { lastPos = sess.off[ti] + mp.getCurrentPosition() / 1000.0; } catch (Exception ignored) {} }
        else lastPos = sess.off[ti] + pendingMs / 1000.0;
        return lastPos;
    }

    private void seekTo(double t) {
        if (sess == null) return;
        t = Math.max(0, Math.min(sess.duration > 0 ? sess.duration : t, t));
        int i = 0;
        for (int k = 0; k < sess.n; k++) if (sess.off[k] <= t) i = k;
        if (i != ti) loadTrack(i, t - sess.off[i], wantPlay);
        else if (mp != null && prepared) { try { mp.seekTo((int) ((t - sess.off[i]) * 1000)); } catch (Exception ignored) {} }
        else pendingMs = (int) ((t - sess.off[i]) * 1000);
        updateUi();
    }

    private void skip(int seconds) { seekTo(position() + seconds); }

    private void onTrackEnd() {
        if (sess == null) return;
        if (ti < sess.n - 1) loadTrack(ti + 1, 0, true);
        else { playing = false; wantPlay = false; releaseWifi(); syncNow(); updateUi(); }
    }

    private void onPlayError(int what, int extra) {
        if (sess == null) return;
        boolean was = wantPlay;
        if (retries++ < 2) { loadTrack(ti, Math.max(0, lastPos - sess.off[ti]), was); return; }
        playing = false;
        toast("Audio error (" + what + "/" + extra + ")");
        updateUi();
    }

    private int chapterAt(double t) {
        int idx = -1;
        if (sess != null) for (int i = 0; i < sess.chStart.length; i++) if (sess.chStart[i] <= t + 0.01) idx = i;
        return idx;
    }

    private void chapterStep(int dir) {
        if (sess == null || sess.chStart.length == 0) return;
        double t = position();
        int ci = chapterAt(t);
        if (dir < 0) seekTo(ci >= 0 && t - sess.chStart[ci] > 3 ? sess.chStart[ci] : (ci > 0 ? sess.chStart[ci - 1] : 0));
        else if (ci + 1 < sess.chStart.length) seekTo(sess.chStart[ci + 1]);
    }

    private void chapterDialog() {
        if (sess == null || sess.chTitle.length == 0) { toast("No chapters"); return; }
        new AlertDialog.Builder(this).setTitle("Chapters").setItems(sess.chTitle, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) { seekTo(sess.chStart[which]); }
        }).show();
    }

    private void cycleSleep() {
        sleepIdx = (sleepIdx + 1) % SLEEPS.length;
        if (sleepRun != null) { ui.removeCallbacks(sleepRun); sleepRun = null; }
        int m = SLEEPS[sleepIdx];
        sleepBtn.setText("Sleep: " + (m == 0 ? "off" : m + "m"));
        if (m > 0) {
            sleepRun = new Runnable() { @Override public void run() { sleepIdx = 0; sleepBtn.setText("Sleep: off"); if (playing) pausePlayback(); updateUi(); } };
            ui.postDelayed(sleepRun, m * 60000L);
        }
    }

    // ------------------------------------------------------------------ sync + ui tick

    private void syncNow() {
        if (sess == null) return;
        final String id = sess.id;
        final double ct = position(), dur = sess.duration;
        final int sent = listened;
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject b = new JSONObject();
                    b.put("currentTime", ct); b.put("timeListened", sent); b.put("duration", dur);
                    Api.text("POST", "/api/session/" + id + "/sync", b);
                    ui.post(new Runnable() { @Override public void run() { listened = Math.max(0, listened - sent); } });
                } catch (Exception ignored) { /* retried at the next sync */ }
            }
        });
    }

    private void stopSession() {
        if (sess == null) return;
        final String id = sess.id;
        final double ct = position(), dur = sess.duration;
        final int sent = listened;
        releasePlayer();
        sess = null; miniBar.setVisibility(View.GONE);
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject b = new JSONObject();
                    b.put("currentTime", ct); b.put("timeListened", sent); b.put("duration", dur);
                    Api.text("POST", "/api/session/" + id + "/close", b);
                } catch (Exception ignored) {}
            }
        });
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            ticks++;
            if (sess != null) {
                if (playing && ticks % 2 == 0) listened++;
                if (playing && ticks % 30 == 0) syncNow();
                updateUi();
            }
            ui.postDelayed(this, 500);
        }
    };

    private void updateUi() {
        if (sess == null) return;
        double t = position(), d = sess.duration;
        if (!dragging) { seek.setProgress(d > 0 ? (int) (t / d * 1000) : 0); curLbl.setText(fmt(t)); }
        leftLbl.setText("-" + fmt(d - t));
        int ci = chapterAt(t);
        chapTitle.setText(ci >= 0 ? sess.chTitle[ci] : "");
        String label = playing ? "Pause" : "Play";
        playBtn.setText(label); miniPlay.setText(label);
    }
}
