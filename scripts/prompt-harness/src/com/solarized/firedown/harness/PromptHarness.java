package com.solarized.firedown.harness;

import android.app.Activity;
import android.os.SystemClock;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import com.solarized.firedown.R;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.prompt.GeckoPromptManager;
import com.solarized.firedown.geckoview.prompt.PromptViewFactory;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;

/**
 * Drives the REAL GeckoPromptManager + REAL PromptFloodGate with a fake
 * malicious page that floods prompts, and checks: the first prompt shows a
 * dialog, everything else is answered with a dismissal (never queued, never
 * hung, never a second dialog), the flood window drains, and no GeckoResult
 * is ever leaked or completed twice.
 */
public class PromptHarness {
    static int pass = 0, fail = 0;
    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("PASS  " + n + (d.isEmpty() ? "" : "  [" + d + "]")); }
        else    { fail++; System.out.println("FAIL  " + n + "  [" + d + "]"); }
    }

    static GeckoPromptManager manager;
    static NavController nav;
    static Activity activity = new Activity();

    /** Fresh current tab on the browser destination. */
    static GeckoState newTab() {
        GeckoState s = new GeckoState();
        GeckoStateDataRepository.current = s;
        return s;
    }
    /** One page-fired alert() reaching the manager (the delegate and the
     *  fragment mode filter sit upstream and gate background tabs/modes —
     *  audited separately; the manager is the dialog decision point). */
    static GeckoResult<PromptDelegate.PromptResponse> fireAlert(GeckoState tab) {
        GeckoResult<PromptDelegate.PromptResponse> res = new GeckoResult<>();
        manager.onAlertPrompt(activity, tab, nav, new PromptDelegate.AlertPrompt(), res);
        return res;
    }
    static int dialogs() { return PromptViewFactory.created.size(); }
    static PromptViewFactory.Created lastDialog() { return PromptViewFactory.created.get(dialogs() - 1); }
    static boolean dismissed(GeckoResult<PromptDelegate.PromptResponse> r) {
        return r.completed && r.value != null;
    }

    public static void main(String[] a) {
        manager = new GeckoPromptManager(new GeckoStateDataRepository(), new IncognitoStateRepository());
        nav = new NavController();
        nav.destination = new NavDestination(R.id.browser);

        // ── 1. the malformed page: a burst of 10 alerts ──────────────────
        GeckoState tab = newTab();
        GeckoResult<PromptDelegate.PromptResponse> first = fireAlert(tab);
        check("1  first prompt shows a dialog", dialogs() == 1 && lastDialog().dialog.shown
                && tab.isPromptDisplaying() && !first.completed, "dialogs=" + dialogs());

        int answered = 0;
        GeckoResult<PromptDelegate.PromptResponse> second = null;
        for (int i = 0; i < 9; i++) {
            GeckoResult<PromptDelegate.PromptResponse> r = fireAlert(tab);
            if (i == 0) second = r;
            if (dismissed(r)) answered++;
        }
        check("2  while it is up, every further prompt is answered-dismissed, zero new dialogs",
                answered == 9 && dialogs() == 1, "answered=" + answered + " dialogs=" + dialogs());
        check("2b the second prompt was answered SYNCHRONOUSLY (page JS never blocks, nothing queues)",
                second != null && dismissed(second), "");

        // ── 3. answering the first dialog completes its result exactly once
        lastDialog().answer();          // user taps OK; dialog dismiss-listener also fires
        check("3  the shown prompt's result completes exactly once (dismiss listener double-fire safe)",
                dismissed(first) && !tab.isPromptDisplaying(), "");

        // ── 4. the nag loop: answer each, flood breaker cuts in at 3/10s ─
        PromptViewFactory.reset();
        tab = newTab();
        int shown = 0;
        for (int i = 0; i < 6; i++) {
            GeckoResult<PromptDelegate.PromptResponse> r = fireAlert(tab);
            if (dialogs() > shown) {    // a dialog appeared — answer it like a user would
                shown = dialogs();
                lastDialog().answer();
            } else {
                check("4." + i + "  flooded prompt is auto-dismissed, not shown and not hung",
                        dismissed(r), "");
            }
            SystemClock.now += 100;     // rapid-fire page
        }
        check("4  a nagging page gets exactly 3 dialogs per window, the rest blocked",
                shown == 3, "shown=" + shown);

        // ── 5. the window drains: dialogs come back (self-healing) ───────
        SystemClock.now += 10_001;
        GeckoResult<PromptDelegate.PromptResponse> afterCalm = fireAlert(tab);
        check("5  after the page calms down (window drained) dialogs return",
                dialogs() == 4 && !afterCalm.completed, "dialogs=" + dialogs());
        lastDialog().answer();

        // ── 6. flood is per-tab: another tab is unaffected ───────────────
        SystemClock.now += 200;
        GeckoState tabA = newTab();
        for (int i = 0; i < 5; i++) {   // flood tab A dry
            GeckoResult<PromptDelegate.PromptResponse> r = fireAlert(tabA);
            if (!r.completed) lastDialog().answer();
        }
        boolean aFlooded = tabA.isPromptFlooding();
        GeckoState tabB = newTab();
        GeckoResult<PromptDelegate.PromptResponse> onB = fireAlert(tabB);
        check("6  tab A flooded, tab B still gets its dialog",
                aFlooded && !onB.completed && lastDialog().state == tabB, "");
        lastDialog().answer();

        // ── 7. user on a sheet / other destination → dismissed, no dialog
        int before = dialogs();
        nav.destination = new NavDestination(R.id.sheet);
        GeckoState tabC = newTab();
        GeckoResult<PromptDelegate.PromptResponse> onSheet = fireAlert(tabC);
        check("7  prompt while a sheet/non-browser destination is up: dismissed, no dialog",
                dismissed(onSheet) && dialogs() == before, "");
        nav.destination = new NavDestination(R.id.browser);

        // ── 8. tab switch while a dialog is up: dialog dies, prompt answered
        GeckoState tabD = newTab();
        GeckoResult<PromptDelegate.PromptResponse> onD = fireAlert(tabD);
        check("8  dialog up on tab D", !onD.completed && tabD.isPromptDisplaying(), "");
        tabD.deactivate();              // the real setActive(false) contract
        check("8b tab switch dismisses the dialog and answers the prompt",
                dismissed(onD) && !tabD.isPromptDisplaying(), "");

        // ── 9. the leak sweep: every result ever created is completed ────
        int leaked = 0;
        for (GeckoResult<?> r : GeckoResult.ALL) if (!r.completed) leaked++;
        check("9  no result leaked: everything was answered (results=" + GeckoResult.ALL.size() + ")",
                leaked == 0, "leaked=" + leaked);

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
