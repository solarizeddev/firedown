package com.solarized.firedown.applinks;

/**
 * What happens to a link that would leave the browser for an app (an
 * {@code intent://}, {@code market://} or custom-scheme link, or a Play Store
 * listing). Pure Java, no Android, so {@code scripts/applinks-harness} runs
 * the real rules.
 *
 * <p>The one input Firefox doesn't use here is {@link Facts#pageFired}: a
 * link the page fired by itself, with no tap (GeckoView's
 * {@code LoadRequest.hasUserGesture} false) and not typed. Only those are
 * blocked by "Block app redirects"; a link the user tapped or typed always
 * asks. Firefox prompts for every app link and keys nothing on the gesture.</p>
 */
public final class AppLinkPolicy {

    public enum Outcome {
        /** Deny it and show nothing. */
        NOTHING,
        /** Load the link's web version in the tab, no prompt. */
        LOAD_WEB,
        /** Deny it and say so (snackbar with Open). */
        BLOCK,
        /** Ask: open in the app, or stay in Firedown. */
        PROMPT
    }

    /** What is known about one link, from the request and from Android. */
    public static final class Facts {
        /** No tap and not typed: the page fired it by itself. */
        boolean pageFired;
        /** The "Block app redirects" setting. */
        boolean blockRedirects;
        /** mailto:, tel:, sms:, geo: — never treated as a nag. */
        boolean commsScheme;
        /** An installed app other than Firedown (and not a browser) opens it. */
        boolean hasApp;
        /** It leads to the Play Store: an install-our-app push. */
        boolean toStore;
        /** It has a web version Firedown can load instead. */
        boolean hasWebUrl;
        /** The same app was asked for or blocked in this tab moments ago. */
        boolean justHandled;
        /** The user chose Stay for this app in this tab within the hour. */
        boolean stayRemembered;
        /** A page-fired link to this app was already blocked in this tab within the hour. */
        boolean blockRemembered;

        public Facts pageFired(boolean v) {
            pageFired = v;
            return this;
        }

        public Facts blockRedirects(boolean v) {
            blockRedirects = v;
            return this;
        }

        public Facts commsScheme(boolean v) {
            commsScheme = v;
            return this;
        }

        public Facts hasApp(boolean v) {
            hasApp = v;
            return this;
        }

        public Facts toStore(boolean v) {
            toStore = v;
            return this;
        }

        public Facts hasWebUrl(boolean v) {
            hasWebUrl = v;
            return this;
        }

        public Facts justHandled(boolean v) {
            justHandled = v;
            return this;
        }

        public Facts stayRemembered(boolean v) {
            stayRemembered = v;
            return this;
        }

        public Facts blockRemembered(boolean v) {
            blockRemembered = v;
            return this;
        }
    }

    private AppLinkPolicy() {
    }

    public static Outcome decide(Facts f) {
        if (!f.hasApp) {
            // Nothing on the phone opens it. A web version is the page the
            // link stands for (Chrome and Firefox load it too), except a store
            // listing the page pushed by itself, which is the nag the setting
            // blocks.
            if (!f.hasWebUrl) {
                return Outcome.NOTHING;
            }
            if (f.toStore && f.pageFired && f.blockRedirects) {
                return Outcome.BLOCK;
            }
            return Outcome.LOAD_WEB;
        }
        if (f.pageFired) {
            if (f.justHandled) {
                // The page fired the same app twice, or right after a tap.
                return Outcome.NOTHING;
            }
            if (f.stayRemembered) {
                return f.hasWebUrl ? Outcome.LOAD_WEB : Outcome.NOTHING;
            }
            if (f.blockRedirects && !f.commsScheme) {
                return f.blockRemembered ? Outcome.NOTHING : Outcome.BLOCK;
            }
        }
        return Outcome.PROMPT;
    }
}
