package com.nodieu.videonotebook;

import org.json.JSONException;
import org.json.JSONObject;

/** One video in the notebook. The video file stays on the phone; we keep a link (uri) to it. */
public class VideoItem {
    public String id;
    public String uri;
    public String title;
    public String note;
    public boolean published;
    public long publishedAt;
    public long createdAt;
    public long sizeBytes;

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("uri", uri);
        o.put("title", title);
        o.put("note", note);
        o.put("published", published);
        o.put("publishedAt", publishedAt);
        o.put("createdAt", createdAt);
        o.put("sizeBytes", sizeBytes);
        return o;
    }

    public static VideoItem fromJson(JSONObject o) {
        VideoItem v = new VideoItem();
        v.id = o.optString("id");
        v.uri = o.optString("uri");
        v.title = o.optString("title");
        v.note = o.optString("note");
        v.published = o.optBoolean("published");
        v.publishedAt = o.optLong("publishedAt");
        v.createdAt = o.optLong("createdAt");
        v.sizeBytes = o.optLong("sizeBytes");
        return v;
    }
}
