package com.solarized.firedown.harness;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

/**
 * Drives the REAL sessions-file reader + writer (spliced by splice.py) through
 * every version the strict reader accepts and the retired-key rule v4 added:
 * a v2/v3 file may carry icon_resolution / tracking_protection and loads; a v4
 * file carrying either is corrupt. See run.sh.
 */
public final class SessionsHarness {

    private static int failures;
    private static int passes;
    private static final SessionsReal REAL = new SessionsReal();

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

    /** One tab as a v3 writer emitted it (every current key), plus extras. */
    private static String v3Tab(int id, String extra) {
        return "{\"date\":1700000000000,\"update\":1700000001000,"
                + "\"icon\":\"https://x.com/favicon.ico\",\"thumb\":\"/thumbs/" + id + ".jpg\","
                + "\"session_ref\":\"/data/tab_states/ss_" + id + "\","
                + "\"uri\":\"https://x.com/" + id + "\",\"id\":" + id + ",\"parent_id\":0,"
                + "\"title\":\"Tab " + id + "\",\"backward\":true,\"forward\":false,"
                + "\"fullscreen\":false,\"desktop\":false,\"active\":" + (id == 1)
                + ",\"home\":false" + extra + "}";
    }

    /** One tab as the v2 writer emitted it: inline session, no session_ref. */
    private static String v2Tab(int id, String extra) {
        return v3Tab(id, extra).replace("\"session_ref\":\"/data/tab_states/ss_" + id + "\"",
                "\"session\":\"{\\\"history\\\":" + id + "}\"");
    }

    private static String doc(int version, String... tabs) {
        return "{\"version\":" + version + ",\"tabs\":[" + String.join(",", tabs) + "]}";
    }

    private static final String RETIRED = ",\"icon_resolution\":4096,\"tracking_protection\":true";

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
        check("the writer stamps v4", SessionsReal.SESSION_FILE_VERSION == 4);
        check("v4 is where the retired keys stop being tolerated",
                SessionsReal.LEGACY_KEYS_BELOW_VERSION == SessionsReal.SESSION_FILE_VERSION);

        // ── files from before the drop still load ─────────────────────────
        List<GeckoStateEntity> v3 = loads("v3 file carrying both retired keys loads",
                doc(3, v3Tab(1, RETIRED), v3Tab(2, RETIRED)));
        check("...every tab, with its fields intact around the skipped keys",
                v3.size() == 2 && v3.get(0).getIcon().equals("https://x.com/favicon.ico")
                        && v3.get(0).getSessionStateRef().equals("/data/tab_states/ss_1")
                        && v3.get(0).getTitle().equals("Tab 1") && v3.get(0).isActive()
                        && v3.get(1).getId() == 2 && !v3.get(1).isActive());
        loads("v3 file carrying only icon_resolution loads", doc(3, v3Tab(1, ",\"icon_resolution\":0")));
        loads("v3 file carrying only tracking_protection loads", doc(3, v3Tab(1, ",\"tracking_protection\":false")));
        loads("v3 file without the retired keys loads", doc(3, v3Tab(1, "")));
        loads("a retired key's value is skipped whatever its type",
                doc(3, v3Tab(1, ",\"icon_resolution\":\"big\",\"tracking_protection\":null")));
        List<GeckoStateEntity> v2 = loads("v2 file (inline session) carrying both retired keys loads",
                doc(2, v2Tab(1, RETIRED)));
        check("...with the inline session read",
                v2.size() == 1 && v2.get(0).getSessionState().equals("{\"history\":1}"));
        List<GeckoStateEntity> legacy = loads("legacy bare array carrying the retired keys (and junk) loads leniently",
                "[" + v2Tab(1, RETIRED + ",\"preview\":\"data:x\",\"junk\":{}") + "]");
        check("...with its title read", legacy.size() == 1 && legacy.get(0).getTitle().equals("Tab 1"));

        // ── v4: what the writer emits, and nothing more ───────────────────
        GeckoStateEntity e = new GeckoStateEntity(false);
        e.setCreationDate(1700000000000L);
        e.setLastAccess(1700000001000L);
        e.setIcon("https://x.com/favicon.ico");
        e.setThumb("/thumbs/7.jpg");
        e.setSessionStateRef("/data/tab_states/ss_7");
        e.setUri("https://x.com/7");
        e.setId(7);
        e.setParentId(3);
        e.setTitle("Seven");
        e.setCanGoBackward(true);
        e.setActive(true);
        String written = write(List.of(e));
        check("the written document is v4", written.startsWith("{\"version\":4,"));
        check("the writer emits no icon_resolution",
                !written.contains("icon_resolution"));
        check("the writer emits no tracking_protection",
                !written.contains("tracking_protection"));
        List<GeckoStateEntity> back = loads("a written v4 document reads back through the strict reader", written);
        check("...field for field",
                back.size() == 1 && back.get(0).getId() == 7 && back.get(0).getParentId() == 3
                        && back.get(0).getTitle().equals("Seven") && back.get(0).canGoBackward()
                        && back.get(0).isActive() && back.get(0).getSessionStateRef().equals("/data/tab_states/ss_7")
                        && back.get(0).getIcon().equals("https://x.com/favicon.ico"));
        loads("a v4 file without the retired keys loads", doc(4, v3Tab(1, "")));

        // ── v4: the retired keys are corruption, like any unknown key ─────
        Exception r1 = rejection(doc(4, v3Tab(1, ",\"icon_resolution\":4096")));
        check("a v4 file carrying icon_resolution is rejected", r1 != null);
        check("...naming the key as retired",
                r1 != null && r1.getMessage() != null && r1.getMessage().contains("icon_resolution"));
        check("a v4 file carrying tracking_protection is rejected",
                rejection(doc(4, v3Tab(1, ",\"tracking_protection\":true"))) != null);
        check("a v4 file carrying an unknown key is rejected (strictness intact)",
                rejection(doc(4, v3Tab(1, ",\"colour\":\"red\""))) != null);
        check("a v3 file carrying an unknown key is still rejected (only RETIRED keys are tolerated)",
                rejection(doc(3, v3Tab(1, ",\"colour\":\"red\""))) != null);
        check("a wrong-typed current key is still rejected in v3",
                rejection(doc(3, v3Tab(1, "").replace("\"id\":1", "\"id\":\"one\""))) != null);

        // ── version envelope ──────────────────────────────────────────────
        check("v5 (a future build's file) is rejected", rejection(doc(5, v3Tab(1, ""))) != null);
        check("v1 is rejected", rejection(doc(1, v3Tab(1, ""))) != null);
        check("a document without a version is rejected", rejection("{\"tabs\":[" + v3Tab(1, "") + "]}") != null);
        check("tabs before the version are rejected (the version decides the key set)",
                rejection("{\"tabs\":[" + v3Tab(1, "") + "],\"version\":3}") != null);
        check("an empty v4 tab list reads", loads("an empty v4 document loads", doc(4)).isEmpty());
    }
}
