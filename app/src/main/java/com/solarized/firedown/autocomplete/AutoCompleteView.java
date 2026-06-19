package com.solarized.firedown.autocomplete;

import static android.content.Context.CLIPBOARD_SERVICE;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.solarized.firedown.R;
import com.solarized.firedown.ui.AutocompleteSectionDecoration;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.utils.UrlStringUtils;

public class AutoCompleteView extends FrameLayout {

    private static final String TAG = AutoCompleteView.class.getSimpleName();

    /** Longest clipboard text the chip will offer. Generous for any real URL
     *  (data: URIs included) or search phrase; everything beyond this is a
     *  pasted document/log dump that must not be volunteered into the
     *  address bar (see showClipboard). */
    private static final int MAX_CLIP_SUGGESTION_LENGTH = 8192;

    private final Context mContext;

    private AutoCompleteRecyclerView mSearchView;

    private View mSearchCard;

    private AutocompleteSectionDecoration mSectionDecoration;

    private AppCompatImageButton mVisibilityView;

    private TextView mClipboardTextView;

    private ImageView mClipboardImage;

    private View mClipboardView;

    /** Clip text the user already acted on (tapped the chip). While the
     *  system clipboard still holds this exact text, the chip stays hidden —
     *  it only re-offers once the clipboard content actually changes. */
    @Nullable private String mDismissedClipText;

    private OnClipboardListener mCallback;

    public interface OnClipboardListener{
        void onClipboardClick(CharSequence text);

        void onClipboardLongClick(CharSequence text);
    }

    public AutoCompleteView(@NonNull Context context) {
        super(context);
        mContext = context;
        init(context);
    }

