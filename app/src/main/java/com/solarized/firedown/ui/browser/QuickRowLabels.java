package com.solarized.firedown.ui.browser;

/**
 * Pure decision for the browser popup's quick-action row: when to drop the
 * per-button text labels and render the row icon-only.
 *
 * <p>The quick-row is two stacked four-up rows — controls ({@code Back ·
 * Forward · Bookmark · Refresh}) and page actions ({@code Find · Desktop ·
 * Share · Save}) — sharing one decision (both are 4 columns of the same
 * width). Four 24dp icons always fit even on a 248dp screen. What overflows
 * first is the <b>labels</b>, and worse at large
 * accessibility font scales. Past the breakpoint the labels are dropped
 * entirely (icons never shrink or move; TalkBack + long-press tooltips carry
 * the meaning) rather than wrapping to a second line or scrolling — either
 * would re-hide the newly-promoted bookmark star, defeating the redesign.</p>
 *
 * <p>The threshold mirrors the interactive mockup's formula verbatim: a label
 * needs ~{@code LABEL_NEED_DP} at 1× (measured against the longest English
 * labels "Forward"/"Refresh"/"Bookmark"), scaling linearly with the font
 * scale; drop to icon-only once the per-column room falls below that. Kept as
 * a pure, framework-free function so it is unit-testable and so the two call
 * sites (initial inflate + {@code onConfigurationChanged}) share one source of
 * truth. This is only the <b>pre-layout estimate</b> (calibrated for English,
 * hence flicker-free): the fragment then runs a post-measure check and drops
 * the whole row to icon-only if any <i>translated</i> label would still
 * ellipsize at its real column width, so wider locales don't show "Actuali…".</p>
 */
public final class QuickRowLabels {

    /** Width one label column needs at font scale 1×, in dp. */
    private static final float LABEL_NEED_DP = 52f;
    /** Slack added to the per-label need, in dp. */
    private static final float LABEL_PAD_DP = 6f;
    /** Fixed number of buttons in the row. */
    private static final int COLUMNS = 4;
    /** Row horizontal padding consumed before the columns, in dp. */
    private static final float ROW_HORIZONTAL_PADDING_DP = 24f;

    private QuickRowLabels() {
    }

    /**
     * @param screenWidthDp the current configuration's {@code screenWidthDp}
     * @param fontScale     the current configuration's {@code fontScale}
     * @return {@code true} when the row should render icon-only (labels dropped)
     */
    public static boolean iconOnly(int screenWidthDp, float fontScale) {
        float perColumn = (screenWidthDp - ROW_HORIZONTAL_PADDING_DP) / COLUMNS;
        float need = LABEL_NEED_DP * fontScale + LABEL_PAD_DP;
        return perColumn < need;
    }
}
