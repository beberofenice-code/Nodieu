package com.nodieu.videonotebook;

import org.json.JSONException;
import org.json.JSONObject;

/** A named folder of videos. Its type sets its color: Tagalog dub = blue, English dub = red. */
public class Folder {
    public static final String TAGALOG = "tagalog";
    public static final String ENGLISH = "english";

    public String id;
    public String name;
    public String type = TAGALOG;
    public long createdAt;

    public boolean isEnglish() { return ENGLISH.equals(type); }

    public String typeLabel() { return isEnglish() ? "ENGLISH DUB" : "TAGALOG DUB"; }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("type", type);
        o.put("createdAt", createdAt);
        return o;
    }

    public static Folder fromJson(JSONObject o) {
        Folder f = new Folder();
        f.id = o.optString("id");
        f.name = o.optString("name");
        f.type = o.optString("type", TAGALOG);
        f.createdAt = o.optLong("createdAt");
        return f;
    }
}