    public AutoCompleteView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        init(context);
    }

    public AutoCompleteView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mContext = context;
        init(context);
    }

    public AutoCompleteView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mContext = context;
        init(context);
    }


    private void init(Context context){

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        View v = inflater.inflate(R.layout.fragment_autocomplete_view, this, true);

        v.setVisibility(View.GONE);

        v.setElevation(context.getResources().getDimensionPixelSize(R.dimen.autocomplete_elevation));

        mClipboardTextView = v.findViewById(R.id.clipboard_subtitle);

        mClipboardImage = v.findViewById(R.id.clipboard_image);

        // Trailing forward action. The content is shown by default now
        // (Option A), so this is a 'go' affordance, not the old reveal/hide
        // eye toggle — tapping it acts on the clip just like tapping the card.
        mVisibilityView = v.findViewById(R.id.clipboard_button);
        mVisibilityView.setOnClickListener(v1 -> consumeClipboard());

        mSearchView = v.findViewById(R.id.search_view);

        mSearchCard = v.findViewById(R.id.search_card);

        mClipboardView = v.findViewById(R.id.clipboard_view);

        //Avoid blinking
        mSearchView.setItemAnimator(null);

        // Inset hairlines between suggestion sections (search / history / tabs).
        mSectionDecoration = new AutocompleteSectionDecoration(context);
        mSearchView.addItemDecoration(mSectionDecoration);

        mClipboardView.setOnClickListener(v2 -> consumeClipboard());

        mClipboardView.setOnLongClickListener(view -> {
            if(mCallback != null){
                mCallback.onClipboardLongClick(mClipboardTextView.getText());
                return true;
            }
            return false;
        });

    }


    public void updateTheme(Activity activity, boolean incognito) {
        // Quiet-holder default (both Homes): the overlay root sits on plain
        // surface, matching GeckoToolbar's quiet holder above it.
        updateTheme(activity, incognito, false);
    }

    /**
     * @param tonalHolder true on the BROWSER (regular + incognito), whose
     *     GeckoToolbar holder is surfaceContainer (the framed/tonal chrome).
     *     The overlay root must then ALSO be surfaceContainer so the focused
     *     panel reads as a seamless extension of the toolbar it drops from —
     *     otherwise the toolbar strip (surfaceContainer) and the overlay body
     *     (surface) show a visible seam. On Home the holder is plain surface,
     *     so false keeps the two coinciding (as they already did). Mirrors the
     *     identical tonalHolder branch in GeckoToolbar.updateTheme.
     */
    public void updateTheme(Activity activity, boolean incognito, boolean tonalHolder) {

        int surfaceColor = tonalHolder
                ? IncognitoColors.getSurfaceContainer(activity, incognito)
                : IncognitoColors.getSurface(activity, incognito);
        int surfaceContainerHighest = IncognitoColors.getSurfaceContainerHighest(activity, incognito);
        int onSurfaceColor = IncognitoColors.getOnSurface(activity, incognito);
        int onSurfaceVariant = IncognitoColors.getOnSurfaceVariant(activity, incognito);

        // 1. Root LinearLayout background
        View root = getChildAt(0);
        if (root != null) {
            root.setBackgroundColor(surfaceColor);
        }

        // 2. Clipboard card background
        if (mClipboardView instanceof MaterialCardView card) {
            card.setCardBackgroundColor(surfaceContainerHighest);
        }

        // 3. Clipboard title
        TextView clipboardTitle = findViewById(R.id.clipboard_title);
        if (clipboardTitle != null) {
            clipboardTitle.setTextColor(onSurfaceColor);
        }

        // 4. Clipboard subtitle
        if (mClipboardTextView != null) {
            mClipboardTextView.setTextColor(onSurfaceVariant);
        }

        // 5. Clipboard icon. It's now a bare 24dp glyph aligned to the list
        //    favicons (no filled chip badge), so only the icon tint needs the
        //    incognito palette here.
        if (mClipboardImage != null) {
            ImageViewCompat.setImageTintList(mClipboardImage,
                    ColorStateList.valueOf(onSurfaceVariant));
        }

        // 6. Trailing forward ('go') button — themed primary so it reads as
        //    the action accent (matches the layout's ?attr/colorPrimary).
        if (mVisibilityView != null) {
            ImageViewCompat.setImageTintList(mVisibilityView, ColorStateList.valueOf(
                    IncognitoColors.getPrimary(activity, incognito)));
        }

        // 7. Suggestion container card. Carries the single list surface now
        //    that rows are flat. Match the focused address-bar pill (which
        //    resolves to the 'highest' tone) so the list and the pill above
        //    it sit on the same surface. Per-row text/icon tints are handled
        //    in the adapter via setIncognito().
        if (mSearchCard instanceof MaterialCardView card) {
            card.setCardBackgroundColor(surfaceContainerHighest);
        }

        // 8. Section dividers. Derive a hairline from onSurfaceVariant at low
        //    alpha so it reads as a subtle outline in both regular and
        //    incognito (no dedicated outline token exists for incognito).
        if (mSectionDecoration != null) {
            mSectionDecoration.setColor(
                    ColorUtils.setAlphaComponent(onSurfaceVariant, 0x40));
            mSearchView.invalidateItemDecorations();
        }
    }

    public void setClipboardCallback(OnClipboardListener onClipboardListener){
        mCallback = onClipboardListener;
    }

    public RecyclerView getRecyclerView(){
        return mSearchView;
    }


    public void showEmpty() {
        showClipboard();
        mSearchView.setVisibility(View.GONE);
        // Hide the container card too, otherwise its filled surface would
        // show as an empty panel under the address bar with no rows in it.
        if (mSearchCard != null) mSearchCard.setVisibility(View.GONE);
    }

    public void hideAll(){
        hideClipboard();
        mSearchView.setVisibility(View.VISIBLE);
        if (mSearchCard != null) mSearchCard.setVisibility(View.VISIBLE);
    }

    /** Empty-focus state, Option A: the clipboard chip AND the "most visited"
     *  list shown TOGETHER — unlike {@link #showEmpty()} (clipboard only) or
     *  {@link #hideAll()} (list only, the typed-results state). showClipboard()
     *  re-evaluates the chip (so a dismissed/oversized/empty clip is still
     *  correctly withheld). */
    public void showMostVisited(){
        showClipboard();
        mSearchView.setVisibility(View.VISIBLE);
        if (mSearchCard != null) mSearchCard.setVisibility(View.VISIBLE);
    }

    public void updateVisibility(boolean hasFocus){
        setVisibility(hasFocus ? View.VISIBLE : View.GONE);
    }

    /** Acts on the clipboard chip and remembers the text as dismissed, so it
     *  won't be re-offered until the clipboard content changes. */
    private void consumeClipboard() {
        CharSequence text = mClipboardTextView.getText();
        mDismissedClipText = text == null ? null : text.toString();
        hideClipboard();
        if (mCallback != null) mCallback.onClipboardClick(text);
    }

    public void showClipboard(){
        ClipboardManager clipboardManager = (ClipboardManager) mContext.getSystemService(CLIPBOARD_SERVICE);
        // Cache the clip locally — the previous five-time
        // getPrimaryClip() call chain triggered Android 13's 'App
        // pasted from your clipboard' toast on every visibility
        // check. One call now.
        //
        // The read is a binder call into system_server's clipboard service
        // and MUST NOT be fatal: when system_server is dying/restarting,
        // getPrimaryClip() throws RuntimeException(DeadSystemException) —
        // observed on-device killing the app on the URL-bar focus tap that
        // races the system's death (the process is doomed either way, but
        // crashing here files a junk crash report and loses any in-flight
        // state). Some OEM clipboard services also throw SecurityException
        // on background reads. The chip is a convenience suggestion, so on
        // any failure just don't offer it.
        ClipData clip = null;
        try {
            if (clipboardManager != null) {
                clip = clipboardManager.getPrimaryClip();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "showClipboard: clipboard read failed", e);
        }
        if (clip == null || clip.getItemCount() == 0) {
            mClipboardView.setVisibility(View.GONE);
            return;
        }
        // Skip the MIME-type filter and trust coerceToText. Browsers
        // (Brave etc.) put URL clips under text/uri-list, which the
        // old MIMETYPE_TEXT_PLAIN || MIMETYPE_TEXT_HTML check
        // silently rejected — the user saw no clipboard chip even
        // though they'd just copied a URL. coerceToText handles
        // every supported representation and returns empty for
        // unsupported ones, so an empty result is the right
        // hide-the-chip signal.
        CharSequence raw = clip.getItemAt(0).coerceToText(mContext);
        String text = raw == null ? "" : raw.toString();
        if (text.isEmpty()) {
            mClipboardView.setVisibility(View.GONE);
            return;
        }
        // Don't offer an absurdly long clip (a pasted log dump, a whole
        // document). No real URL or search term comes close to this cap, and
        // tapping the chip would shuttle the entire blob into the address
        // bar — from where it gets carried across binder again and again
        // (EditText view-state save on every onStop, accessibility
        // announcements) and churned through text-change handling. The user
        // can still long-press-paste deliberately; we just don't volunteer it.
        if (text.length() > MAX_CLIP_SUGGESTION_LENGTH) {
            mClipboardView.setVisibility(View.GONE);
            return;
        }
        // Already acted on this exact clip — don't re-offer it. Re-offer only
        // once the clipboard content changes (text no longer matches).
        if (text.equals(mDismissedClipText)) {
            mClipboardView.setVisibility(View.GONE);
            return;
        }
        mClipboardTextView.setText(text);
        mClipboardTextView.setVisibility(View.VISIBLE);
        // Adaptive leading icon: a globe when the clip looks like a URL
        // (tap navigates), a magnifier otherwise (tap searches it) — mirrors
        // what openUri()/parseUri does with the same text downstream.
        if (mClipboardImage != null) {
            mClipboardImage.setImageResource(
                    UrlStringUtils.isURLLike(text.trim())
                            ? R.drawable.ic_globe_24
                            : R.drawable.ic_search_24);
        }
        if (mClipboardView.getVisibility() == View.GONE) {
            mClipboardView.setVisibility(View.VISIBLE);
        }
    }

    public void hideClipboard(){
        mClipboardView.setVisibility(View.GONE);
    }


}
