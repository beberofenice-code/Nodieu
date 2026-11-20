package com.nodieu.videonotebook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Saves the notebook (in its arranged order) on the phone. */
public class VideoStore {
    private static final String PREFS = "video_notebook";
    private static final String KEY = "videos";
    private final SharedPreferences prefs;

    public VideoStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<VideoItem> load() {
        List<VideoItem> list = new ArrayList<VideoItem>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                list.add(VideoItem.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            // Corrupt data: start with an empty notebook rather than crash.
        }
        return list;
    }

    public void save(List<VideoItem> list) {
        JSONArray arr = new JSONArray();
        try {
            for (VideoItem v : list) arr.put(v.toJson());
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(KEY, arr.toString()).apply();
    }
}
