package com.solarized.firedown.harness;

import com.solarized.firedown.addons.AddonPermissions;
import com.solarized.firedown.addons.AddonPolicy;
import com.solarized.firedown.addons.AmoCatalog;
import com.solarized.firedown.addons.XpiManifest;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Drives the REAL pure add-on classes (copied from app/src by run.sh):
 * the built-in ID rule, the official catalog's parser, the .xpi manifest
 * reader and the permission lines. Prints PASS/FAIL per check, exits 1 on
 * any failure.
 */
public final class AddonsHarness {

    private static int failures;
    private static int checks;

    private static void check(String name, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            policy();
            catalog();
            realFixtures(args.length > 0 ? args[0] : null);
            manifest();
            permissions();
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL harness threw: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- policy

    private static void policy() {
        check("policy: uBlock Origin is NOT built in (the official build installs as an add-on)",
                !AddonPolicy.isBuiltInId("uBlock0@raymondhill.net")
                        && !AddonPolicy.isBuiltInId(" UBLOCK0@RaymondHill.net "));
        check("policy: surrounding spaces do not matter", AddonPolicy.isBuiltInId(" icons@mozac.org "));
        check("policy: capture extension is built in", AddonPolicy.isBuiltInId("downloader@solarized.dev"));
        check("policy: a future @solarized.dev id is built in", AddonPolicy.isBuiltInId("anything@solarized.dev"));
        check("policy: icons extension is built in", AddonPolicy.isBuiltInId("icons@mozac.org"));
        check("policy: Violentmonkey is not", !AddonPolicy.isBuiltInId("{aecec67f-0d10-4fa7-b7c7-609a2db280cf}"));
        check("policy: a lookalike domain is not ours", !AddonPolicy.isBuiltInId("x@solarized.dev.evil.com"));
        check("policy: null and empty are not", !AddonPolicy.isBuiltInId(null) && !AddonPolicy.isBuiltInId(""));
        check("policy: uBlock Origin is recognised in any case", AddonPolicy.isUblockId("uBlock0@raymondhill.net")
                && AddonPolicy.isUblockId("ublock0@RAYMONDHILL.net"));
        check("policy: nothing else is uBlock", !AddonPolicy.isUblockId("{aecec67f-0d10-4fa7-b7c7-609a2db280cf}")
                && !AddonPolicy.isUblockId("downloader@solarized.dev") && !AddonPolicy.isUblockId(null));
        blockers();

        String base = "moz-extension://1234-abcd/";
        check("policy: https loads in a tab", AddonPolicy.mayLoadInTab("https://example.com/", base));
        check("policy: http loads in a tab", AddonPolicy.mayLoadInTab("HTTP://example.com/", base));
        check("policy: about:blank loads", AddonPolicy.mayLoadInTab("about:blank", base));
        check("policy: own page loads", AddonPolicy.mayLoadInTab(base + "options.html", base));
        check("policy: another extension's page is refused",
                !AddonPolicy.mayLoadInTab("moz-extension://9999-ffff/page.html", base));
        check("policy: any moz-extension refused without a base",
                !AddonPolicy.mayLoadInTab(base + "options.html", null));
        check("policy: javascript: refused", !AddonPolicy.mayLoadInTab("javascript:alert(1)", base));
        check("policy: file: refused", !AddonPolicy.mayLoadInTab("file:///sdcard/x.html", base));
        check("policy: about:config refused", !AddonPolicy.mayLoadInTab("about:config", base));
        check("policy: null refused", !AddonPolicy.mayLoadInTab(null, base));
    }

    // --------------------------------------------------------------- catalog

    private static String addon(String guid, String type, String name, String summary,
                                String fileUrl, String extra) {
        return "{\"addon\":{"
                + "\"guid\":" + q(guid) + ","
                + (type == null ? "" : "\"type\":" + q(type) + ",")
                + "\"name\":" + name + ","
                + "\"summary\":" + summary + ","
                + "\"default_locale\":\"en-US\","
                + "\"icon_url\":\"https://addons.cdn.mozilla.net/icon-64.png\","
                + "\"url\":\"https://addons.mozilla.org/addon/x/\","
                + "\"authors\":[{\"name\":\"Ada\"}],"
                + "\"ratings\":{\"average\":4.5,\"count\":120,\"text_count\":30},"
                + "\"average_daily_users\":5000,"
                + extra
                + "\"current_version\":{\"version\":\"2.1\",\"files\":[{\"url\":" + q(fileUrl)
                + ",\"permissions\":[\"storage\",\"tabs\",\"<all_urls>\"]}]}"
                + "},\"notes\":null}";
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\"";
    }

    private static void catalog() throws Exception {
        String url = AmoCatalog.collectionUrl("es");
        check("catalog: URL names the official collection",
                url.equals("https://services.addons.mozilla.org/api/v4/accounts/account/mozilla/"
                        + "collections/Extensions-for-Android/addons/?page_size=50&sort=-popularity&lang=es"));
        check("catalog: URL without a language has no lang param",
                !AmoCatalog.collectionUrl(null).contains("lang="));
        check("catalog: next page on the API server is trusted",
                AmoCatalog.isTrustedPageUrl("https://services.addons.mozilla.org/api/v4/x?page=2"));
        check("catalog: next page elsewhere is not",
                !AmoCatalog.isTrustedPageUrl("https://services.addons.mozilla.org.evil.com/x")
                        && !AmoCatalog.isTrustedPageUrl("http://services.addons.mozilla.org/x")
                        && !AmoCatalog.isTrustedPageUrl(null));

        String body = "{\"count\":6,\"next\":\"https://services.addons.mozilla.org/api/v4/p2\",\"results\":["
                + addon("a@x", "extension", q("Alpha"), q("First"), "https://a.example/a.xpi", "") + ","
                + addon("theme@x", "statictheme", q("Theme"), q("A theme"), "https://a.example/t.xpi", "") + ","
                + addon("", "extension", q("NoId"), q(""), "https://a.example/n.xpi", "") + ","
                + addon("http@x", "extension", q("Plain"), q(""), "http://a.example/p.xpi", "") + ","
                + addon("map@x", null,
                        "{\"de\":\"Beta DE\",\"en-US\":\"Beta\",\"pt-BR\":\"Beta PT\"}",
                        "{\"en-US\":\"Second\"}", "https://a.example/b.xpi",
                        "\"icons\":{\"32\":\"https://cdn/32.png\",\"64\":\"https://cdn/64.png\"},") + ","
                + addon("off@x", "extension", q("Off"), q(""), "https://a.example/o.xpi",
                        "\"is_disabled\":true,")
                + "]}";
        AmoCatalog.Page page = AmoCatalog.parse(body, "pt");
        List<String> ids = new ArrayList<>();
        for (AmoCatalog.Entry e : page.entries) {
            ids.add(e.id);
        }
        check("catalog: keeps extensions with an id and an https file, drops theme/no-id/http/disabled",
                ids.equals(Arrays.asList("a@x", "map@x")));
        check("catalog: next URL read", "https://services.addons.mozilla.org/api/v4/p2".equals(page.next));
        AmoCatalog.Entry a = page.entries.get(0);
        check("catalog: fields read", a.name.equals("Alpha") && a.summary.equals("First")
                && a.author.equals("Ada") && a.version.equals("2.1") && a.users == 5000
                && a.reviews == 120 && Math.abs(a.rating - 4.5) < 1e-9
                && a.downloadUrl.equals("https://a.example/a.xpi")
                && "https://addons.cdn.mozilla.net/icon-64.png".equals(a.iconUrl));
        check("catalog: permissions from the file", a.permissions.equals(Arrays.asList("storage", "tabs", "<all_urls>")));
        AmoCatalog.Entry b = page.entries.get(1);
        check("catalog: translation map picks the language by subtag", b.name.equals("Beta PT"));
        check("catalog: translation map falls back to the default locale", b.summary.equals("Second"));
        check("catalog: sized icon preferred", "https://cdn/64.png".equals(b.iconUrl));

        AmoCatalog.Page noLang = AmoCatalog.parse(body, null);
        check("catalog: no language → default locale", noLang.entries.get(1).name.equals("Beta"));

        String broken = "{\"next\":null,\"results\":["
                + addon("w@x", "extension", "\"W\"", "\" One line.\\nTwo  lines.\\r\\n\"",
                        "https://w.example/w.xpi", "")
                + "]}";
        AmoCatalog.Page lines = AmoCatalog.parse(broken, "en");
        check("catalog: summary line breaks collapsed", lines.entries.size() == 1
                && lines.entries.get(0).summary.equals("One line. Two lines."));

        AmoCatalog.Page last = AmoCatalog.parse("{\"next\":null,\"results\":[]}", "en");
        check("catalog: null next is no next page", last.next == null && last.entries.isEmpty());

        boolean threw = false;
        try {
            AmoCatalog.parse("<html>", "en");
        } catch (Exception e) {
            threw = true;
        }
        check("catalog: a non-JSON body throws", threw);

        // Search-result shape (no "addon" wrapper) parses too.
        String search = "{\"results\":[{\"guid\":\"s@x\",\"name\":\"S\",\"summary\":\"\","
                + "\"current_version\":{\"version\":\"1\",\"file\":{\"url\":\"https://s/x.xpi\","
                + "\"permissions\":[],\"host_permissions\":[\"https://*.example.com/*\"]}}}]}";
        AmoCatalog.Page s = AmoCatalog.parse(search, "en");
        check("catalog: unwrapped add-on and a single 'file' parse",
                s.entries.size() == 1 && s.entries.get(0).id.equals("s@x"));
        check("catalog: MV3 host permissions are kept",
                s.entries.get(0).permissions.equals(Collections.singletonList("https://*.example.com/*")));

        List<AmoCatalog.Page> pages = new ArrayList<>();
        pages.add(page);
        pages.add(AmoCatalog.parse(body, "pt"));
        check("catalog: merge drops an id seen on an earlier page", AmoCatalog.merge(pages).size() == 2);
        StringBuilder many = new StringBuilder("{\"results\":[");
        for (int i = 0; i < AmoCatalog.MAX_ENTRIES + 20; i++) {
            if (i > 0) {
                many.append(',');
            }
            many.append(addon("n" + i + "@x", "extension", q("N" + i), q(""), "https://a/x.xpi", ""));
        }
        many.append("]}");
        check("catalog: merge caps the list",
                AmoCatalog.merge(Collections.singletonList(AmoCatalog.parse(many.toString(), "en"))).size()
                        == AmoCatalog.MAX_ENTRIES);
    }

    /** Firefox's own AMO fixtures, when a Firefox source checkout is given (else SKIP). */
    private static void realFixtures(String firefoxSrc) throws Exception {
        if (firefoxSrc == null) {
            System.out.println("SKIP real fixtures: pass a mozilla-firefox checkout to run them");
            return;
        }
        File dir = new File(firefoxSrc,
                "mobile/android/android-components/components/feature/addons/src/test/resources");
        File collection = new File(dir, "collection.json");
        File localized = new File(dir, "localized_collection.json");
        if (!collection.isFile() || !localized.isFile()) {
            System.out.println("SKIP real fixtures: not found under " + dir);
            return;
        }
        AmoCatalog.Page all = AmoCatalog.parse(read(collection), null);
        boolean everyFieldSane = !all.entries.isEmpty();
        for (AmoCatalog.Entry e : all.entries) {
            if (e.id.isEmpty() || !e.downloadUrl.startsWith("https://") || e.name.isEmpty()) {
                everyFieldSane = false;
            }
        }
        check("fixture: Firefox's collection.json parses (" + all.entries.size() + " add-ons)", everyFieldSane);
        AmoCatalog.Page loc = AmoCatalog.parse(read(localized), "en-US");
        check("fixture: localized_collection.json parses with names",
                !loc.entries.isEmpty() && !loc.entries.get(0).name.isEmpty()
                        && loc.entries.get(0).summary.length() > 0);
        boolean hasUblock = false;
        for (AmoCatalog.Entry e : loc.entries) {
            if (AddonPolicy.isUblockId(e.id)) {
                hasUblock = true;
            }
        }
        check("fixture: the official list contains uBlock Origin, which the screen replaces with its reinstall row",
                hasUblock);
    }

    /** Which add-ons stand behind the shield, and what the shield says. */
    private static void blockers() {
        java.util.List<String> ubo = java.util.Arrays.asList("alarms", "dns", "menus", "privacy", "storage", "tabs",
                "unlimitedStorage", "webNavigation", "webRequest", "webRequestBlocking", "<all_urls>");
        check("blockers: uBlock's own manifest (1.75.0) can block requests", AddonPolicy.blocksRequests(ubo, null));
        check("blockers: webRequestBlocking with <all_urls> as an origin",
                AddonPolicy.blocksRequests(java.util.Arrays.asList("webRequest", "webRequestBlocking"),
                        java.util.Collections.singletonList("<all_urls>")));
        check("blockers: webRequestBlocking with http and https wildcards",
                AddonPolicy.blocksRequests(java.util.Arrays.asList("webRequestBlocking"),
                        java.util.Arrays.asList("http://*/*", "https://*/*")));
        check("blockers: *://*/* is all sites",
                AddonPolicy.blocksRequests(java.util.Arrays.asList("webRequestBlocking"),
                        java.util.Collections.singletonList("*://*/*")));
        check("blockers: declarativeNetRequest blocks without host access",
                AddonPolicy.blocksRequests(java.util.Collections.singletonList("declarativeNetRequest"), null));
        check("blockers: webRequestBlocking on one site is not a blocker",
                !AddonPolicy.blocksRequests(java.util.Arrays.asList("webRequestBlocking"),
                        java.util.Collections.singletonList("https://www.youtube.com/*")));
        check("blockers: https wildcard alone is not all sites",
                !AddonPolicy.blocksRequests(java.util.Arrays.asList("webRequestBlocking"),
                        java.util.Collections.singletonList("https://*/*")));
        check("blockers: all sites without a blocking permission is not a blocker (Dark Reader)",
                !AddonPolicy.blocksRequests(java.util.Arrays.asList("storage", "tabs"),
                        java.util.Collections.singletonList("<all_urls>")));
        check("blockers: declarativeNetRequestWithHostAccess needs all sites",
                !AddonPolicy.blocksRequests(java.util.Collections.singletonList("declarativeNetRequestWithHostAccess"),
                        java.util.Collections.singletonList("https://a.example/*"))
                        && AddonPolicy.blocksRequests(java.util.Collections.singletonList("declarativeNetRequestWithHostAccess"),
                        java.util.Collections.singletonList("<all_urls>")));
        check("blockers: no permissions, no blocker", !AddonPolicy.blocksRequests(null, null));
        check("blockers: uBlock is always one, button or not",
                AddonPolicy.isBlocker("uBlock0@raymondhill.net", false, Boolean.FALSE));
        check("blockers: a known blocker with a button is one (AdGuard, Privacy Badger, Ghostery)",
                AddonPolicy.isBlocker("adguardadblocker@adguard.com", true, null)
                        && AddonPolicy.isBlocker("jid1-MnnxcxisBPnSXQ@jetpack", true, null)
                        && AddonPolicy.isBlocker("firefox@ghostery.com", true, null));
        check("blockers: the known list matches whatever the ID's case",
                AddonPolicy.isKnownBlocker("JID1-NIFFY2CA8FY1TG@JETPACK")
                        && AddonPolicy.isKnownBlocker(" uBOLite@raymondhill.net "));
        check("blockers: an unknown add-on is not one on its permissions alone (Bypass Paywalls Clean)",
                !AddonPolicy.isBlocker("magnolia@12.34", true, null));
        check("blockers: no button, no row", !AddonPolicy.isBlocker("adguardadblocker@adguard.com", false, Boolean.TRUE));
        check("blockers: the user hides a known one",
                !AddonPolicy.isBlocker("firefox@ghostery.com", true, Boolean.FALSE));
        check("blockers: the user shows an unknown one", AddonPolicy.isBlocker("nsc@x", true, Boolean.TRUE));
        check("blockers: the switch is offered to an add-on that can block requests",
                AddonPolicy.offersShieldChoice("magnolia@12.34", true, true, null));
        check("blockers: the switch is offered to a known blocker whatever its permissions",
                AddonPolicy.offersShieldChoice("2.0@disconnect.me", true, false, null));
        check("blockers: no switch for an add-on that cannot block anything (Dark Reader)",
                !AddonPolicy.offersShieldChoice("addon@darkreader.org", true, false, null));
        check("blockers: a choice already made keeps its switch, so it can be undone",
                AddonPolicy.offersShieldChoice("addon@darkreader.org", true, false, Boolean.TRUE));
        check("blockers: no switch for uBlock or an add-on with no button",
                !AddonPolicy.offersShieldChoice("uBlock0@raymondhill.net", true, true, null)
                        && !AddonPolicy.offersShieldChoice("magnolia@12.34", false, true, null));
        check("sheet: uBlock's per-tab title repeats the name and badge, no second line",
                !AddonPolicy.actionTitleSaysMore("uBlock Origin (23)", "uBlock Origin", "23"));
        check("sheet: a title equal to the name says nothing more",
                !AddonPolicy.actionTitleSaysMore("Bypass Paywalls Clean", "Bypass Paywalls Clean", "^")
                        && !AddonPolicy.actionTitleSaysMore("  ", "x", null)
                        && !AddonPolicy.actionTitleSaysMore(null, "x", null));
        check("sheet: a title with its own words is shown",
                AddonPolicy.actionTitleSaysMore("Privacy Badger (3 trackers)", "Privacy Badger", "3")
                        && AddonPolicy.actionTitleSaysMore("uBlock Origin (24)", "uBlock Origin", "23"));
        check("shield: uBlock's (off) title is off for the site",
                AddonPolicy.saysOffForSite("uBlock0@raymondhill.net", "uBlock Origin (off)"));
        check("shield: uBlock's count title is on",
                !AddonPolicy.saysOffForSite("uBlock0@raymondhill.net", "uBlock Origin (23)")
                        && !AddonPolicy.saysOffForSite("uBlock0@raymondhill.net", "uBlock Origin (0)"));
        check("shield: an untitled tab reads as on",
                !AddonPolicy.saysOffForSite("uBlock0@raymondhill.net", "uBlock Origin")
                        && !AddonPolicy.saysOffForSite("uBlock0@raymondhill.net", null));
        check("shield: another add-on's (off) says nothing",
                !AddonPolicy.saysOffForSite("ag@x", "AdGuard (off)"));
        check("shield: no blocker running", AddonPolicy.shieldState(java.util.Collections.emptyList())
                == AddonPolicy.SHIELD_NO_BLOCKER && AddonPolicy.shieldState(null) == AddonPolicy.SHIELD_NO_BLOCKER);
        check("shield: one on is protected",
                AddonPolicy.shieldState(java.util.Collections.singletonList(false)) == AddonPolicy.SHIELD_PROTECTED);
        check("shield: one says off, another on: off for the site",
                AddonPolicy.shieldState(java.util.Arrays.asList(false, true)) == AddonPolicy.SHIELD_OFF_FOR_SITE);
        check("shield: two on is protected",
                AddonPolicy.shieldState(java.util.Arrays.asList(false, false)) == AddonPolicy.SHIELD_PROTECTED);
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------- manifest

    private static File zip(String name, String[][] files) throws Exception {
        File f = File.createTempFile(name, ".xpi");
        f.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(f))) {
            for (String[] entry : files) {
                out.putNextEntry(new ZipEntry(entry[0]));
                out.write(entry[1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return f;
    }

    private static void manifest() throws Exception {
        File full = zip("full", new String[][]{
                {"manifest.json", "{\"manifest_version\":2,\"name\":\"Wider Player\",\"version\":\"1.4.0\","
                        + "\"browser_specific_settings\":{\"gecko\":{\"id\":\"wider@example.org\","
                        + "\"update_url\":\"https://example.org/updates.json\"},\"gecko_android\":{}}}"},
                {"META-INF/mozilla.rsa", "sig"},
                {"background.js", "1"}});
        XpiManifest.Info info = XpiManifest.read(full);
        check("manifest: id, name, version", "wider@example.org".equals(info.id)
                && info.name.equals("Wider Player") && info.version.equals("1.4.0") && info.manifestVersion == 2);
        check("manifest: declares Android", info.declaresAndroid);
        check("manifest: self-updates", info.selfUpdates);
        check("manifest: signed (mozilla.rsa)", info.signed);

        File mv3 = zip("mv3", new String[][]{
                {"manifest.json", "﻿// a comment\n{\"manifest_version\":3,\n/* block */ \"name\":\"__MSG_name__\","
                        + "\"version\":\"3\",\"url\":\"http://x/y\",\"applications\":{\"gecko\":{\"id\":\"legacy@example.org\"}}}"},
                {"META-INF/cose.sig", "sig"}});
        XpiManifest.Info m3 = XpiManifest.read(mv3);
        check("manifest: BOM and comments are read, // inside a string kept",
                m3.manifestVersion == 3 && "legacy@example.org".equals(m3.id));
        check("manifest: legacy 'applications' key read", "legacy@example.org".equals(m3.id));
        check("manifest: no gecko_android → not declared", !m3.declaresAndroid);
        check("manifest: no update_url → no self updates", !m3.selfUpdates);
        check("manifest: cose.sig counts as signed", m3.signed);

        File unsigned = zip("unsigned", new String[][]{
                {"manifest.json", "{\"manifest_version\":2,\"name\":\"X\",\"version\":\"1\"}"}});
        XpiManifest.Info u = XpiManifest.read(unsigned);
        check("manifest: no signature files → unsigned, id null", !u.signed && u.id == null);

        check("manifest: no manifest.json → not an add-on",
                notAnAddon(zip("none", new String[][]{{"readme.txt", "hi"}})));
        check("manifest: manifest.json in a folder only → not an add-on",
                notAnAddon(zip("nested", new String[][]{{"sub/manifest.json", "{\"manifest_version\":2}"}})));
        check("manifest: no manifest_version → not an add-on",
                notAnAddon(zip("nover", new String[][]{{"manifest.json", "{\"name\":\"X\"}"}})));
        check("manifest: broken JSON → not an add-on",
                notAnAddon(zip("broken", new String[][]{{"manifest.json", "{\"manifest_version\":2,"}})));
        StringBuilder big = new StringBuilder("{\"manifest_version\":2,\"name\":\"");
        while (big.length() < 1024 * 1024 + 10) {
            big.append("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        }
        big.append("\"}");
        check("manifest: a manifest over 1 MB is not read",
                notAnAddon(zip("big", new String[][]{{"manifest.json", big.toString()}})));
        File notZip = File.createTempFile("plain", ".xpi");
        notZip.deleteOnExit();
        Files.write(notZip.toPath(), "<html>not a zip</html>".getBytes(StandardCharsets.UTF_8));
        check("manifest: an HTML page saved as .xpi → not an add-on", notAnAddon(notZip));
    }

    private static boolean notAnAddon(File f) {
        try {
            XpiManifest.read(f);
            return false;
        } catch (XpiManifest.NotAnAddonException e) {
            return true;
        } catch (Exception e) {
            System.out.println("  unexpected " + e);
            return false;
        }
    }

    // ----------------------------------------------------------- permissions

    private static String lines(List<AddonPermissions.Line> lines) {
        List<String> out = new ArrayList<>();
        for (AddonPermissions.Line l : lines) {
            out.add(l.toString());
        }
        return String.join(" ", out);
    }

    private static void permissions() {
        List<AddonPermissions.Line> l = AddonPermissions.describe(
                Arrays.asList("storage", "tabs", "notifications", "unlimitedStorage", "<all_urls>"), null);
        check("perm: all sites first, then prompted APIs in Firefox's order, silent ones dropped",
                lines(l).equals(AddonPermissions.ALL_SITES + ":null:0 "
                        + AddonPermissions.API + ":notifications:0 " + AddonPermissions.API + ":tabs:0"));
        check("perm: *://*/* is all sites",
                AddonPermissions.grantsAllSites(null, Collections.singletonList("*://*/*")));
        check("perm: http://*/* is all sites",
                AddonPermissions.grantsAllSites(Collections.singletonList("http://*/*"), null));
        check("perm: one site is not all sites",
                !AddonPermissions.grantsAllSites(null, Collections.singletonList("https://example.com/*")));

        List<AddonPermissions.Line> some = AddonPermissions.describe(null,
                Arrays.asList("https://*.example.com/*", "https://www.youtube.com/*",
                        "https://www.youtube.com:443/watch*", "https://m.youtube.com/*"));
        check("perm: domains then sites, ports dropped, duplicates merged",
                lines(some).equals(AddonPermissions.DOMAIN + ":example.com:0 "
                        + AddonPermissions.SITE + ":www.youtube.com:0 " + AddonPermissions.SITE + ":m.youtube.com:0"));

        List<String> manySites = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            manySites.add("https://s" + i + ".example.org/*");
        }
        List<AddonPermissions.Line> capped = AddonPermissions.describe(null, manySites);
        check("perm: four sites listed, then 'other sites' with the count",
                capped.size() == 5 && capped.get(4).kind == AddonPermissions.MORE_SITES && capped.get(4).count == 3);

        List<AddonPermissions.Line> bad = AddonPermissions.describe(null,
                Arrays.asList("not a pattern", "https://nopath.example.com"));
        check("perm: malformed patterns are dropped", bad.isEmpty());

        check("perm: data collection in Firefox's order",
                AddonPermissions.dataCollection(Arrays.asList("websiteContent", "healthInfo", "bogus"))
                        .equals(Arrays.asList("healthInfo", "websiteContent")));
        check("perm: 'none' collects nothing",
                AddonPermissions.dataCollection(Collections.singletonList("none")).isEmpty());
    }
}
