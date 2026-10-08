package com.solarized.firedown.harness;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

/**
 * Drives the REAL sessions-file reader + writer (spliced by splice.py) through
 * every version the strict reader accepts and the retired-key rule: a key a
 * later version retired is skipped in a file from before that version and
 * rejected, like any unknown key, from it on — icon_resolution and
 * tracking_protection from v4, thumb from v5. See run.sh.
 */
public final class SessionsHarness {

    private static int failures;
    private static int passes;
    private static final SessionsReal REAL = new SessionsReal();
    private static final int V = SessionsReal.SESSION_FILE_VERSION;

    private static void check(String name, boolean ok) {
        if (ok) {
            passes++;
            System.out.println("ok    " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }

    /** The shape dispatch of GeckoStateDataRepository.tryReadEntities — a bare
     *  array is the legacy lenient path, an object the strict versioned one
     *  (mirrored: that method is file IO + move-aside around these lines). */
    private static List<GeckoStateEntity> read(String json) throws Exception {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                List<GeckoStateEntity> entities = new java.util.ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) {
                    entities.add(REAL.readEntity(reader));
                }
                reader.endArray();
                return entities;
            }
            return REAL.readDocumentStrict(reader);
        }
    }

    /** Null when the document reads, else the exception the reader threw
     *  (what tryReadEntities' catch-all turns into a move-aside). */
    private static Exception rejection(String json) {
        try {
            read(json);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    /** A document that must LOAD: a throw is reported as this check failing,
     *  not as an aborted run that hides every check after it. */
    private static List<GeckoStateEntity> loads(String name, String json) {
        try {
            List<GeckoStateEntity> entities = read(json);
            check(name, true);
            return entities;
        } catch (Exception e) {
            check(name + " (threw " + e + ")", false);
            return List.of();
        }
    }

    private static boolean rejected(String name, String json, String naming) {
        Exception e = rejection(json);
        boolean ok = e != null && (naming == null || (e.getMessage() != null && e.getMessage().contains(naming)));
        check(name, ok);
        return ok;
    }

    /** The document envelope GeckoStateObserver.persist writes (version first,
     *  then the tabs array) around the REAL writeEntity. */
    private static String write(List<GeckoStateEntity> entities) throws Exception {
        StringWriter sw = new StringWriter();
        try (JsonWriter writer = new JsonWriter(sw)) {
            writer.beginObject();
            writer.name(SessionsReal.KEY_VERSION);
            writer.value(SessionsReal.SESSION_FILE_VERSION);
            writer.name(SessionsReal.KEY_TABS);
            writer.beginArray();
            for (GeckoStateEntity e : entities) {
                REAL.writeEntity(writer, e, e.getIcon(), e.getSessionStateRef());
            }
            writer.endArray();
            writer.endObject();
        }
        return sw.toString();
    }

    /** One tab as the CURRENT writer emits it (every current key), plus extras. */
    private static String tab(int id, String extra) {
        return "{\"date\":1700000000000,\"update\":1700000001000,"
                + "\"icon\":\"https://x.com/favicon.ico\","
                + "\"session_ref\":\"/data/tab_states/ss_" + id + "\","
                + "\"uri\":\"https://x.com/" + id + "\",\"id\":" + id + ",\"parent_id\":0,"
                + "\"title\":\"Tab " + id + "\",\"backward\":true,\"forward\":false,"
                + "\"fullscreen\":false,\"desktop\":false,\"active\":" + (id == 1)
                + ",\"home\":false" + extra + "}";
    }

    /** One tab as the v3/v4 writer emitted it: the thumb PATH is still there. */
    private static String v3Tab(int id, String extra) {
        return tab(id, ",\"thumb\":\"/cache/thumbs/" + id + "_1700000000000.png\"" + extra);
    }

    /** One tab as the v2 writer emitted it: inline session, no session_ref. */
    private static String v2Tab(int id, String extra) {
        return v3Tab(id, extra).replace("\"session_ref\":\"/data/tab_states/ss_" + id + "\"",
                "\"session\":\"{\\\"history\\\":" + id + "}\"");
    }

    private static String doc(int version, String... tabs) {
        return "{\"version\":" + version + ",\"tabs\":[" + String.join(",", tabs) + "]}";
    }

    private static final String RETIRED_V4 = ",\"icon_resolution\":4096,\"tracking_protection\":true";

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL  harness threw: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println(failures == 0 ? "all " + passes + " passed" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        check("the writer stamps v5", V == 5);
        check("thumb is retired in the current version",
                Integer.valueOf(V).equals(SessionsReal.RETIRED_KEYS.get("thumb")));
        check("icon_resolution and tracking_protection were retired in v4",
                Integer.valueOf(4).equals(SessionsReal.RETIRED_KEYS.get("icon_resolution"))
                        && Integer.valueOf(4).equals(SessionsReal.RETIRED_KEYS.get("tracking_protection")));

        // ── files from before each retirement still load ──────────────────
        List<GeckoStateEntity> v3 = loads("v3 file carrying thumb + both v4-retired keys loads",
                doc(3, v3Tab(1, RETIRED_V4), v3Tab(2, RETIRED_V4)));
        check("...every tab, with its fields intact around the skipped keys",
                v3.size() == 2 && v3.get(0).getIcon().equals("https://x.com/favicon.ico")
                        && v3.get(0).getSessionStateRef().equals("/data/tab_states/ss_1")
                        && v3.get(0).getTitle().equals("Tab 1") && v3.get(0).isActive()
                        && v3.get(1).getId() == 2 && !v3.get(1).isActive());
        loads("v3 file carrying only icon_resolution loads", doc(3, v3Tab(1, ",\"icon_resolution\":0")));
        loads("v3 file carrying only tracking_protection loads", doc(3, v3Tab(1, ",\"tracking_protection\":false")));
        loads("v3 file with none of the v4-retired keys loads", doc(3, v3Tab(1, "")));
        loads("a retired key's value is skipped whatever its type",
                doc(3, v3Tab(1, ",\"icon_resolution\":\"big\",\"tracking_protection\":null")));
        List<GeckoStateEntity> v2 = loads("v2 file (inline session) carrying every retired key loads",
                doc(2, v2Tab(1, RETIRED_V4)));
        check("...with the inline session read",
                v2.size() == 1 && v2.get(0).getSessionState().equals("{\"history\":1}"));
        loads("v4 file carrying thumb loads (retired only in v5)", doc(4, v3Tab(1, "")));
        loads("v4 file without thumb loads", doc(4, tab(1, "")));
        List<GeckoStateEntity> legacy = loads("legacy bare array carrying every retired key (and junk) loads leniently",
                "[" + v2Tab(1, RETIRED_V4 + ",\"preview\":\"data:x\",\"junk\":{}") + "]");
        check("...with its title read", legacy.size() == 1 && legacy.get(0).getTitle().equals("Tab 1"));

        // ── the current version: what the writer emits, and nothing more ──
        GeckoStateEntity e = new GeckoStateEntity(false);
        e.setCreationDate(1700000000000L);
        e.setLastAccess(1700000001000L);
        e.setIcon("https://x.com/favicon.ico");
        e.setSessionStateRef("/data/tab_states/ss_7");
        e.setUri("https://x.com/7");
        e.setId(7);
        e.setParentId(3);
        e.setTitle("Seven");
        e.setCanGoBackward(true);
        e.setActive(true);
        String written = write(List.of(e));
        check("the written document is v" + V, written.startsWith("{\"version\":" + V + ","));
        check("the writer emits no thumb", !written.contains("\"thumb\""));
        check("the writer emits no icon_resolution", !written.contains("icon_resolution"));
        check("the writer emits no tracking_protection", !written.contains("tracking_protection"));
        List<GeckoStateEntity> back = loads("a written document reads back through the strict reader", written);
        check("...field for field",
                back.size() == 1 && back.get(0).getId() == 7 && back.get(0).getParentId() == 3
                        && back.get(0).getTitle().equals("Seven") && back.get(0).canGoBackward()
                        && back.get(0).isActive() && back.get(0).getSessionStateRef().equals("/data/tab_states/ss_7")
                        && back.get(0).getIcon().equals("https://x.com/favicon.ico"));
        loads("a v" + V + " file without any retired key loads", doc(V, tab(1, "")));

        // ── from the retiring version on, a retired key is corruption ─────
        rejected("a v" + V + " file carrying thumb is rejected, naming the key", doc(V, v3Tab(1, "")), "thumb");
        rejected("a v" + V + " file carrying icon_resolution is rejected", doc(V, tab(1, ",\"icon_resolution\":4096")), "icon_resolution");
        rejected("a v" + V + " file carrying tracking_protection is rejected", doc(V, tab(1, ",\"tracking_protection\":true")), null);
        rejected("a v4 file carrying icon_resolution is rejected (retired in v4)", doc(4, tab(1, ",\"icon_resolution\":4096")), null);
        rejected("a v4 file carrying tracking_protection is rejected", doc(4, tab(1, ",\"tracking_protection\":true")), null);
        rejected("a v" + V + " file carrying an unknown key is rejected (strictness intact)", doc(V, tab(1, ",\"colour\":\"red\"")), "colour");
        rejected("a v3 file carrying an unknown key is still rejected (only RETIRED keys are tolerated)", doc(3, v3Tab(1, ",\"colour\":\"red\"")), null);
        rejected("a wrong-typed current key is still rejected in v3", doc(3, v3Tab(1, "").replace("\"id\":1", "\"id\":\"one\"")), null);

        // ── version envelope ──────────────────────────────────────────────
        rejected("v" + (V + 1) + " (a future build's file) is rejected", doc(V + 1, tab(1, "")), null);
        rejected("v1 is rejected", doc(1, tab(1, "")), null);
        rejected("a document without a version is rejected", "{\"tabs\":[" + tab(1, "") + "]}", null);
        rejected("tabs before the version are rejected (the version decides the key set)",
                "{\"tabs\":[" + tab(1, "") + "],\"version\":3}", null);
        check("an empty document loads", loads("an empty v" + V + " document loads", doc(V)).isEmpty());
    }
}
