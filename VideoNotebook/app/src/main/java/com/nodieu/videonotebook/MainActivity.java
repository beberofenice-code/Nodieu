package com.nodieu.videonotebook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.format.DateFormat;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int FILTER_ALL = 0, FILTER_DRAFT = 1, FILTER_PUB = 2;
    private static final String[] PLATFORMS = {
            "TikTok", "YouTube", "Facebook", "Instagram", "X (Twitter)", "Snapchat"
    };

    private VideoStore store;
    private List<VideoItem> videos = new ArrayList<VideoItem>();
    private List<VideoItem> shown = new ArrayList<VideoItem>();
    private int filter = FILTER_ALL;

    private ListView listView;
    private View emptyView;
    private TextView emptyTitle, emptyText, countAll, countPub, countDraft;
    private Button filterAll, filterDraft, filterPub;
    private VideoAdapter adapter;

    // Thumbnails are made in the background so the list stays smooth.
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private final Set<String> thumbFailed = new HashSet<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new VideoStore(this);
        videos = store.load();
        filter = getPreferences(MODE_PRIVATE).getInt("filter", FILTER_ALL);

        listView = (ListView) findViewById(R.id.list);
        emptyView = findViewById(R.id.empty);
        emptyTitle = (TextView) findViewById(R.id.emptyTitle);
        emptyText = (TextView) findViewById(R.id.emptyText);
        countAll = (TextView) findViewById(R.id.countAll);
        countPub = (TextView) findViewById(R.id.countPub);
        countDraft = (TextView) findViewById(R.id.countDraft);
        filterAll = (Button) findViewById(R.id.filterAll);
        filterDraft = (Button) findViewById(R.id.filterDraft);
        filterPub = (Button) findViewById(R.id.filterPub);

        adapter = new VideoAdapter();
        listView.setAdapter(adapter);

        filterAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setFilter(FILTER_ALL); }
        });
        filterDraft.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setFilter(FILTER_DRAFT); }
        });
        filterPub.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setFilter(FILTER_PUB); }
        });
        findViewById(R.id.addBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickVideo(); }
        });

        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbExecutor.shutdownNow();
    }

    // ---------- menu ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Add video").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) { pickVideo(); return true; }
        return super.onOptionsItemSelected(item);
    }

    // ---------- list state ----------

    private void setFilter(int f) {
        filter = f;
        getPreferences(MODE_PRIVATE).edit().putInt("filter", f).apply();
        refresh();
    }

    private void refresh() {
        int pub = 0;
        for (VideoItem v : videos) if (v.published) pub++;
        countAll.setText(String.valueOf(videos.size()));
        countPub.setText(String.valueOf(pub));
        countDraft.setText(String.valueOf(videos.size() - pub));

        styleFilter(filterAll, filter == FILTER_ALL);
        styleFilter(filterDraft, filter == FILTER_DRAFT);
        styleFilter(filterPub, filter == FILTER_PUB);

        shown = new ArrayList<VideoItem>();
        for (VideoItem v : videos) {
            if (filter == FILTER_ALL || (filter == FILTER_PUB) == v.published) shown.add(v);
        }
        adapter.notifyDataSetChanged();

        boolean empty = shown.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            if (videos.isEmpty()) {
                emptyTitle.setText(R.string.empty_title);
                emptyText.setText(R.string.empty_text);
            } else if (filter == FILTER_PUB) {
                emptyTitle.setText("Nothing published yet");
                emptyText.setText("Tap “Mark published” on a video and it shows up here.");
            } else {
                emptyTitle.setText("Everything is published");
                emptyText.setText("All your videos have green boxes. Nice work.");
            }
        }
    }

    private void styleFilter(Button b, boolean on) {
        b.setBackgroundResource(on ? R.drawable.btn_accent : R.drawable.btn_outline);
        b.setTextColor(getResources().getColor(on ? android.R.color.white : R.color.ink));
    }

    private void save() {
        store.save(videos);
        refresh();
    }

    // ---------- adding a video ----------

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
                toast("That video is already in your notebook.");
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
        v.sizeBytes = size;
        v.createdAt = System.currentTimeMillis();
        showEditDialog(v, true);
    }

    // ---------- edit dialog (used for new and existing videos) ----------

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
        note.setHint("Notes (caption, hashtags, where to post…)");
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
                dateBtn.setText(planned[0] > 0 ? "📅 " + formatLongDate(planned[0]) : "📅 Choose a date");
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
                .setTitle(isNew ? "Add video" : "Edit video")
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
                        v.plannedAt = planned[0];
                        setPublished(v, pub.isChecked());
                        if (isNew) videos.add(v);
                        save();
                        if (isNew) {
                            toast("Added “" + v.title + "”");
                            listView.setSelection(Math.max(0, shown.indexOf(v)));
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
        t.setText(text.toUpperCase());
        t.setTextSize(12);
        t.setLetterSpacing(0.08f);
        t.setTextColor(getResources().getColor(R.color.muted));
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) b.append(", ");
            b.append(p);
        }
        return b.toString();
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

    private String formatLongDate(long t) {
        return DateFormat.format("EEE, d MMM yyyy", t).toString();
    }

    private void setPublished(VideoItem v, boolean on) {
        if (on && !v.published) v.publishedAt = System.currentTimeMillis();
        if (!on) v.publishedAt = 0;
        v.published = on;
    }

    // ---------- actions on a box ----------

    private void play(VideoItem v) {
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

    private void showMore(View anchor, final VideoItem v) {
        PopupMenu pm = new PopupMenu(this, anchor);
        final int idx = videos.indexOf(v);
        pm.getMenu().add(0, 1, 0, "Edit details (platform, date…)");
        pm.getMenu().add(0, 2, 1, "Move up").setEnabled(idx > 0);
        pm.getMenu().add(0, 3, 2, "Move down").setEnabled(idx < videos.size() - 1);
        pm.getMenu().add(0, 4, 3, "Remove from notebook");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1: showEditDialog(v, false); return true;
                    case 2: swap(idx, idx - 1); return true;
                    case 3: swap(idx, idx + 1); return true;
                    case 4: confirmRemove(v); return true;
                }
                return false;
            }
        });
        pm.show();
    }

    private void swap(int a, int b) {
        if (a < 0 || b < 0 || a >= videos.size() || b >= videos.size()) return;
        VideoItem t = videos.get(a);
        videos.set(a, videos.get(b));
        videos.set(b, t);
        save();
    }

    private void confirmRemove(final VideoItem v) {
        new AlertDialog.Builder(this)
                .setTitle("Remove “" + v.title + "”?")
                .setMessage("It's removed from the notebook only. The video file stays on your phone.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        videos.remove(v);
                        releaseAccess(v);
                        save();
                    }
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    private void releaseAccess(VideoItem v) {
        try {
            getContentResolver().releasePersistableUriPermission(Uri.parse(v.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            // Nothing to release.
        }
    }

    // ---------- helpers ----------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String formatDate(long t) {
        return DateFormat.getMediumDateFormat(this).format(new Date(t));
    }

    private static String formatSize(long b) {
        if (b <= 0) return "";
        if (b >= 1024L * 1024 * 1024) return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
        return String.format("%.1f MB", b / (1024.0 * 1024));
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
                        int w = 480;
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
                        if (result != null) thumbs.put(key, result); else thumbFailed.add(key);
                        if (key.equals(target.getTag()) && result != null) target.setImageBitmap(result);
                    }
                });
            }
        });
    }

    // ---------- the boxes ----------

    private class VideoAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return shown.get(p).id.hashCode(); }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) view = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_video, parent, false);
            final VideoItem v = shown.get(position);

            View box = view.findViewById(R.id.box);
            TextView chip = (TextView) view.findViewById(R.id.chip);
            TextView title = (TextView) view.findViewById(R.id.title);
            TextView note = (TextView) view.findViewById(R.id.note);
            TextView meta = (TextView) view.findViewById(R.id.meta);
            Button toggle = (Button) view.findViewById(R.id.toggle);
            Button more = (Button) view.findViewById(R.id.more);
            ImageView thumb = (ImageView) view.findViewById(R.id.thumb);

            // The box itself shows the status: green = published.
            box.setBackgroundResource(v.published ? R.drawable.box_published : R.drawable.box_draft);
            chip.setBackgroundResource(v.published ? R.drawable.chip_pub : R.drawable.chip_draft);
            chip.setTextColor(getResources().getColor(v.published ? R.color.pub : R.color.draft));
            chip.setText(v.published ? "● PUBLISHED" : "○ NOT PUBLISHED");

            title.setText(v.title);

            TextView platforms = (TextView) view.findViewById(R.id.platforms);
            boolean hasPlat = v.platforms != null && !v.platforms.isEmpty();
            platforms.setVisibility(hasPlat ? View.VISIBLE : View.GONE);
            if (hasPlat) {
                platforms.setText((v.published ? "Posted on: " : "Post on: ") + v.platforms.replace(", ", " · "));
            }

            TextView planned = (TextView) view.findViewById(R.id.planned);
            if (v.plannedAt > 0) {
                planned.setVisibility(View.VISIBLE);
                String date = formatLongDate(v.plannedAt);
                int days = daysUntil(v.plannedAt);
                int color;
                String text;
                if (v.published) {
                    text = "📅 Planned " + date;
                    color = R.color.muted;
                } else if (days < 0) {
                    text = "⚠ Planned date passed · " + date;
                    color = R.color.danger;
                } else if (days == 0) {
                    text = "📅 Post today!";
                    color = R.color.pub;
                } else if (days == 1) {
                    text = "📅 Post tomorrow · " + date;
                    color = R.color.draft;
                } else {
                    text = "📅 " + date + " · in " + days + " days";
                    color = R.color.draft;
                }
                planned.setText(text);
                planned.setTextColor(getResources().getColor(color));
            } else {
                planned.setVisibility(View.GONE);
            }

            note.setText(v.note);
            note.setVisibility(v.note == null || v.note.isEmpty() ? View.GONE : View.VISIBLE);

            StringBuilder m = new StringBuilder("Added ").append(formatDate(v.createdAt));
            if (v.published && v.publishedAt > 0) m.append("  ·  Posted ").append(formatDate(v.publishedAt));
            String size = formatSize(v.sizeBytes);
            if (!size.isEmpty()) m.append("  ·  ").append(size);
            meta.setText(m.toString());

            if (v.published) {
                toggle.setText("Mark not published");
                toggle.setBackgroundResource(R.drawable.btn_outline);
                toggle.setTextColor(getResources().getColor(R.color.ink));
            } else {
                toggle.setText("✓ Mark published");
                toggle.setBackgroundResource(R.drawable.btn_green);
                toggle.setTextColor(getResources().getColor(android.R.color.white));
            }

            toggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View b) {
                    setPublished(v, !v.published);
                    save();
                    toast(v.published ? "Marked published" : "Marked not published");
                }
            });
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View b) { showMore(b, v); }
            });
            view.findViewById(R.id.thumbWrap).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View b) { play(v); }
            });

            loadThumb(v, thumb);
            return view;
        }
    }
}
