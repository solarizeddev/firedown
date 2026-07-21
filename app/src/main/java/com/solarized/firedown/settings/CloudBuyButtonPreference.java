package com.solarized.firedown.settings;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.Preference;

import com.solarized.firedown.R;

/**
 * The Cloud screen's morphing CTA row, with STATE-DEPENDENT emphasis: the
 * FILLED button ({@code preference_cloud_buy_button}) while the next onboarding
 * step is genuinely "pay" — pre-key ("Create recovery code"), unfunded, or in
 * grace — and the OUTLINED variant ({@code preference_cloud_buy_button_plain})
 * once the account is comfortably funded, so a years-of-runway account isn't
 * stared down by a permanent filled sales button (the calm-by-default ethos:
 * same reasoning as the home pill's GONE-when-idle). The hero alert + amber
 * grace state re-escalate the top-up moment when it matters, so no conversion
 * surface is lost — only permanent visual pressure.
 *
 * <p>Swapping {@link #setLayoutResource} yields a new RecyclerView view type,
 * and {@code notifyChanged()} rebinds through it — the standard
 * PreferenceGroupAdapter path (it registers unseen layouts on demand in
 * getItemViewType), no adapter surgery needed. The label keeps riding the
 * Preference TITLE in both layouts (the button carries {@code @android:id/title}).
 */
public class CloudBuyButtonPreference extends Preference {

    /** Matches the XML default layout (the filled variant). */
    private boolean mEmphasized = true;

    public CloudBuyButtonPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Filled (true) vs outlined (false) — no-op when unchanged, so the
     *  every-updateState caller never causes a rebind churn. */
    public void setEmphasized(boolean emphasized) {
        if (mEmphasized == emphasized) {
            return;
        }
        mEmphasized = emphasized;
        setLayoutResource(emphasized
                ? R.layout.preference_cloud_buy_button
                : R.layout.preference_cloud_buy_button_plain);
        notifyChanged();
    }
}
