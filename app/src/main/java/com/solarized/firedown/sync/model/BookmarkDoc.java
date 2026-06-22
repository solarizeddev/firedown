package com.solarized.firedown.sync.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * (De)serialization of the plaintext sync document (the JSON that gets
 * gzip+AES-GCM'd into the blob). Shape matches cloud-sync-spec-api.md §9:
 *
 * <pre>
 * {"v":1,"updated_at":0,
 *  "bookmarks":[{"url","title","date","icon","preview","updatedAt"}],
 *  "tombstones":[{"url","deletedAt","updatedAt"}]}
 * </pre>
 *
 * Live items go in {@code bookmarks}; deletions in {@code tombstones}. Uses
 * {@code org.json} (the same JSON the rest of the app uses). Keys are emitted in
 * a stable order so unchanged data re-encrypts deterministic-ish.
 */
public final class BookmarkDoc {

    public static final int SCHEMA = 1;

    private BookmarkDoc() {}

    /** Serializes items to the canonical JSON string. */
    public static String toJson(List<SyncItem> items, long updatedAt) {
        try {
            JSONObject root = new JSONObject();
            root.put("v", SCHEMA);
            root.put("updated_at", updatedAt);
            JSONArray bookmarks = new JSONArray();
            JSONArray tombstones = new JSONArray();
            for (SyncItem it : items) {
                if (it.deleted) {
                    JSONObject t = new JSONObject();
                    t.put("url", it.url);
                    t.put("deletedAt", it.deletedAt);
                    t.put("updatedAt", it.updatedAt);
                    tombstones.put(t);
                } else {
                    JSONObject b = new JSONObject();
                    b.put("url", it.url);
                    b.put("title", it.title == null ? JSONObject.NULL : it.title);
                    b.put("date", it.date);
                    b.put("icon", it.icon == null ? JSONObject.NULL : it.icon);
                    b.put("preview", it.preview == null ? JSONObject.NULL : it.preview);
                    b.put("updatedAt", it.updatedAt);
                    bookmarks.put(b);
                }
            }
            root.put("bookmarks", bookmarks);
            root.put("tombstones", tombstones);
            return root.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("bookmark doc serialize failed", e);
        }
    }

    /** Parses the JSON string back to items (live + tombstones). */
    public static List<SyncItem> fromJson(String json) {
        List<SyncItem> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray bookmarks = root.optJSONArray("bookmarks");
            if (bookmarks != null) {
                for (int i = 0; i < bookmarks.length(); i++) {
                    JSONObject b = bookmarks.getJSONObject(i);
                    String url = b.optString("url", null);
                    if (url == null || url.isEmpty()) {
                        continue;
                    }
                    out.add(new SyncItem(
                            url,
                            optStringOrNull(b, "title"),
                            b.optLong("date", 0),
                            optStringOrNull(b, "icon"),
                            optStringOrNull(b, "preview"),
                            b.optLong("updatedAt", 0),
                            false, 0));
                }
            }
            JSONArray tombstones = root.optJSONArray("tombstones");
            if (tombstones != null) {
                for (int i = 0; i < tombstones.length(); i++) {
                    JSONObject t = tombstones.getJSONObject(i);
                    String url = t.optString("url", null);
                    if (url == null || url.isEmpty()) {
                        continue;
                    }
                    out.add(new SyncItem(url, null, 0, null, null,
                            t.optLong("updatedAt", 0), true, t.optLong("deletedAt", 0)));
                }
            }
        } catch (JSONException e) {
            throw new IllegalStateException("bookmark doc parse failed", e);
        }
        return out;
    }

    private static String optStringOrNull(JSONObject o, String key) {
        if (o.isNull(key)) {
            return null;
        }
        String v = o.optString(key, null);
        return (v == null || v.isEmpty()) ? null : v;
    }
}
