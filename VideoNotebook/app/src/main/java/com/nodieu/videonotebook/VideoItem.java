package com.nodieu.videonotebook;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

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
    /** Where it is (or will be) posted, comma separated, e.g. "TikTok, YouTube". */
    public String platforms;
    /** Planned posting date (start of day, in millis). 0 = no date set. */
    public long plannedAt;
    /** Folder this video is in. Empty = Unsorted. */
    public String folderId = "";
    /** Platforms this video is already uploaded to, with the date it was ticked (millis). */
    public Map<String, Long> postedOn = new LinkedHashMap<String, Long>();

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
        o.put("platforms", platforms);
        o.put("plannedAt", plannedAt);
        o.put("folderId", folderId);
        JSONObject posted = new JSONObject();
        for (Map.Entry<String, Long> e : postedOn.entrySet()) posted.put(e.getKey(), e.getValue());
        o.put("postedOn", posted);
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
        v.platforms = o.optString("platforms", "");
        v.plannedAt = o.optLong("plannedAt");
        v.folderId = o.optString("folderId", "");
        JSONObject posted = o.optJSONObject("postedOn");
        if (posted != null) {
            Iterator<String> keys = posted.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                v.postedOn.put(k, posted.optLong(k));
            }
        }
        return v;
    }
}
