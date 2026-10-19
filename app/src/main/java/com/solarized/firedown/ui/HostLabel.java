package com.solarized.firedown.ui;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.R;
import com.google.android.material.color.MaterialColors;

/**
 * A blocked host as both blocked-host lists print it — Settings → Trackers
 * and ads blocked ("Most blocked domains today") and the security sheet's
 * "Blocked on this page" drill-in: the registrable domain, the site's own
 * name, in onSurface at medium weight, and any subdomain before it in
 * onSurface at {@link #SUBDOMAIN_ALPHA} ({@code pagead2.}{@code
 * googlesyndication.com}). Reading a column of tracker hosts is reading the
 * second half of each one; this puts the eye there, the way a browser's
 * address bar shows a page's domain.
 *
 * <p>The subdomain is NOT onSurfaceVariant: in dark theme that grey is
 * 1.32:1 from onSurface, and on-device the split was invisible ("the
 * highlight is hardly visible"). onSurface at 64% sits about 2:1 from the
 * domain in dark theme and 3:1 in light while keeping 4.5:1 on every ground
 * the rows land on — dark sheet card 6.2:1, dark settings card 5.5:1, OLED
 * 6.2:1, the incognito sheet's purple 5.2:1, light 5.0:1 and 4.8:1. An
 * alpha of the theme's own ink rather than a fixed grey is what keeps the
 * incognito overlay covered. Below about 60% the light settings card drops
 * under 4.5:1; re-measure before moving it.</p>
 *
 * <p>The domain comes from uBlock's own public suffix list (firedown.js ships
 * it beside every host), so there is no second PSL on the Java side. When it
 * is missing or doesn't end the host on a label boundary (an IP, an older
 * extension build), the whole host takes the domain style — never a guess at
 * where the site's name starts.</p>
 */
public final class HostLabel {

    /** The subdomain's share of onSurface — see the class comment. */
    private static final float SUBDOMAIN_ALPHA = 0.64f;

    private HostLabel() {}

    @NonNull
    public static CharSequence format(@NonNull Context context, @NonNull String host,
                                      @Nullable String domain) {
        int domainInk = MaterialColors.getColor(context,
                R.attr.colorOnSurface, 0);
        int subInk = ColorUtils.setAlphaComponent(domainInk,
                Math.round(SUBDOMAIN_ALPHA * 255));
        SpannableString label = new SpannableString(host);
        int split = splitIndex(host, domain);
        if (split > 0) {
            label.setSpan(new ForegroundColorSpan(subInk), 0, split,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        label.setSpan(new ForegroundColorSpan(domainInk), split, host.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new TypefaceSpan("sans-serif-medium"), split, host.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    /** Where the registrable domain starts in {@code host}, 0 when it is the whole host. */
    private static int splitIndex(@NonNull String host, @Nullable String domain) {
        if (domain == null || domain.isEmpty() || domain.length() >= host.length()) {
            return 0;
        }
        if (!host.endsWith(domain)) {
            return 0;
        }
        int split = host.length() - domain.length();
        if (host.charAt(split - 1) != '.') {
            return 0;
        }
        return split;
    }
}
