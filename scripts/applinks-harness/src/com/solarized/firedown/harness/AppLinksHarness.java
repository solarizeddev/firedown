package com.solarized.firedown.harness;

import com.solarized.firedown.applinks.AppLinkMemory;
import com.solarized.firedown.applinks.AppLinkPolicy;
import com.solarized.firedown.applinks.AppLinkPolicy.Facts;
import com.solarized.firedown.applinks.AppLinkPolicy.Outcome;

/**
 * Drives the REAL AppLinkPolicy and AppLinkMemory (copied from app/src by
 * run.sh): which links ask, which are blocked, which load their web version,
 * and what a tab remembers. Exit status 1 on any failure.
 */
public final class AppLinksHarness {

    private static int sFailures;
    private static int sChecks;

    private static void check(String name, boolean ok) {
        sChecks++;
        if (ok) {
            System.out.println("ok   " + name);
        } else {
            sFailures++;
            System.out.println("FAIL " + name);
        }
    }

    private static void expect(String name, Facts facts, Outcome want) {
        Outcome got = AppLinkPolicy.decide(facts);
        sChecks++;
        if (got == want) {
            System.out.println("ok   " + name + " -> " + got);
        } else {
            sFailures++;
            System.out.println("FAIL " + name + " -> " + got + " (want " + want + ")");
        }
    }

    /** An installed app, blocking on: the common case. */
    private static Facts app() {
        return new Facts().hasApp(true).blockRedirects(true);
    }

    public static void main(String[] args) {
        // A tap or a typed link always asks; only what the page fires by
        // itself is blocked.
        expect("tapped link to an installed app asks", app(), Outcome.PROMPT);
        expect("page-fired link is blocked", app().pageFired(true), Outcome.BLOCK);
        expect("page-fired link with blocking off asks", app().pageFired(true).blockRedirects(false),
                Outcome.PROMPT);
        expect("page-fired mailto/tel asks even with blocking on", app().pageFired(true).commsScheme(true),
                Outcome.PROMPT);
        expect("a second block in the same tab is silent", app().pageFired(true).blockRemembered(true),
                Outcome.NOTHING);
        expect("a remembered block never silences a tap", app().blockRemembered(true), Outcome.PROMPT);

        // The store: the install-our-app push.
        expect("page-fired store link with the Play Store installed is blocked",
                app().pageFired(true).toStore(true).hasWebUrl(true), Outcome.BLOCK);
        expect("tapped store link with the Play Store installed asks",
                app().toStore(true).hasWebUrl(true), Outcome.PROMPT);
        expect("page-fired store link, no Play Store, blocking on: blocked",
                new Facts().blockRedirects(true).pageFired(true).toStore(true).hasWebUrl(true), Outcome.BLOCK);
        expect("tapped store link, no Play Store: listing loads in the tab",
                new Facts().blockRedirects(true).toStore(true).hasWebUrl(true), Outcome.LOAD_WEB);
        expect("page-fired store link, no Play Store, blocking off: listing loads",
                new Facts().pageFired(true).toStore(true).hasWebUrl(true), Outcome.LOAD_WEB);

        // No app: the web version, or nothing.
        expect("missing app with a web version loads it, even page-fired",
                new Facts().blockRedirects(true).pageFired(true).hasWebUrl(true), Outcome.LOAD_WEB);
        expect("missing app with no web version does nothing",
                new Facts().blockRedirects(true).pageFired(true), Outcome.NOTHING);
        expect("tapped link nothing can open does nothing", new Facts(), Outcome.NOTHING);

        // Stay, and the same app fired twice.
        expect("after Stay a page-fired link loads its web version",
                app().pageFired(true).stayRemembered(true).hasWebUrl(true), Outcome.LOAD_WEB);
        expect("after Stay a page-fired link with no web version is silent",
                app().pageFired(true).stayRemembered(true), Outcome.NOTHING);
        expect("after Stay a tap still asks", app().stayRemembered(true).hasWebUrl(true), Outcome.PROMPT);
        expect("after Stay with blocking off a page-fired link stays quiet",
                app().blockRedirects(false).pageFired(true).stayRemembered(true), Outcome.NOTHING);
        expect("a tap moments after the same app is still answered", app().justHandled(true), Outcome.PROMPT);
        expect("the same app moments later, page-fired, gets nothing", app().pageFired(true).justHandled(true),
                Outcome.NOTHING);

        memory();

        System.out.println();
        System.out.println(sChecks + " checks, " + sFailures + " failed");
        if (sFailures > 0) {
            System.exit(1);
        }
    }

    private static void memory() {
        AppLinkMemory m = new AppLinkMemory();
        long t = 1_000_000L;
        m.recordHandled("com.example.tunely", t);
        check("handled: just handled at once", m.wasJustHandled("com.example.tunely", t + 1));
        check("handled: still at 2 s", m.wasJustHandled("com.example.tunely", t + AppLinkMemory.JUST_HANDLED_MS));
        check("handled: over after 2 s", !m.wasJustHandled("com.example.tunely", t + AppLinkMemory.JUST_HANDLED_MS + 1));
        check("handled: other app unaffected", !m.wasJustHandled("com.example.other", t + 1));
        check("handled: does not count as Stay", !m.isStayRemembered("com.example.tunely", t + 1));

        m.rememberStay("com.example.tunely", t);
        check("stay: remembered for the hour", m.isStayRemembered("com.example.tunely", t + AppLinkMemory.REMEMBER_MS));
        check("stay: forgotten after the hour", !m.isStayRemembered("com.example.tunely", t + AppLinkMemory.REMEMBER_MS + 1));
        check("stay: is not a block", !m.isBlockRemembered("com.example.tunely", t + 1));

        m.rememberBlock("tiktok", t);
        check("block: remembered", m.isBlockRemembered("tiktok", t + 60_000L));
        check("block: forgotten after the hour", !m.isBlockRemembered("tiktok", t + AppLinkMemory.REMEMBER_MS + 1));
        check("block: clock going back reads as not remembered", !m.isBlockRemembered("tiktok", t - 1));

        AppLinkMemory capped = new AppLinkMemory();
        for (int i = 0; i < 40; i++) {
            capped.rememberStay("app" + i, t);
        }
        check("cap: bounded", sizeOf(capped) <= 32);
        check("cap: newest kept", capped.isStayRemembered("app39", t + 1));
        check("cap: oldest dropped", !capped.isStayRemembered("app0", t + 1));

        AppLinkMemory touched = new AppLinkMemory();
        touched.rememberStay("keep", t);
        for (int i = 0; i < 31; i++) {
            touched.rememberStay("app" + i, t);
        }
        touched.isStayRemembered("keep", t + 1);
        touched.rememberStay("one-more", t);
        check("cap: a key just read is not the one dropped", touched.isStayRemembered("keep", t + 2));
    }

    private static int sizeOf(AppLinkMemory m) {
        int n = 0;
        for (int i = 0; i < 40; i++) {
            if (m.isStayRemembered("app" + i, 1_000_001L)) {
                n++;
            }
        }
        return n;
    }
}
