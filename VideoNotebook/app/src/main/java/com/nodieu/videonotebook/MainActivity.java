package com.nodieu.videonotebook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;

    // Bottom-nav tabs
    private static final int TAB_ALL = 0, TAB_DRAFT = 1, TAB_PUB = 2;
    // View modes (cycled by the header button)
    private static final int VIEW_GRID3 = 0, VIEW_GRID2 = 1, VIEW_LIST = 2;
    // Sort orders
    private static final int SORT_MANUAL = 0, SORT_ADDED = 1, SORT_POSTED = 2,
            SORT_PLANNED = 3, SORT_NAME = 4, SORT_SIZE = 5;
    private static final String[] SORT_LABELS = {
            "My order", "Date added", "Date posted", "Planned date", "Name (A–Z)", "File size"
    };

    private static final String[] PLATFORMS = {
            "TikTok", "YouTube", "Facebook", "Instagram", "X (Twitter)", "Snapchat"
    };

    private VideoStore store;
    private SharedPreferences ui;
    private List<VideoItem> videos = new ArrayList<VideoItem>();
    private List<VideoItem> shown = new ArrayList<VideoItem>();
    private int tab = TAB_ALL;
    private int viewMode = VIEW_GRID3;
    private int sort = SORT_MANUAL;
    private String query = "";

    private GridView grid;
    private View header, searchRow, emptyView, emptyAdd, fabWrap, snack, bottomNav;
    private EditText searchInput;
    private ImageButton viewBtn;
    private ImageView emptyIcon;
    private TextView emptyTitle, emptyText, snackText, snackUndo;
    private final View[] navItems = new View[3];
    private final View[] navPills = new View[3];
    private final ImageView[] navIcons = new ImageView[3];
    private final TextView[] navBadges = new TextView[3];
    private final TextView[] navLabels = new TextView[3];
    private VideoAdapter adapter;

    // Detail sheet currently open (if any) and how to redraw it after a change.
    private Dialog sheet;
    private VideoItem sheetVideo;
    private Runnable sheetRebind;

    // Violet platform panel (opened by swiping up on a poster).
    private Dialog panel;
    private VideoItem panelVideo;
    private Runnable panelRebind;
    private String popPlatform; // row whose box should "pop" after a tap

    // Snackbar state: what to do when it closes without Undo.
    private Runnable snackCommit;
    private final Runnable snackTimeout = new Runnable() {
        @Override public void run() { hideSnack(true); }
    };

    // Thumbnails are made in the background so the grid stays smooth.
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private final Set<String> thumbFailed = new HashSet<String>();
    private final Map<String, Float> aspect = new HashMap<String, Float>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new VideoStore(this);
        videos = store.load();
        ui = getPreferences(MODE_PRIVATE);
        tab = ui.getInt("filter", TAB_ALL);
        viewMode = ui.getInt("viewMode", VIEW_GRID3);
        sort = ui.getInt("sort", SORT_MANUAL);

        grid = (GridView) findViewById(R.id.grid);
        header = findViewById(R.id.header);
        searchRow = findViewById(R.id.searchRow);
        searchInput = (EditText) findViewById(R.id.searchInput);
        viewBtn = (ImageButton) findViewById(R.id.viewBtn);
        emptyView = findViewById(R.id.empty);
        emptyIcon = (ImageView) findViewById(R.id.emptyIcon);
        emptyTitle = (TextView) findViewById(R.id.emptyTitle);
        emptyText = (TextView) findViewById(R.id.emptyText);
        emptyAdd = findViewById(R.id.emptyAdd);
        fabWrap = findViewById(R.id.fabWrap);
        snack = findViewById(R.id.snack);
        snackText = (TextView) findViewById(R.id.snackText);
        snackUndo = (TextView) findViewById(R.id.snackUndo);
        bottomNav = findViewById(R.id.bottomNav);

        int[][] nav = {
                {R.id.navAll, R.id.navAllPill, R.id.navAllIcon, R.id.navAllBadge, R.id.navAllLabel},
                {R.id.navDraft, R.id.navDraftPill, R.id.navDraftIcon, R.id.navDraftBadge, R.id.navDraftLabel},
                {R.id.navPub, R.id.navPubPill, R.id.navPubIcon, R.id.navPubBadge, R.id.navPubLabel},
        };
        for (int i = 0; i < 3; i++) {
            final int t = i;
            navItems[i] = findViewById(nav[i][0]);
            navPills[i] = findViewById(nav[i][1]);
            navIcons[i] = (ImageView) findViewById(nav[i][2]);
            navBadges[i] = (TextView) findViewById(nav[i][3]);
            navLabels[i] = (TextView) findViewById(nav[i][4]);
            navBadges[i].setFontFeatureSettings("tnum");
            navItems[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setTab(t, true); }
            });
        }

        adapter = new VideoAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                openSheet(shown.get(pos));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showQuickMenu(view, shown.get(pos));
                return true;
            }
        });

        // Quick swipe up that starts on a poster opens its platform panel.
        // The listener never consumes events, so normal scrolling still works.
        grid.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private long downT;
            private int downPos = AdapterView.INVALID_POSITION;

            @Override public boolean onTouch(View g, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        downT = e.getEventTime();
                        downPos = grid.pointToPosition((int) downX, (int) downY);
                        break;
                    case MotionEvent.ACTION_UP:
                        float dy = downY - e.getY();
                        float dx = Math.abs(e.getX() - downX);
                        long dt = e.getEventTime() - downT;
                        if (downPos != AdapterView.INVALID_POSITION && downPos < shown.size()
                                && dt < 350 && dy > dp(56) && dx < dy * 0.6f) {
                            final VideoItem v = shown.get(downPos);
                            grid.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            grid.post(new Runnable() {
                                @Override public void run() {
                                    grid.smoothScrollBy(0, 0); // stop the fling the swipe started
                                    openPlatformPanel(v);
                                }
                            });
                        }
                        downPos = AdapterView.INVALID_POSITION;
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        downPos = AdapterView.INVALID_POSITION;
                        break;
                }
                return false;
            }
        });

        // Keep the grid clear of the floating header as it grows (search open/closed).
        header.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                final int top = (b - t) + dp(10);
                if (grid.getPaddingTop() != top) {
                    grid.post(new Runnable() {
                        @Override public void run() {
                            grid.setPadding(grid.getPaddingLeft(), top, grid.getPaddingRight(), grid.getPaddingBottom());
                        }
                    });
                }
            }
        });
        grid.setPadding(grid.getPaddingLeft(), dp(66), grid.getPaddingRight(), dp(72 + 96));

        findViewById(R.id.fab).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickVideo(); }
        });
        emptyAdd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickVideo(); }
        });
        findViewById(R.id.searchBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSearch(searchRow.getVisibility() != View.VISIBLE); }
        });
        findViewById(R.id.searchClose).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSearch(false); }
        });
        viewBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setViewMode((viewMode + 1) % 3, true); }
        });
        findViewById(R.id.sortBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSortMenu(v); }
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                refresh();
            }
        });
        snackUndo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { /* replaced per message */ }
        });

        setViewMode(viewMode, false);
        setTab(tab, false);

        if (!videos.isEmpty() && !ui.getBoolean("tipSwipe", false)) {
            ui.edit().putBoolean("tipSwipe", true).apply();
            main.postDelayed(new Runnable() {
                @Override public void run() { showSnack("Tip: swipe up on a poster to tick its platforms", null, null); }
            }, 800);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(snackTimeout);
        if (snackCommit != null) { snackCommit.run(); snackCommit = null; }
        if (sheet != null) sheet.dismiss();
        if (panel != null) panel.dismiss();
        thumbExecutor.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (searchRow.getVisibility() == View.VISIBLE) { toggleSearch(false); return; }
        super.onBackPressed();
    }

    // =====================================================================
    // Header: search, view mode, sort
    // =====================================================================

    private void toggleSearch(boolean open) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (open) {
            searchRow.setVisibility(View.VISIBLE);
            searchInput.requestFocus();
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        } else {
            imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            searchInput.setText("");
            searchInput.clearFocus();
            searchRow.setVisibility(View.GONE);
        }
    }

    private void setViewMode(int mode, boolean animate) {
        viewMode = mode;
        ui.edit().putInt("viewMode", mode).apply();
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int cols;
        int gap;
        if (mode == VIEW_GRID3) {
            cols = clamp(widthDp / 125, 3, 6);
            gap = 8;
            viewBtn.setImageResource(R.drawable.ic_grid3);
        } else if (mode == VIEW_GRID2) {
            cols = clamp(widthDp / 190, 2, 4);
            gap = 10;
            viewBtn.setImageResource(R.drawable.ic_grid2);
        } else {
            cols = widthDp >= 720 ? 2 : 1;
            gap = 8;
            viewBtn.setImageResource(R.drawable.ic_list);
        }
        grid.setNumColumns(cols);
        grid.setHorizontalSpacing(dp(gap));
        grid.setVerticalSpacing(dp(gap));
        grid.setAdapter(adapter); // rebuild cells for the new layout
        if (animate) {
            fadeIn(grid);
            String[] names = {"Poster grid", "Large grid", "List"};
            showSnack(names[mode], null, null);
        }
    }

    private void showSortMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor, Gravity.END);
        for (int i = 0; i < SORT_LABELS.length; i++) {
            pm.getMenu().add(1, i, i, SORT_LABELS[i]).setCheckable(true).setChecked(i == sort);
        }
        pm.getMenu().setGroupCheckable(1, true, true);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                sort = item.getItemId();
                ui.edit().putInt("sort", sort).apply();
                refresh();
                fadeIn(grid);
                grid.setSelection(0);
                showSnack("Sorted by " + SORT_LABELS[sort].toLowerCase(Locale.getDefault()), null, null);
                return true;
            }
        });
        pm.show();
    }

    // =====================================================================
    // Bottom nav + list state
    // =====================================================================

    private void setTab(int t, boolean animate) {
        tab = t;
        ui.edit().putInt("filter", t).apply();
        for (int i = 0; i < 3; i++) {
            boolean on = i == t;
            navPills[i].animate().alpha(on ? 1f : 0f).scaleX(on ? 1f : 0.6f).setDuration(animate ? 180 : 0).start();
            navIcons[i].setColorFilter(color(on ? R.color.accent_light : R.color.muted));
            navLabels[i].setTextColor(color(on ? R.color.text : R.color.muted));
            navLabels[i].setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            navBadges[i].setBackgroundResource(on ? R.drawable.badge_active : R.drawable.badge);
        }
        refresh();
        if (animate) {
            grid.setSelection(0);
            fadeIn(grid);
        }
    }

    private void refresh() {
        int pub = 0;
        for (VideoItem v : videos) if (v.published) pub++;
        navBadges[TAB_ALL].setText(String.valueOf(videos.size()));
        navBadges[TAB_DRAFT].setText(String.valueOf(videos.size() - pub));
        navBadges[TAB_PUB].setText(String.valueOf(pub));

        String q = query.toLowerCase(Locale.getDefault());
        List<VideoItem> list = new ArrayList<VideoItem>();
        for (VideoItem v : videos) {
            if (tab == TAB_PUB && !v.published) continue;
            if (tab == TAB_DRAFT && v.published) continue;
            if (!q.isEmpty() && (v.title == null || !v.title.toLowerCase(Locale.getDefault()).contains(q))) continue;
            list.add(v);
        }
        sortList(list);
        shown = list;
        adapter.notifyDataSetChanged();

        boolean empty = shown.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        emptyAdd.setVisibility(View.GONE);
        if (empty) {
            if (!q.isEmpty()) {
                emptyIcon.setImageResource(R.drawable.ic_search);
                emptyTitle.setText("No matches");
                emptyText.setText("No video titles contain “" + query + "”.");
            } else if (videos.isEmpty()) {
                emptyIcon.setImageResource(R.drawable.ic_movie);
                emptyTitle.setText("Your library is empty");
                emptyText.setText("Add your first video. Every video gets a poster here, and published ones get a green border.");
                emptyAdd.setVisibility(View.VISIBLE);
            } else if (tab == TAB_PUB) {
                emptyIcon.setImageResource(R.drawable.ic_check_circle);
                emptyTitle.setText("Nothing published yet");
                emptyText.setText("Open a video and tap “Mark as Published”. It will show up here.");
            } else {
                emptyIcon.setImageResource(R.drawable.ic_clock);
                emptyTitle.setText("All caught up");
                emptyText.setText("Every video is published. Nice work.");
            }
        }

        if (panel != null && panel.isShowing()) {
            if (videos.contains(panelVideo)) {
                if (panelRebind != null) panelRebind.run();
            } else {
                panel.dismiss();
            }
        }

        if (sheet != null && sheet.isShowing()) {
            if (videos.contains(sheetVideo)) {
                if (sheetRebind != null) sheetRebind.run();
            } else {
                sheet.dismiss();
            }
        }
    }

    private void sortList(List<VideoItem> list) {
        Comparator<VideoItem> cmp = null;
        switch (sort) {
            case SORT_ADDED:
                cmp = new Comparator<VideoItem>() {
                    @Override public int compare(VideoItem a, VideoItem b) { return cmpLong(b.createdAt, a.createdAt); }
                };
                break;
            case SORT_POSTED:
                cmp = new Comparator<VideoItem>() {
                    @Override public int compare(VideoItem a, VideoItem b) {
                        if (a.published != b.published) return a.published ? -1 : 1;
                        if (a.published) return cmpLong(b.publishedAt, a.publishedAt);
                        return cmpLong(b.createdAt, a.createdAt);
                    }
                };
                break;
            case SORT_PLANNED:
                cmp = new Comparator<VideoItem>() {
                    @Override public int compare(VideoItem a, VideoItem b) {
                        if ((a.plannedAt == 0) != (b.plannedAt == 0)) return a.plannedAt == 0 ? 1 : -1;
                        return cmpLong(a.plannedAt, b.plannedAt);
                    }
                };
                break;
            case SORT_NAME:
                cmp = new Comparator<VideoItem>() {
                    @Override public int compare(VideoItem a, VideoItem b) {
                        return safe(a.title).compareToIgnoreCase(safe(b.title));
                    }
                };
                break;
            case SORT_SIZE:
                cmp = new Comparator<VideoItem>() {
                    @Override public int compare(VideoItem a, VideoItem b) { return cmpLong(b.sizeBytes, a.sizeBytes); }
                };
                break;
            default:
                return; // My order: the arranged order
        }
        Collections.sort(list, cmp);
    }

    private void save() {
        store.save(videos);
        refresh();
    }

    // =====================================================================
    // Adding a video
    // =====================================================================

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            toast("No file picker found on this phone.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            // Keep access to the video after the phone restarts.
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // Some pickers don't allow this; the video still works until the phone restarts.
        }

        for (VideoItem v : videos) {
            if (uri.toString().equals(v.uri)) {
                showSnack("That video is already in your library", null, null);
                return;
            }
        }

        String name = "Untitled video";
        long size = 0;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && !c.isNull(n)) name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
            }
        } catch (Exception e) {
            // Name/size are just nice-to-have.
        } finally {
            if (c != null) c.close();
        }

        VideoItem v = new VideoItem();
        v.id = UUID.randomUUID().toString();
        v.uri = uri.toString();
        v.title = name.replaceAll("\\.[^.]+$", "").replaceAll("[_-]+", " ").trim();
        v.note = "";
        v.platforms = "";
        v.sizeBytes = size;
        v.createdAt = System.currentTimeMillis();
        showEditDialog(v, true);
    }

    // =====================================================================
    // Edit dialog (new and existing videos): title, notes, platforms, planned date, status
    // =====================================================================

    private void showEditDialog(final VideoItem v, final boolean isNew) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        final EditText title = new EditText(this);
        title.setHint("Title");
        title.setSingleLine(true);
        title.setText(v.title);
        box.addView(title);

        final EditText note = new EditText(this);
        note.setHint("Notes (caption, hashtags…)");
        note.setMinLines(2);
        note.setText(v.note);
        box.addView(note);

        // --- Platforms ---
        box.addView(sectionLabel("Platform"));
        List<String> current = new ArrayList<String>();
        if (v.platforms != null) {
            for (String p : v.platforms.split(",")) {
                if (!p.trim().isEmpty()) current.add(p.trim());
            }
        }
        final List<CheckBox> platformBoxes = new ArrayList<CheckBox>();
        LinearLayout row = null;
        for (int i = 0; i < PLATFORMS.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row);
            }
            CheckBox cb = new CheckBox(this);
            cb.setText(PLATFORMS[i]);
            cb.setChecked(current.remove(PLATFORMS[i]));
            row.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            platformBoxes.add(cb);
        }
        final EditText other = new EditText(this);
        other.setHint("Other platform (optional)");
        other.setSingleLine(true);
        other.setText(join(current));
        box.addView(other);

        // --- Planned posting date ---
        box.addView(sectionLabel("Planned posting date"));
        final long[] planned = { v.plannedAt };
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        final Button dateBtn = new Button(this);
        dateBtn.setAllCaps(false);
        final Button clearBtn = new Button(this);
        clearBtn.setAllCaps(false);
        clearBtn.setText("Remove");
        dateRow.addView(dateBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        dateRow.addView(clearBtn);
        box.addView(dateRow);
        final Runnable showDate = new Runnable() {
            @Override public void run() {
                dateBtn.setText(planned[0] > 0 ? formatLongDate(planned[0]) : "Choose a date");
                clearBtn.setVisibility(planned[0] > 0 ? View.VISIBLE : View.GONE);
            }
        };
        showDate.run();
        dateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View b) {
                Calendar c = Calendar.getInstance();
                if (planned[0] > 0) c.setTimeInMillis(planned[0]);
                new DatePickerDialog(MainActivity.this, new DatePickerDialog.OnDateSetListener() {
                    @Override public void onDateSet(DatePicker dp, int y, int m, int d) {
                        planned[0] = startOfDay(y, m, d);
                        showDate.run();
                    }
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
            }
        });
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View b) {
                planned[0] = 0;
                showDate.run();
            }
        });

        final CheckBox pub = new CheckBox(this);
        pub.setText("Already published");
        pub.setChecked(v.published);
        LinearLayout.LayoutParams pubLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pubLp.topMargin = dp(10);
        box.addView(pub, pubLp);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle(isNew ? "Add video" : "Edit details")
                .setView(scroll)
                .setPositiveButton(isNew ? "Add" : "Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String t = title.getText().toString().trim();
                        v.title = t.isEmpty() ? "Untitled video" : t;
                        v.note = note.getText().toString().trim();
                        List<String> chosen = new ArrayList<String>();
                        for (CheckBox cb : platformBoxes) {
                            if (cb.isChecked()) chosen.add(cb.getText().toString());
                        }
                        for (String p : other.getText().toString().split(",")) {
                            if (!p.trim().isEmpty()) chosen.add(p.trim());
                        }
                        v.platforms = join(chosen);
                        v.postedOn.keySet().retainAll(chosen);
                        v.plannedAt = planned[0];
                        setPublished(v, pub.isChecked());
                        if (isNew) {
                            videos.add(v);
                            save();
                            showSnack("Added “" + v.title + "”", new Runnable() {
                                @Override public void run() {
                                    videos.remove(v);
                                    releaseAccess(v);
                                    save();
                                }
                            }, null);
                            int pos = shown.indexOf(v);
                            if (pos >= 0) grid.smoothScrollToPosition(pos);
                        } else {
                            save();
                            showSnack("Saved", null, null);
                        }
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (isNew) releaseAccess(v);
                    }
                })
                .show();
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text.toUpperCase(Locale.getDefault()));
        t.setTextSize(12);
        t.setLetterSpacing(0.08f);
        t.setTextColor(color(R.color.muted));
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    // =====================================================================
    // Actions (shared by the detail sheet and the quick menu)
    // =====================================================================

    private void setPublished(VideoItem v, boolean on) {
        if (on && !v.published) v.publishedAt = System.currentTimeMillis();
        if (!on) v.publishedAt = 0;
        v.published = on;
    }

    private void togglePublished(final VideoItem v) {
        final boolean oldPub = v.published;
        final long oldAt = v.publishedAt;
        setPublished(v, !oldPub);
        save();
        showSnack(v.published ? "Marked as published" : "Marked not published", new Runnable() {
            @Override public void run() {
                v.published = oldPub;
                v.publishedAt = oldAt;
                save();
            }
        }, null);
    }

    /** Removes from the notebook at once, with Undo. The video file on the phone is never touched. */
    private void removeWithUndo(final VideoItem v) {
        final int idx = videos.indexOf(v);
        if (idx < 0) return;
        videos.remove(idx);
        save();
        showSnack("Removed “" + v.title + "”", new Runnable() {
            @Override public void run() {
                videos.add(Math.min(idx, videos.size()), v);
                save();
            }
        }, new Runnable() {
            @Override public void run() { releaseAccess(v); }
        });
    }

    private void confirmRemove(final VideoItem v) {
        new AlertDialog.Builder(this)
                .setTitle("Remove “" + v.title + "”?")
                .setMessage("It's removed from the notebook only. The video file stays on your phone.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (sheet != null) sheet.dismiss();
                        removeWithUndo(v);
                    }
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    private void move(VideoItem v, int dir) {
        if (sort != SORT_MANUAL) {
            showSnack("Switch sort to “My order” to arrange videos", null, null);
            return;
        }
        int a = videos.indexOf(v);
        int b = a + dir;
        if (a < 0 || b < 0 || b >= videos.size()) return;
        VideoItem t = videos.get(a);
        videos.set(a, videos.get(b));
        videos.set(b, t);
        save();
        showSnack(dir < 0 ? "Moved earlier" : "Moved later", null, null);
    }

    private void playExternal(VideoItem v) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(v.uri), "video/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("No video player found on this phone.");
        } catch (SecurityException e) {
            toast("This video can't be opened anymore. It may have been moved or deleted.");
        }
    }

    private void share(VideoItem v) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("video/*");
        i.putExtra(Intent.EXTRA_STREAM, Uri.parse(v.uri));
        i.putExtra(Intent.EXTRA_TITLE, v.title);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Share video"));
        } catch (Exception e) {
            toast("This video can't be shared.");
        }
    }

    private void releaseAccess(VideoItem v) {
        try {
            getContentResolver().releasePersistableUriPermission(Uri.parse(v.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            // Nothing to release.
        }
    }

    /** Long-press or ⋮ on a poster: one-second actions without opening the player. */
    private void showQuickMenu(View anchor, final VideoItem v) {
        PopupMenu pm = new PopupMenu(this, anchor, Gravity.END);
        pm.getMenu().add(0, 1, 0, v.published ? "Mark not published" : "Mark as published");
        pm.getMenu().add(0, 6, 1, "Platforms");
        pm.getMenu().add(0, 2, 1, "Open details");
        pm.getMenu().add(0, 3, 2, "Edit details");
        pm.getMenu().add(0, 4, 3, "Share");
        pm.getMenu().add(0, 5, 4, "Remove");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1: togglePublished(v); return true;
                    case 2: openSheet(v); return true;
                    case 3: showEditDialog(v, false); return true;
                    case 4: share(v); return true;
                    case 5: removeWithUndo(v); return true;
                    case 6: openPlatformPanel(v); return true;
                }
                return false;
            }
        });
        pm.show();
    }

    // =====================================================================
    // Detail bottom sheet
    // =====================================================================

    private void openSheet(final VideoItem v) {
        if (sheet != null) sheet.dismiss();
        final Dialog d = new Dialog(this, R.style.SheetDialog);
        final View root = LayoutInflater.from(this).inflate(R.layout.sheet_video, null);
        d.setContentView(root);
        d.setCanceledOnTouchOutside(true);

        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        int sheetW = wide ? dp(560) : getResources().getDisplayMetrics().widthPixels;
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(wide ? sheetW : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(wide ? Gravity.CENTER : Gravity.BOTTOM);
        }
        if (wide) root.setBackgroundResource(R.drawable.sheet_bg_round);

        // --- Video preview sized to the video's shape ---
        final FrameLayout videoBox = (FrameLayout) root.findViewById(R.id.videoBox);
        final ImageView thumb = (ImageView) root.findViewById(R.id.sheetThumb);
        final VideoView video = (VideoView) root.findViewById(R.id.sheetVideo);
        final ImageView playBtn = (ImageView) root.findViewById(R.id.sheetPlay);
        videoBox.setClipToOutline(true);
        Float ratio = aspect.get(v.uri);
        float r = ratio != null ? ratio : 16f / 9f;
        int boxW = sheetW - dp(32);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.45f);
        videoBox.getLayoutParams().height = clamp(Math.round(boxW * r), dp(180), maxH);
        Bitmap cached = thumbs.get(v.uri);
        if (cached != null) thumb.setImageBitmap(cached);

        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View b) {
                playBtn.setVisibility(View.GONE);
                video.setVisibility(View.VISIBLE);
                MediaController mc = new MediaController(MainActivity.this);
                mc.setAnchorView(videoBox);
                video.setMediaController(mc);
                video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override public void onPrepared(MediaPlayer mp) { thumb.setVisibility(View.INVISIBLE); }
                });
                video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override public void onCompletion(MediaPlayer mp) { playBtn.setVisibility(View.VISIBLE); }
                });
                video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                        video.setVisibility(View.GONE);
                        thumb.setVisibility(View.VISIBLE);
                        playBtn.setVisibility(View.VISIBLE);
                        toast("Can't play it here. Opening your video player…");
                        playExternal(v);
                        return true;
                    }
                });
                try {
                    video.setVideoURI(Uri.parse(v.uri));
                    video.start();
                } catch (Exception e) {
                    playExternal(v);
                }
            }
        });

        final View status = root.findViewById(R.id.sheetStatus);
        final View statusDot = root.findViewById(R.id.sheetStatusDot);
        final TextView statusText = (TextView) root.findViewById(R.id.sheetStatusText);
        final TextView title = (TextView) root.findViewById(R.id.sheetTitle);
        final TextView platforms = (TextView) root.findViewById(R.id.sheetPlatforms);
        final TextView plannedView = (TextView) root.findViewById(R.id.sheetPlanned);
        final TextView note = (TextView) root.findViewById(R.id.sheetNote);
        final LinearLayout chips = (LinearLayout) root.findViewById(R.id.sheetChips);
        final TextView toggle = (TextView) root.findViewById(R.id.sheetToggle);
        final LinearLayout actions = (LinearLayout) root.findViewById(R.id.sheetActions);

        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View b) { togglePublished(v); }
        });
        final TextView platBtn = (TextView) root.findViewById(R.id.sheetPlatBtn);
        platBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View b) { openPlatformPanel(v); }
        });

        final View[] moveBtns = new View[2];
        addAction(actions, R.drawable.ic_edit, "Edit", new Runnable() {
            @Override public void run() { showEditDialog(v, false); }
        });
        addAction(actions, R.drawable.ic_share, "Share", new Runnable() {
            @Override public void run() { share(v); }
        });
        moveBtns[0] = addAction(actions, R.drawable.ic_up, "Earlier", new Runnable() {
            @Override public void run() { move(v, -1); }
        });
        moveBtns[1] = addAction(actions, R.drawable.ic_down, "Later", new Runnable() {
            @Override public void run() { move(v, 1); }
        });
        addAction(actions, R.drawable.ic_open, "Player", new Runnable() {
            @Override public void run() { playExternal(v); }
        });
        View del = addAction(actions, R.drawable.ic_delete, "Delete", new Runnable() {
            @Override public void run() { confirmRemove(v); }
        });
        ((ImageView) del.findViewWithTag("icon")).setColorFilter(color(R.color.danger));

        sheetRebind = new Runnable() {
            @Override public void run() {
                boolean p = v.published;
                status.setBackgroundResource(p ? R.drawable.status_pub : R.drawable.status_draft);
                statusDot.setBackgroundResource(p ? R.drawable.dot_pub : R.drawable.dot_draft);
                statusText.setText(p ? "Published" : "Not Published");
                statusText.setTextColor(color(p ? R.color.pub : R.color.draft));
                title.setText(v.title);

                boolean hasPlat = v.platforms != null && !v.platforms.isEmpty();
                platforms.setVisibility(hasPlat ? View.VISIBLE : View.GONE);
                if (hasPlat) platforms.setText((p ? "Posted on " : "Post on ") + v.platforms.replace(", ", " · "));

                CharSequence plan = plannedText(v, false);
                plannedView.setVisibility(plan == null ? View.GONE : View.VISIBLE);
                if (plan != null) {
                    plannedView.setText(plan);
                    plannedView.setTextColor(plannedColor(v));
                }

                boolean hasNote = v.note != null && !v.note.isEmpty();
                note.setVisibility(hasNote ? View.VISIBLE : View.GONE);
                note.setText(v.note);

                chips.removeAllViews();
                addChip(chips, "Added " + formatDate(v.createdAt));
                if (p && v.publishedAt > 0) addChip(chips, "Posted " + formatDate(v.publishedAt));
                String size = formatSize(v.sizeBytes);
                if (!size.isEmpty()) addChip(chips, size);

                if (p) {
                    toggle.setText("Mark Not Published");
                    toggle.setBackgroundResource(R.drawable.btn_outline);
                    toggle.setTextColor(color(R.color.text));
                } else {
                    toggle.setText("✓  Mark as Published");
                    toggle.setBackgroundResource(R.drawable.btn_pub);
                    toggle.setTextColor(0xFFFFFFFF);
                }

                List<String> plats = platformsOf(v);
                int done = postedCount(v, plats);
                platBtn.setText(plats.isEmpty() ? "+  Add platforms" : "Platforms  ·  " + done + "/" + plats.size() + " uploaded  ›");

                int idx = videos.indexOf(v);
                boolean manual = sort == SORT_MANUAL;
                moveBtns[0].setAlpha(manual && idx > 0 ? 1f : 0.35f);
                moveBtns[1].setAlpha(manual && idx < videos.size() - 1 ? 1f : 0.35f);
            }
        };
        sheetRebind.run();

        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface di) {
                try { video.stopPlayback(); } catch (Exception ignored) { }
                if (sheet == d) {
                    sheet = null;
                    sheetVideo = null;
                    sheetRebind = null;
                }
            }
        });
        sheet = d;
        sheetVideo = v;
        d.show();
    }

    private View addAction(LinearLayout parent, int icon, String label, final Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(this);
        iv.setTag("icon");
        iv.setImageResource(icon);
        iv.setColorFilter(color(R.color.text));
        iv.setBackgroundResource(R.drawable.action_circle);
        iv.setPadding(dp(12), dp(12), dp(12), dp(12));
        iv.setContentDescription(label);
        item.addView(iv, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTextColor(color(R.color.muted));
        tv.setPadding(0, dp(6), 0, 0);
        tv.setMaxLines(1);
        item.addView(tv);
        iv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View b) { action.run(); }
        });
        parent.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return item;
    }

    private void addChip(LinearLayout parent, String text) {
        TextView c = new TextView(this);
        c.setText(text);
        c.setTextSize(12);
        c.setTextColor(color(R.color.muted));
        c.setFontFeatureSettings("tnum");
        c.setBackgroundResource(R.drawable.chip_dark);
        c.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        parent.addView(c, lp);
    }

    // =====================================================================
    // Violet platform panel: one box per platform (red X = not uploaded, green check = uploaded)
    // =====================================================================

    private List<String> platformsOf(VideoItem v) {
        List<String> list = new ArrayList<String>();
        if (v.platforms != null) {
            for (String p : v.platforms.split(",")) {
                String t = p.trim();
                if (!t.isEmpty() && !list.contains(t)) list.add(t);
            }
        }
        return list;
    }

    private int postedCount(VideoItem v, List<String> plats) {
        int n = 0;
        for (String p : plats) if (v.postedOn.containsKey(p)) n++;
        return n;
    }

    private void openPlatformPanel(final VideoItem v) {
        if (panel != null) panel.dismiss();
        final Dialog d = new Dialog(this, R.style.SheetDialog);
        final View root = LayoutInflater.from(this).inflate(R.layout.sheet_platforms, null);
        d.setContentView(root);
        d.setCanceledOnTouchOutside(true);

        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(wide ? dp(520) : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(wide ? Gravity.CENTER : Gravity.BOTTOM);
        }
        if (wide) root.setBackgroundResource(R.drawable.violet_sheet_bg_round);
        root.setClipToOutline(true);

        root.findViewById(R.id.pThumbWrap).setClipToOutline(true);
        Bitmap cached = thumbs.get(v.uri);
        if (cached != null) ((ImageView) root.findViewById(R.id.pThumb)).setImageBitmap(cached);

        final TextView title = (TextView) root.findViewById(R.id.pTitle);
        final TextView count = (TextView) root.findViewById(R.id.pCount);
        final ProgressBar progress = (ProgressBar) root.findViewById(R.id.pProgress);
        final TextView hint = (TextView) root.findViewById(R.id.pHint);
        final LinearLayout rows = (LinearLayout) root.findViewById(R.id.pRows);
        final LinearLayout add = (LinearLayout) root.findViewById(R.id.pAdd);
        final TextView addLabel = (TextView) root.findViewById(R.id.pAddLabel);
        root.findViewById(R.id.pDone).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View b) { d.dismiss(); }
        });

        panelRebind = new Runnable() {
            @Override public void run() {
                title.setText(v.title);
                List<String> plats = platformsOf(v);
                int done = postedCount(v, plats);

                if (plats.isEmpty()) {
                    count.setText("No platforms yet");
                    count.setTextColor(color(R.color.muted));
                    hint.setText("Add where you'll post this video, then tick each one after you upload.");
                } else if (done == plats.size()) {
                    count.setText("All " + plats.size() + " uploaded ✓");
                    count.setTextColor(color(R.color.pub));
                    hint.setText(v.published ? "Done everywhere. This video is published." : "Done everywhere.");
                } else {
                    count.setText(done + " of " + plats.size() + " uploaded");
                    count.setTextColor(color(R.color.violet_light));
                    hint.setText("Tap a box when you've uploaded the video there.");
                }
                progress.setVisibility(plats.isEmpty() ? View.GONE : View.VISIBLE);
                progress.setProgress(plats.isEmpty() ? 0 : done * 100 / plats.size());

                rows.removeAllViews();
                for (final String name : plats) {
                    View row = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_platform, rows, false);
                    boolean posted = v.postedOn.containsKey(name);

                    TextView mono = (TextView) row.findViewById(R.id.mono);
                    styleMonogram(mono, name);
                    ((TextView) row.findViewById(R.id.name)).setText(name);
                    TextView state = (TextView) row.findViewById(R.id.state);
                    View box = row.findViewById(R.id.box);
                    ImageView boxIcon = (ImageView) row.findViewById(R.id.boxIcon);
                    if (posted) {
                        Long at = v.postedOn.get(name);
                        state.setText(at != null && at > 0 ? "Uploaded · " + formatDate(at) : "Uploaded");
                        state.setTextColor(color(R.color.pub));
                        row.setBackgroundResource(R.drawable.platform_row_done);
                        box.setBackgroundResource(R.drawable.box_check);
                        boxIcon.setImageResource(R.drawable.ic_check);
                        boxIcon.setColorFilter(0xFFFFFFFF);
                    } else {
                        state.setText("Not uploaded yet");
                        state.setTextColor(color(R.color.red));
                        row.setBackgroundResource(R.drawable.platform_row_bg);
                        box.setBackgroundResource(R.drawable.box_x);
                        boxIcon.setImageResource(R.drawable.ic_close);
                        boxIcon.setColorFilter(color(R.color.red));
                    }
                    row.setContentDescription(name + (posted ? ", uploaded" : ", not uploaded yet"));

                    View.OnClickListener tap = new View.OnClickListener() {
                        @Override public void onClick(View b) {
                            b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            togglePlatform(v, name);
                        }
                    };
                    row.setOnClickListener(tap);
                    box.setOnClickListener(tap);
                    row.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override public boolean onLongClick(View b) {
                            confirmRemovePlatform(v, name);
                            return true;
                        }
                    });

                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(68));
                    lp.bottomMargin = dp(10);
                    rows.addView(row, lp);

                    if (name.equals(popPlatform)) {
                        box.setScaleX(0.6f);
                        box.setScaleY(0.6f);
                        box.animate().scaleX(1f).scaleY(1f).setDuration(260)
                                .setInterpolator(new OvershootInterpolator(2.5f)).start();
                    }
                }
                popPlatform = null;

                add.removeAllViews();
                for (final String p : PLATFORMS) {
                    if (plats.contains(p)) continue;
                    addPlatformChip(add, "+  " + p, new Runnable() {
                        @Override public void run() { addPlatform(v, p); }
                    });
                }
                addPlatformChip(add, "+  Other…", new Runnable() {
                    @Override public void run() { askOtherPlatform(v); }
                });
                addLabel.setText(plats.isEmpty() ? "CHOOSE PLATFORMS" : "ADD A PLATFORM");
            }
        };
        panelRebind.run();

        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface di) {
                if (panel == d) {
                    panel = null;
                    panelVideo = null;
                    panelRebind = null;
                }
            }
        });
        panel = d;
        panelVideo = v;
        d.show();
    }

    private void togglePlatform(VideoItem v, String name) {
        if (v.postedOn.containsKey(name)) {
            v.postedOn.remove(name);
        } else {
            v.postedOn.put(name, System.currentTimeMillis());
            List<String> plats = platformsOf(v);
            // Every platform ticked: the video counts as published.
            if (!v.published && postedCount(v, plats) == plats.size()) setPublished(v, true);
        }
        popPlatform = name;
        save();
    }

    private void addPlatform(VideoItem v, String name) {
        List<String> plats = platformsOf(v);
        if (plats.contains(name)) return;
        plats.add(name);
        v.platforms = join(plats);
        save();
    }

    private void confirmRemovePlatform(final VideoItem v, final String name) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + name + "?")
                .setMessage("It's removed from this video's platform list only.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface di, int which) {
                        List<String> plats = platformsOf(v);
                        plats.remove(name);
                        v.platforms = join(plats);
                        v.postedOn.remove(name);
                        save();
                    }
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    private void askOtherPlatform(final VideoItem v) {
        final EditText input = new EditText(this);
        input.setHint("e.g. Threads, LinkedIn");
        input.setSingleLine(true);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Add a platform")
                .setView(wrap)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface di, int which) {
                        String name = input.getText().toString().replace(",", " ").trim();
                        if (!name.isEmpty()) addPlatform(v, name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addPlatformChip(LinearLayout parent, String text, final Runnable action) {
        TextView c = new TextView(this);
        c.setText(text);
        c.setTextSize(13);
        c.setTextColor(color(R.color.violet_light));
        c.setBackgroundResource(R.drawable.chip_violet);
        c.setPadding(dp(14), dp(8), dp(14), dp(8));
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View b) { action.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        parent.addView(c, lp);
    }

    /** Brand-colored round badge with a letter/symbol for each platform. */
    private void styleMonogram(TextView t, String name) {
        String n = name.toLowerCase(Locale.getDefault());
        int bg = color(R.color.violet);
        int fg = 0xFFFFFFFF;
        int stroke = 0;
        String glyph = name.substring(0, 1).toUpperCase(Locale.getDefault());
        if (n.contains("facebook")) { bg = 0xFF1877F2; glyph = "f"; }
        else if (n.contains("youtube")) { bg = 0xFFFF0033; glyph = "▶"; }
        else if (n.contains("tiktok")) { bg = 0xFF111111; glyph = "♪"; fg = 0xFF25F4EE; stroke = 0xFF333333; }
        else if (n.contains("instagram")) { bg = 0xFFE1306C; glyph = "◎"; }
        else if (n.startsWith("x") || n.contains("twitter")) { bg = 0xFF000000; glyph = "X"; stroke = 0xFF333333; }
        else if (n.contains("snapchat")) { bg = 0xFFFFFC00; glyph = "S"; fg = 0xFF111111; }
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(12));
        g.setColor(bg);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        t.setBackground(g);
        t.setText(glyph);
        t.setTextColor(fg);
    }

    // =====================================================================
    // Snackbar with optional Undo
    // =====================================================================

    /**
     * @param undo   run if the person taps Undo (null = no Undo button)
     * @param commit run when the message closes without Undo (e.g. finally release a removed video)
     */
    private void showSnack(String message, final Runnable undo, Runnable commit) {
        main.removeCallbacks(snackTimeout);
        if (snackCommit != null) { snackCommit.run(); }
        boolean overlay = (sheet != null && sheet.isShowing()) || (panel != null && panel.isShowing());
        if (overlay) {
            // The open sheet already shows the change; the bar would be hidden behind it.
            snackCommit = null;
            if (commit != null) commit.run();
            if (snack.getVisibility() == View.VISIBLE) hideSnack(false);
            return;
        }
        snackCommit = commit;
        snackText.setText(message);
        snackUndo.setVisibility(undo != null ? View.VISIBLE : View.GONE);
        snackUndo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View b) {
                snackCommit = null;
                if (undo != null) undo.run();
                hideSnack(false);
            }
        });
        if (snack.getVisibility() != View.VISIBLE) {
            snack.setVisibility(View.VISIBLE);
            snack.setAlpha(0f);
            snack.setTranslationY(dp(16));
            snack.animate().alpha(1f).translationY(0).setDuration(180).start();
        }
        fabWrap.animate().translationY(-dp(56)).setDuration(180).start();
        main.postDelayed(snackTimeout, undo != null ? 4500 : 2200);
    }

    private void hideSnack(boolean commit) {
        main.removeCallbacks(snackTimeout);
        if (commit && snackCommit != null) snackCommit.run();
        snackCommit = null;
        snack.animate().alpha(0f).translationY(dp(16)).setDuration(160).withEndAction(new Runnable() {
            @Override public void run() { snack.setVisibility(View.GONE); }
        }).start();
        fabWrap.animate().translationY(0).setDuration(180).start();
    }

    // =====================================================================
    // Grid cells
    // =====================================================================

    private class VideoAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return shown.get(p).id.hashCode(); }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return viewMode == VIEW_LIST ? 1 : 0; }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            boolean list = viewMode == VIEW_LIST;
            if (view == null) {
                view = LayoutInflater.from(MainActivity.this)
                        .inflate(list ? R.layout.item_row : R.layout.item_poster, parent, false);
                view.setClipToOutline(true);
                if (list) view.findViewById(R.id.thumbWrap).setClipToOutline(true);
            }
            final VideoItem v = shown.get(position);
            boolean p = v.published;

            ((FrameLayout) view).setForeground(getDrawable(p ? R.drawable.card_border_pub : R.drawable.card_border_draft));
            if (!list) ((AspectFrameLayout) view).setRatio(viewMode == VIEW_GRID2 ? 4f / 3f : 1.5f);

            View badge = view.findViewById(R.id.badge);
            View dot = view.findViewById(R.id.badgeDot);
            TextView badgeText = (TextView) view.findViewById(R.id.badgeText);
            dot.setBackgroundResource(p ? R.drawable.dot_pub : R.drawable.dot_draft);
            if (list) {
                badge.setBackgroundResource(p ? R.drawable.status_pub : R.drawable.status_draft);
                badgeText.setText(p ? "Published" : "Not Published");
                badgeText.setTextColor(color(p ? R.color.pub : R.color.draft));
            } else {
                badgeText.setText(p ? "Posted" : "Draft");
            }

            ((TextView) view.findViewById(R.id.title)).setText(v.title);

            // Micro label: size, plus the planned-date state for drafts.
            TextView label = (TextView) view.findViewById(R.id.label);
            StringBuilder sb = new StringBuilder();
            String size = formatSize(v.sizeBytes);
            if (list) {
                sb.append("Added ").append(formatDate(v.createdAt));
                if (p && v.publishedAt > 0) sb.append("  ·  Posted ").append(formatDate(v.publishedAt));
                if (!size.isEmpty()) sb.append("  ·  ").append(size);
            } else {
                sb.append(size);
            }
            CharSequence plan = p ? null : plannedText(v, true);
            if (plan != null && !list) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(plan);
            }
            label.setText(sb.toString());
            label.setTextColor(plan != null && !list ? plannedColor(v) : color(R.color.muted));
            label.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);

            List<String> plats = platformsOf(v);
            int done = postedCount(v, plats);
            if (!list) {
                View plat = view.findViewById(R.id.plat);
                plat.setVisibility(plats.isEmpty() ? View.GONE : View.VISIBLE);
                if (!plats.isEmpty()) {
                    boolean all = done == plats.size();
                    plat.setBackgroundResource(all ? R.drawable.pill_done : R.drawable.pill_violet);
                    ImageView pi = (ImageView) view.findViewById(R.id.platIcon);
                    pi.setImageResource(all ? R.drawable.ic_check : R.drawable.ic_swipe_up);
                    pi.setColorFilter(color(all ? R.color.pub : R.color.violet_light));
                    TextView pt = (TextView) view.findViewById(R.id.platText);
                    pt.setText(done + "/" + plats.size() + " posted");
                    pt.setTextColor(color(all ? R.color.pub : R.color.violet_light));
                }
            }

            if (list) {
                TextView extra = (TextView) view.findViewById(R.id.extra);
                List<String> bits = new ArrayList<String>();
                if (!plats.isEmpty()) bits.add(done + "/" + plats.size() + " posted · " + v.platforms.replace(", ", " · "));
                CharSequence lp = p ? null : plannedText(v, false);
                if (lp != null) bits.add(lp.toString());
                extra.setVisibility(bits.isEmpty() ? View.GONE : View.VISIBLE);
                extra.setText(join(bits).replace(", ", "  ·  "));
                extra.setTextColor(lp != null ? plannedColor(v) : color(R.color.accent_light));
            }

            view.findViewById(R.id.quick).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View b) { showQuickMenu(b, v); }
            });

            ImageView thumb = (ImageView) view.findViewById(R.id.thumb);
            loadThumb(v, thumb);
            return view;
        }
    }

    private void loadThumb(final VideoItem v, final ImageView target) {
        final String key = v.uri;
        target.setTag(key);
        Bitmap cached = thumbs.get(key);
        if (cached != null) { target.setImageBitmap(cached); return; }
        target.setImageDrawable(null);
        if (thumbFailed.contains(key)) return;
        final Context ctx = getApplicationContext();
        thumbExecutor.execute(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(ctx, Uri.parse(key));
                    Bitmap frame = r.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame != null) {
                        int w = 400;
                        int h = Math.max(1, frame.getHeight() * w / Math.max(1, frame.getWidth()));
                        bmp = Bitmap.createScaledBitmap(frame, w, h, true);
                        if (bmp != frame) frame.recycle();
                    }
                } catch (Exception e) {
                    bmp = null;
                } finally {
                    try { r.release(); } catch (Exception ignored) { }
                }
                final Bitmap result = bmp;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (result != null) {
                            thumbs.put(key, result);
                            aspect.put(key, result.getHeight() / (float) result.getWidth());
                        } else {
                            thumbFailed.add(key);
                        }
                        if (key.equals(target.getTag()) && result != null) {
                            target.setAlpha(0f);
                            target.setImageBitmap(result);
                            target.animate().alpha(1f).setDuration(200).start();
                        }
                    }
                });
            }
        });
    }

    // =====================================================================
    // Planned date helpers
    // =====================================================================

    /** Short ("Today", "in 3d", "Overdue") or long text for a planned date, or null when none applies. */
    private CharSequence plannedText(VideoItem v, boolean shortForm) {
        if (v.plannedAt <= 0) return null;
        String date = formatLongDate(v.plannedAt);
        if (v.published) return shortForm ? null : "Planned for " + date;
        int days = daysUntil(v.plannedAt);
        if (shortForm) {
            if (days < 0) return "Overdue";
            if (days == 0) return "Today";
            if (days == 1) return "Tomorrow";
            return "in " + days + "d";
        }
        if (days < 0) return "⚠ Planned date passed · " + date;
        if (days == 0) return "Post today!";
        if (days == 1) return "Post tomorrow · " + date;
        return "Planned " + date + " · in " + days + " days";
    }

    private int plannedColor(VideoItem v) {
        if (v.published) return color(R.color.muted);
        int days = daysUntil(v.plannedAt);
        if (days < 0) return color(R.color.danger);
        if (days == 0) return color(R.color.pub);
        return color(R.color.draft);
    }

    private static long startOfDay(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m, d, 0, 0, 0);
        return c.getTimeInMillis();
    }

    private static long today() {
        Calendar c = Calendar.getInstance();
        return startOfDay(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
    }

    /** Whole days from today to the planned date (negative = in the past). */
    private static int daysUntil(long plannedAt) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(today());
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(plannedAt);
        int days = 0;
        if (b.before(a)) {
            while (b.before(a)) { b.add(Calendar.DAY_OF_MONTH, 1); days--; }
        } else {
            while (a.before(b)) { a.add(Calendar.DAY_OF_MONTH, 1); days++; }
        }
        return days;
    }

    // =====================================================================
    // Small helpers
    // =====================================================================

    private void fadeIn(View v) {
        v.setAlpha(0.3f);
        v.animate().alpha(1f).setDuration(220).start();
    }

    private int color(int res) {
        return getResources().getColor(res);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int cmpLong(long a, long b) {
        return a < b ? -1 : (a == b ? 0 : 1);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) b.append(", ");
            b.append(p);
        }
        return b.toString();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String formatDate(long t) {
        return DateFormat.getMediumDateFormat(this).format(new Date(t));
    }

    private String formatLongDate(long t) {
        return DateFormat.format("EEE, d MMM yyyy", t).toString();
    }

    private static String formatSize(long b) {
        if (b <= 0) return "";
        if (b >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024));
        return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024));
    }
}
