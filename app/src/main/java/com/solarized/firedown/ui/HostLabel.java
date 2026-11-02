package com.solarized.firedown.ui;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.R;
import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.utils.RegistrableDomain;

/**
 * A blocked host as both blocked-host lists print it — Settings → Trackers
 * and ads blocked ("Most blocked domains today") and the security sheet's
 * "Blocked on this page" drill-in: the registrable domain, the site's own
 * name, in onSurface, and any subdomain before it in onSurface at
 * {@link #SUBDOMAIN_ALPHA} ({@code pagead2.}{@code googlesyndication.com}).
 * Reading a column of tracker hosts is reading the second half of each one;
 * this puts the eye there, the way a browser's address bar shows a page's
 * domain — by COLOUR only, one weight (Chrome's omnibox). The domain was
 * medium weight too for one build: at 16sp a column of hosts read louder
 * than anything else in the sheet, and with the dimmer subdomain the
 * colour split carries it alone. The rows are 14sp (body-medium) for the
 * same reason: a host is a fact to scan, not a menu item.
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
 * <p>The domain comes from the public suffix list OkHttp ships
 * ({@link RegistrableDomain}), so {@code bbc.co.uk} is the site's name in
 * {@code static.files.bbc.co.uk}, not {@code co.uk}. When there is none (an
 * IP, a host that is itself a public suffix, the list not read yet in the
 * first moment after start), the whole host takes the domain style — never
 * a guess at where the site's name starts. The patched uBlock used to send
 * its own PSL answer beside each host; the capture extension's counter that
 * replaced it has no list, which is why the split is worked out here.</p>
 */
public final class HostLabel {

    /** The subdomain's share of onSurface — see the class comment. */
    private static final float SUBDOMAIN_ALPHA = 0.64f;

    private HostLabel() {}

    @NonNull
    public static CharSequence format(@NonNull Context context, @NonNull String host) {
        int domainInk = MaterialColors.getColor(context,
                R.attr.colorOnSurface, 0);
        int subInk = ColorUtils.setAlphaComponent(domainInk,
                Math.round(SUBDOMAIN_ALPHA * 255));
        SpannableString label = new SpannableString(host);
        int split = splitIndex(host, RegistrableDomain.of(host));
        if (split > 0) {
            label.setSpan(new ForegroundColorSpan(subInk), 0, split,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        label.setSpan(new ForegroundColorSpan(domainInk), split, host.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    /** Where the registrable domain starts in {@code host}, 0 when it is the whole host. */
    private static int splitIndex(@NonNull String host, @Nullable String domain) {
        if (domain == null || domain.isEmpty() || domain.length() >= host.length()) {
            return 0;
        }
        int split = host.length() - domain.length();
        if (!host.regionMatches(true, split, domain, 0, domain.length())) {
            return 0;
        }
        if (host.charAt(split - 1) != '.') {
            return 0;
        }
        return split;
    }
}
