package com.solarized.firedown.sync.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * (De)serialization of the storage vault manifest — the client's encrypted index
 * of backed-up files (cloud-storage-spec.md §5). The JSON here is what gets
 * gzip+AES-GCM'd into the opaque manifest blob the server version-controls (OCC),
 * exactly like {@link BookmarkDoc} for bookmarks.
 *
 * <pre>
 * {"v":1,"updated_at":0,
 *  "files":[{"objectId","wrappedDek","name","size","mime","downloadedAt","chunkCount"}]}
 * </pre>
 */
public final class VaultManifest {

    public static final int SCHEMA = 1;

    private VaultManifest() {}

    /** Serializes the file index to the canonical JSON string. */
    public static String toJson(List<VaultEntry> entries, long updatedAt) {
        try {
            JSONObject root = new JSONObject();
            root.put("v", SCHEMA);
            root.put("updated_at", updatedAt);
            JSONArray files = new JSONArray();
            for (VaultEntry e : entries) {
                JSONObject o = new JSONObject();
                o.put("objectId", e.objectId);
                o.put("wrappedDek", e.wrappedDek);
                o.put("name", e.name == null ? JSONObject.NULL : e.name);
                o.put("size", e.size);
                o.put("mime", e.mime == null ? JSONObject.NULL : e.mime);
                o.put("downloadedAt", e.downloadedAt);
                o.put("chunkCount", e.chunkCount);
                if (e.thumb != null) {
                    o.put("thumb", e.thumb);
                }
                files.put(o);
            }
            root.put("files", files);
            return root.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("vault manifest serialize failed", e);
        }
    }

    /** Parses the JSON string back to the file index. */
    public static List<VaultEntry> fromJson(String json) {
        List<VaultEntry> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray files = root.optJSONArray("files");
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject o = files.getJSONObject(i);
                    String objectId = o.optString("objectId", null);
                    String wrappedDek = o.optString("wrappedDek", null);
                    if (objectId == null || objectId.isEmpty() || wrappedDek == null || wrappedDek.isEmpty()) {
                        continue; // a corrupt row can't be restored — skip it
                    }
                    out.add(new VaultEntry(
                            objectId,
                            wrappedDek,
                            optStringOrNull(o, "name"),
                            o.optLong("size", 0),
                            optStringOrNull(o, "mime"),
                            o.optLong("downloadedAt", 0),
                            o.optInt("chunkCount", 1),
                            optStringOrNull(o, "thumb")));
                }
            }
        } catch (JSONException e) {
            throw new IllegalStateException("vault manifest parse failed", e);
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
