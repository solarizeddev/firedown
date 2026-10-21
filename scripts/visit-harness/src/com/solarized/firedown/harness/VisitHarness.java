package com.solarized.firedown.harness;

import com.solarized.firedown.geckoview.VisitModel;
import com.solarized.firedown.utils.DebugLog;

/**
 * Drives the REAL GeckoState visit code (spliced by splice.py) through URL
 * sequences and checks when the visit id moves. A moved id is what puts the
 * captures stamped before it behind "Earlier in this tab" in the Captured
 * sheet; a kept one keeps them on the page.
 */
public final class VisitHarness {
    private static int failures;
    private static int checks;

    private VisitHarness() {}

    public static void main(String[] args) {
        DebugLog.verbose = args.length > 0 && "-v".equals(args[0]);
        try {
            run();
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL harness threw: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("ok   " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + "  (" + detail + ")");
        }
    }

    private static void expectVisit(String name, VisitModel m, String url, int visit) {
        m.locationChange(url);
        check(name, m.mVisitId == visit, "visit=" + m.mVisitId + ", expected " + visit
                + ", key=" + VisitModel.key(url));
    }

    private static void run() {
        // 1. A Mix opened fresh on m.youtube.com: the video is stamped under
        //    index=1, then the page rewrites itself to index=2 on the SAME
        //    video ~300 ms later; that must not allocate visit 2.
        String base = "https://m.youtube.com/watch?v=4WIMyqBG9gs&list=RD4WIMyqBG9gs";
        VisitModel mix = new VisitModel();
        expectVisit("mix: first location is visit 1", mix, base + "&index=1", 1);
        int resets = mix.titleResets;
        expectVisit("mix: index 1 -> 2 on the same video keeps the visit", mix,
                base + "&index=2", 1);
        check("mix: no title reset for the position move", mix.titleResets == resets,
                "titleResets=" + mix.titleResets);
        expectVisit("mix: index 2 -> 3, same video, still the visit", mix, base + "&index=3", 1);
        expectVisit("mix: tracking noise on top keeps it", mix, base + "&index=3&pp=ygU", 1);
        expectVisit("mix: autoplay to the next video moves the visit", mix,
                "https://m.youtube.com/watch?v=Zq3b8LyKc0E&list=RD4WIMyqBG9gs&index=4", 2);
        check("mix: the new video resets the title", mix.titleResets == resets + 1,
                "titleResets=" + mix.titleResets);
        expectVisit("mix: Back to the first video re-anchors to visit 1", mix,
                base + "&index=2", 1);
        expectVisit("mix: Back again, other position, still visit 1", mix,
                base + "&index=1", 1);

        // 2. Entering a Mix from a plain watch page: params are only added,
        //    then the position moves.
        VisitModel entry = new VisitModel();
        expectVisit("entry: plain watch page", entry, "https://www.youtube.com/watch?v=AAAAAAAAAAA", 1);
        expectVisit("entry: list + start_radio added keeps it", entry,
                "https://www.youtube.com/watch?v=AAAAAAAAAAA&list=RDAAAAAAAAAAA&start_radio=1", 1);
        expectVisit("entry: start_radio swapped for index keeps it", entry,
                "https://www.youtube.com/watch?v=AAAAAAAAAAA&list=RDAAAAAAAAAAA&index=1", 1);
        expectVisit("entry: index advances, same video", entry,
                "https://www.youtube.com/watch?v=AAAAAAAAAAA&list=RDAAAAAAAAAAA&index=2", 1);

        // 3. What the relaxation must NOT accept.
        VisitModel viewer = new VisitModel();
        expectVisit("viewer: position-only query", viewer, "https://example.com/viewer?index=1", 1);
        expectVisit("viewer: index is the item there, so 1 -> 2 moves", viewer,
                "https://example.com/viewer?index=2", 2);

        VisitModel other = new VisitModel();
        expectVisit("other: v=A at index 1", other, "https://www.youtube.com/watch?v=A&index=1", 1);
        expectVisit("other: v=B at the same index moves", other,
                "https://www.youtube.com/watch?v=B&index=1", 2);

        VisitModel lists = new VisitModel();
        expectVisit("lists: v=A in list L1", lists, "https://www.youtube.com/watch?v=A&list=L1&index=1", 1);
        expectVisit("lists: same video, another list, moves (a changed identity value)", lists,
                "https://www.youtube.com/watch?v=A&list=L2&index=1", 2);

        // 4. The rules that were there before, unchanged.
        VisitModel ig = new VisitModel();
        expectVisit("instagram: permalink", ig, "https://www.instagram.com/p/CODE123", 1);
        expectVisit("instagram: ?l=1 refinement keeps it", ig, "https://www.instagram.com/p/CODE123/?l=1", 1);

        VisitModel fb = new VisitModel();
        expectVisit("facebook: hub", fb, "https://www.facebook.com/watch/", 1);
        expectVisit("facebook: hub -> item moves", fb, "https://www.facebook.com/watch/?v=123", 2);

        VisitModel yt = new VisitModel();
        expectVisit("youtube: v=A", yt, "https://www.youtube.com/watch?v=A", 1);
        expectVisit("youtube: v=A -> v=B moves", yt, "https://www.youtube.com/watch?v=B", 2);
        expectVisit("youtube: m. spelling of v=B is the same page", yt, "https://m.youtube.com/watch?v=B", 2);

        // 5. The key itself still carries the position (two tabs at different
        //    playlist positions are not duplicates in the archive sweep).
        check("key: index stays in the identity key",
                !VisitModel.key(base + "&index=1").equals(VisitModel.key(base + "&index=2")),
                VisitModel.key(base + "&index=1"));

        // 6. isQueryRefinement directly, on keys.
        check("refinement: position moves with v kept",
                VisitModel.refinement("youtube.com/watch?index=1&list=L&v=X",
                        "youtube.com/watch?index=2&list=L&v=X"), "");
        check("refinement: position moves and a param is added",
                VisitModel.refinement("youtube.com/watch?index=1&v=X",
                        "youtube.com/watch?index=2&list=L&v=X"), "");
        check("refinement: position-only keys are not relaxed",
                !VisitModel.refinement("example.com/viewer?index=1", "example.com/viewer?index=2"), "");
        check("refinement: other path is never a refinement",
                !VisitModel.refinement("youtube.com/watch?index=1&v=X",
                        "youtube.com/shorts?index=2&v=X"), "");
        check("refinement: an upper-case Index counts as a position",
                VisitModel.refinement("example.com/a/b?Index=1&id=7", "example.com/a/b?Index=2&id=7"), "");

        // 7. Instagram carousel: swiping a post's slides rewrites img_index on
        //    the SAME post. The path names the post, so the position alone may
        //    move; another post still moves the visit.
        VisitModel carousel = new VisitModel();
        expectVisit("carousel: the post", carousel, "https://www.instagram.com/p/CAR0USEL1/", 1);
        expectVisit("carousel: first swipe adds img_index", carousel,
                "https://www.instagram.com/p/CAR0USEL1/?img_index=2", 1);
        expectVisit("carousel: img_index 2 -> 3 keeps the visit", carousel,
                "https://www.instagram.com/p/CAR0USEL1/?img_index=3", 1);
        expectVisit("carousel: another post moves it", carousel,
                "https://www.instagram.com/p/OTHERPOST/?img_index=1", 2);
        check("carousel: img_index stays in the key (tabs at two slides differ)",
                !VisitModel.key("https://www.instagram.com/p/X/?img_index=1")
                        .equals(VisitModel.key("https://www.instagram.com/p/X/?img_index=2")), "");

        // 8. A map site: every pan, zoom and photo rewrites the @camera and
        //    data= segments while the place stays. Another place, or the
        //    search list, is another page; Back re-anchors.
        String place = "https://www.google.com/maps/place/Museo+del+Prado";
        VisitModel maps = new VisitModel();
        expectVisit("maps: a place", maps,
                place + "/@40.4137818,-3.6921271,17z/data=!3m1!4b1!4m6!3m5!1s0xd42:0x6b!8m2", 1);
        expectVisit("maps: pan and zoom keep the visit", maps,
                place + "/@40.4152,-3.6899,15.5z/data=!4m6!3m5!1s0xd42:0x6b!8m2", 1);
        expectVisit("maps: opening a photo keeps it", maps,
                place + "/@40.4137818,-3.6921271,3a,75y,90t/data=!3m8!1e2!3m6!1sAF1QipN", 1);
        expectVisit("maps: the next photo keeps it", maps,
                place + "/@40.4137818,-3.6921271,3a,75y,90t/data=!3m8!1e2!3m6!1sAF1QipQ", 1);
        expectVisit("maps: another place moves it", maps,
                "https://www.google.com/maps/place/Retiro/@40.415,-3.684,16z/data=!4m2", 2);
        expectVisit("maps: Back to the first place re-anchors", maps,
                place + "/@40.41,-3.69,17z/data=!3m1!4b1", 1);
        check("maps: the key is the place",
                ("google.com/maps/place/Museo+del+Prado").equals(
                        VisitModel.key(place + "/@40.41,-3.69,17z/data=!3m1!4b1")),
                VisitModel.key(place + "/@40.41,-3.69,17z/data=!3m1!4b1"));
        check("maps: a bare map view keys to /maps",
                "google.com/maps".equals(VisitModel.key("https://www.google.com/maps/@40.4,-3.7,12z")),
                VisitModel.key("https://www.google.com/maps/@40.4,-3.7,12z"));
        check("handles: /@user is not a map camera",
                "tiktok.com/@user/video/123".equals(
                        VisitModel.key("https://www.tiktok.com/@user/video/123")),
                VisitModel.key("https://www.tiktok.com/@user/video/123"));
        check("handles: a numeric handle with no comma is kept",
                "example.com/@2024".equals(VisitModel.key("https://example.com/@2024")),
                VisitModel.key("https://example.com/@2024"));
        check("data= without the ! is an ordinary segment",
                "example.com/data=raw/file".equals(VisitModel.key("https://example.com/data=raw/file")),
                VisitModel.key("https://example.com/data=raw/file"));

        // 9. A capture made FOR a page belongs to it (GeckoState.originNamesPage):
        //    a feed captures its items before the user opens one, and the item
        //    must be on the item's page, not behind the fold.
        check("origin: Instagram post opened from a profile",
                VisitModel.belongs("https://www.instagram.com/p/CODE123",
                        "https://www.instagram.com/p/CODE123/"), "");
        check("origin: the post page after Continue on web (?l=1)",
                VisitModel.belongs("https://www.instagram.com/p/CODE123",
                        "https://www.instagram.com/p/CODE123/?l=1"), "");
        check("origin: another post is not this page",
                !VisitModel.belongs("https://www.instagram.com/p/CODE123",
                        "https://www.instagram.com/p/CODE999/"), "");
        check("origin: the profile is not the post's page",
                !VisitModel.belongs("https://www.instagram.com/p/CODE123",
                        "https://www.instagram.com/someone/"), "");
        check("origin: TikTok video opened from a profile, share params on top",
                VisitModel.belongs("https://www.tiktok.com/@user/video/7312",
                        "https://www.tiktok.com/@user/video/7312?is_from_webapp=1&sender_device=pc"), "");
        check("origin: X tweet opened from the timeline",
                VisitModel.belongs("https://x.com/someone/status/1790",
                        "https://x.com/someone/status/1790?s=20"), "");
        check("origin: the tweet's photo viewer is another page",
                !VisitModel.belongs("https://x.com/someone/status/1790",
                        "https://x.com/someone/status/1790/photo/1"), "");
        check("origin: Facebook canonical on the m. item page with ref noise",
                VisitModel.belongs("https://www.facebook.com/watch/?v=123",
                        "https://m.facebook.com/watch/?v=123&ref=sharing"), "");
        check("origin: a Facebook item is not the Watch hub",
                !VisitModel.belongs("https://www.facebook.com/watch/?v=123",
                        "https://www.facebook.com/watch/"), "");
        check("origin: a YouTube video inside its Mix",
                VisitModel.belongs("https://www.youtube.com/watch?v=AAAAAAAAAAA",
                        "https://m.youtube.com/watch?v=AAAAAAAAAAA&list=RDAAAAAAAAAAA&index=2"), "");
        check("origin: another YouTube video is not this page",
                !VisitModel.belongs("https://www.youtube.com/watch?v=AAAAAAAAAAA",
                        "https://www.youtube.com/watch?v=BBBBBBBBBBB"), "");
        check("origin: an empty or opaque origin never matches",
                !VisitModel.belongs("", "https://example.com/a")
                        && !VisitModel.belongs(null, "https://example.com/a")
                        && !VisitModel.belongs("about:blank", "https://example.com/a"), "");
    }
}
