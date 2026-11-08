package com.solarized.firedown.geckoview.toolbar;

import java.util.Arrays;
import java.util.Locale;

/**
 * Drives the REAL DownloadsLineGeometry (copied from app/src by run.sh): the
 * Downloads button's tray at rest must be exactly Material's "download" tray,
 * the progress line must stay inside the 24dp icon box, 4dp under the arrow's
 * tip and as wide as the arrow's reach plus a margin, the fill must start at
 * the start edge (the right one in RTL) and never vanish, and the sliding
 * piece must stay on the line.
 */
public final class LineHarness {
    private static int sChecks;
    private static int sFailures;

    private static void check(String name, boolean ok, String detail) {
        sChecks++;
        if (!ok) {
            sFailures++;
            System.out.println("FAIL " + name + "  [" + detail + "]");
        }
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-4f;
    }

    public static void main(String[] args) {
        // Material Symbols "download", 960 units = 24dp: tray base 160..800 x 720..800, sides 600..720.
        float[] rest = DownloadsLineGeometry.base(0f);
        check("rest base is the tray's base (x 4..20, y 18..20)",
                near(rest[0], 4) && near(rest[1], 18) && near(rest[2], 20) && near(rest[3], 20), Arrays.toString(rest));
        float[] sides = DownloadsLineGeometry.sides(0f);
        check("rest sides are the tray's (x 4..6 and 18..20, y 15..18)",
                sides.length == 8 && near(sides[0], 4) && near(sides[1], 15) && near(sides[2], 6) && near(sides[3], 18)
                        && near(sides[4], 18) && near(sides[5], 15) && near(sides[6], 20) && near(sides[7], 18),
                Arrays.toString(sides));
        check("the sides are gone once the line is in place", DownloadsLineGeometry.sides(1f).length == 0, "");

        float[] line = DownloadsLineGeometry.base(1f);
        float arrowTip = 640f / 40f;   // 16
        check("the line is 4dp under the arrow's tip (the board-19 gap of 2 read as a small icon)",
                near(line[1] - arrowTip, 4f), "gap=" + (line[1] - arrowTip));
        check("the line is 2dp wider than the tray's base, centred on it",
                near(line[2] - line[0], 18f) && near((line[0] + line[2]) / 2f, 12f), Arrays.toString(line));
        check("the line stays inside the 24dp icon box",
                line[0] >= 0 && line[2] <= 24 && line[3] <= 24, Arrays.toString(line));
        check("the line is the glyph's own stroke weight (2dp)", near(line[3] - line[1], 2f), "");

        // Every morph step stays in the box and between rest and line.
        for (int i = 0; i <= 100; i++) {
            float a = i / 100f;
            float[] b = DownloadsLineGeometry.base(a);
            check(String.format(Locale.ROOT, "morph %.2f inside the box", a),
                    b[0] >= 0 && b[2] <= 24 && b[1] >= 18 - 1e-4f && b[3] <= 22 + 1e-4f, Arrays.toString(b));
            float[] s = DownloadsLineGeometry.sides(a);
            if (s.length == 8) {
                check(String.format(Locale.ROOT, "morph %.2f sides sit on the base", a),
                        near(s[3], b[1]) && near(s[7], b[1]) && s[1] <= s[3], Arrays.toString(s));
            }
        }

        // Fill: start edge, never under 2dp, full at 1.
        for (int p = 0; p <= 100; p++) {
            float f = p / 100f;
            float[] ltr = DownloadsLineGeometry.fill(f, false);
            float[] rtl = DownloadsLineGeometry.fill(f, true);
            float width = Math.max(2f, 18f * f);
            check("fill " + p + "% LTR from the left", near(ltr[0], 3) && near(ltr[1] - ltr[0], width), Arrays.toString(ltr));
            check("fill " + p + "% RTL from the right", near(rtl[1], 21) && near(rtl[1] - rtl[0], width), Arrays.toString(rtl));
        }

        // The sliding piece: on the line, enters at the start edge, mirrored in RTL.
        boolean seenEmptyStart = DownloadsLineGeometry.segment(0f, false).length == 0;
        check("the piece starts just off the start edge", seenEmptyStart, "");
        float[] early = DownloadsLineGeometry.segment(0.1f, false);
        float[] earlyRtl = DownloadsLineGeometry.segment(0.1f, true);
        check("early in a sweep the piece is at the start edge (LTR left)", early.length == 2 && near(early[0], 3), Arrays.toString(early));
        check("early in a sweep the piece is at the start edge (RTL right)", earlyRtl.length == 2 && near(earlyRtl[1], 21), Arrays.toString(earlyRtl));
        for (int i = 0; i <= 200; i++) {
            float phase = i / 200f;
            for (boolean r : new boolean[] {false, true}) {
                float[] s = DownloadsLineGeometry.segment(phase, r);
                if (s.length == 2) {
                    check("piece on the line at " + phase, s[0] >= 3 - 1e-4f && s[1] <= 21 + 1e-4f && s[1] - s[0] <= 6 + 1e-4f
                            && s[1] > s[0], Arrays.toString(s));
                }
            }
        }
        float[] still = DownloadsLineGeometry.stillSegment();
        check("animations off: a still 6dp piece in the middle", near(still[0], 9) && near(still[1], 15), Arrays.toString(still));

        System.out.println(String.format(Locale.ROOT, "%d checks, %d failures", sChecks, sFailures));
        System.exit(sFailures == 0 ? 0 : 1);
    }
}
