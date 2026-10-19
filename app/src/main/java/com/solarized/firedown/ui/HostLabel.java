package com.solarized.firedown.ui;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.R;
import com.google.android.material.color.MaterialColors;

/**
 * A blocked host as both blocked-host lists print it — Settings → Trackers
 * and ads blocked ("Most blocked domains today") and the security sheet's
 * "Blocked on this page" drill-in: the registrable domain, the site's own
 * name, in onSurface at medium weight, and any subdomain before it in
 * onSurfaceVariant ({@code pagead2.}{@code googlesyndication.com}). Reading a
 * column of tracker hosts is reading the second half of each one; this puts
 * the eye there, the way a browser's address bar shows a page's domain.
 *
 * <p>The domain comes from uBlock's own public suffix list (firedown.js ships
 * it beside every host), so there is no second PSL on the Java side. When it
 * is missing or doesn't end the host on a label boundary (an IP, an older
 * extension build), the whole host takes the domain style — never a guess at
 * where the site's name starts.</p>
 */
public final class HostLabel {

    private HostLabel() {}

    @NonNull
    public static CharSequence format(@NonNull Context context, @NonNull String host,
                                      @Nullable String domain) {
        int subInk = MaterialColors.getColor(context,
                R.attr.colorOnSurfaceVariant, 0);
        int domainInk = MaterialColors.getColor(context,
                R.attr.colorOnSurface, 0);
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
