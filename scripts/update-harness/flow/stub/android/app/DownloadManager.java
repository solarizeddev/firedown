package android.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A fake DownloadManager: enqueue() records the request; the harness then
 * finishes it (writing the served bytes into the destination) or fails it.
 * remove() deletes the row AND its file, as the real one does.
 */
public class DownloadManager {
    public static final String ACTION_DOWNLOAD_COMPLETE = "android.intent.action.DOWNLOAD_COMPLETE";
    public static final String EXTRA_DOWNLOAD_ID = "extra_download_id";
    public static final String COLUMN_STATUS = "status";
    public static final int STATUS_PENDING = 1;
    public static final int STATUS_RUNNING = 2;
    public static final int STATUS_PAUSED = 4;
    public static final int STATUS_SUCCESSFUL = 8;
    public static final int STATUS_FAILED = 16;

    public static final class Row {
        public final long id;
        public final String url;
        public final File dest;
        public int status = STATUS_RUNNING;
        Row(long id, String url, File dest) { this.id = id; this.url = url; this.dest = dest; }
    }

    public final Map<Long, Row> rows = new LinkedHashMap<>();
    public final java.util.List<Long> removed = new java.util.ArrayList<>();
    private long nextId = 100;

    public long enqueue(Request request) {
        if (request.dest.exists()) {
            throw new IllegalStateException("destination exists: " + request.dest);
        }
        long id = nextId++;
        rows.put(id, new Row(id, request.uri.toString(), request.dest));
        return id;
    }

    public Cursor query(Query query) {
        Row row = rows.get(query.id);
        return new Cursor() {
            public boolean moveToFirst() { return row != null; }
            public int getColumnIndex(String name) { return COLUMN_STATUS.equals(name) ? 0 : -1; }
            public int getInt(int column) { return row.status; }
            public void close() { }
        };
    }

    /** The Download Manager app disabled: remove() throws, as the real one does then. */
    public boolean removeThrows;

    public int remove(long... ids) {
        if (removeThrows) {
            throw new IllegalArgumentException("Unknown URL content://downloads/my_downloads");
        }
        int n = 0;
        for (long id : ids) {
            Row row = rows.remove(id);
            removed.add(id);
            if (row != null) {
                row.dest.delete();
                n++;
            }
        }
        return n;
    }

    public static class Request {
        public static final int VISIBILITY_HIDDEN = 2;
        final Uri uri;
        File dest;
        public Request(Uri uri) { this.uri = uri; }
        public Request addRequestHeader(String h, String v) { return this; }
        public Request setDestinationInExternalFilesDir(Context context, String type, String sub) {
            dest = new File(context.getExternalFilesDir(type), sub);
            return this;
        }
        public Request setNotificationVisibility(int v) { return this; }
        public Request setAllowedOverMetered(boolean b) { return this; }
        public Request setAllowedOverRoaming(boolean b) { return this; }
    }

    public static class Query {
        long id;
        public Query setFilterById(long... ids) { id = ids[0]; return this; }
    }
}
