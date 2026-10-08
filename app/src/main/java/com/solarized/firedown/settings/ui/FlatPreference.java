package com.solarized.firedown.settings.ui;

/**
 * Marker for a settings row that renders FLAT on the page ground instead of
 * inside a grouped card ({@link CardPreferenceGroupAdapter}): a status hero
 * (the Cloud hero, the tracking count), a custom full-width control (the
 * Cloud CTA, the DoH server field), a footnote. A flat row also BREAKS the
 * card group it sits in — the cards before and after it close off with
 * their outer corners, the same way a category header does.
 *
 * <p>Decided per CLASS on purpose: the adapter keys view types by layout +
 * class, so a holder is either always a card or always flat and never has
 * to undo the other's margins / background / icon frame on recycle.</p>
 */
public interface FlatPreference {
}
