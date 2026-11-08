package com.solarized.firedown.phone;

/**
 * Whether double-tap seek is on for a tab in page fullscreen. It turns on
 * when the tab's video is seen to be the fullscreen element or playing,
 * and then STAYS on for that fullscreen stint: a player that fullscreens
 * a wrapper around its video is known only by its playing, and the
 * gesture must not vanish the moment the user pauses to rewind.
 *
 * <p>{@code base} is what must hold on every touch (page fullscreen, the
 * tab's media session alive with a finite length, the setting on); a
 * base that fails forgets the stint, and so does {@link #clear()}, called
 * whenever page fullscreen is entered.
 */
public final class WebVideoSeekGate {

    private boolean mArmed;
    private int mArmedTab;

    /**
     * @param tabId  the tab on screen
     * @param base   everything that must hold on every touch
     * @param active the video is the fullscreen element, or plays
     */
    public boolean allow(int tabId, boolean base, boolean active) {
        if (!base) {
            mArmed = false;
            return false;
        }
        if (mArmed && mArmedTab == tabId) {
            return true;
        }
        if (active) {
            mArmed = true;
            mArmedTab = tabId;
            return true;
        }
        return false;
    }

    /** A new fullscreen stint: the video must show itself again. */
    public void clear() {
        mArmed = false;
    }
}
