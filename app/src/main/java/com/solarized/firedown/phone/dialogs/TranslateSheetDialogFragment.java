package com.solarized.firedown.phone.dialogs;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.solarized.firedown.R;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.TranslationLanguageSettings;
import com.solarized.firedown.geckoview.TranslationLanguages;
import com.solarized.firedown.utils.NavigationUtils;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.TranslationsController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The translate sheet for Gecko's built-in translator — one sheet with two
 * faces (see {@code fragment_dialog_translate.xml}), picked from the tab's
 * translation state when it opens:
 *
 * <ul>
 * <li><b>PICKER</b> — the page is not translated: From/To over the languages
 * Gecko reports as supported, the hint line, the options, Cancel/Translate.
 * Opened by the popup's "Translate page" row, the offer card's Translate,
 * its ⋮ "Choose another language", and the quiet address-bar glyph.</li>
 * <li><b>TRANSLATED</b> — the page shows a translation: a lit header naming
 * the pair, Show original, Change languages (flips to the picker in place,
 * preselected with the pair in use), the same options. Opened by the lit
 * glyph.</li>
 * </ul>
 *
 * <p>The engine (Bergamot, on-device WASM) and the per-language models are
 * GeckoView's; this sheet only picks the pair and asks the current tab's
 * {@link TranslationsController.SessionTranslation} to translate.</p>
 *
 * <p>Pre-selection comes from the tab's last {@code TranslationState} (the
 * document language Gecko detected, the user's preferred language); the
 * supported lists come from {@code RuntimeTranslation.listSupportedLanguages()}.
 * Detection is asynchronous on Gecko's side (a content actor samples the
 * page text, CLD2 classifies it in a worker, and only then does a
 * {@code TranslationState} with {@code docLangTag} reach the delegate), so
 * a sheet opened from the popup on a page that has just loaded can find no
 * document language yet. The sheet therefore OBSERVES the tab's state
 * changes ({@code getTranslationStateChanges()}, fed by the delegate for
 * every tab) and fills From — and To, if still empty — in place when
 * detection lands, showing "Detecting language…" in the hint line until it
 * does. Fields the user has already picked are never overwritten. Once a
 * pair is chosen the hint line states the size of any model download the
 * pair needs — the one network touch of the whole feature (Mozilla's Remote
 * Settings + attachment CDN), so it is named before it happens rather than
 * after. Translating with {@code downloadModel=true} then fetches and
 * translates in one step; the sheet closes as soon as Gecko accepts the
 * request and the page changes in place.</p>
 *
 * <p><b>The options are three switches over Gecko's OWN stores</b> — the
 * per-language setting ({@link TranslationLanguageSettings}: always / offer
 * / never, so "Always translate X" and "Never translate X" are one three-way
 * choice: turning either on writes that value and clears the other, turning
 * one off writes {@code offer}) and the per-site setting
 * ({@code SessionTranslation.setNeverTranslateSiteSetting}). The language
 * rows follow the FROM field — a user who picks a different source language
 * is asked about that one — and fall back to the detected language while
 * From is empty. Every switch write reads back on failure so the sheet
 * never shows a state Gecko didn't take. No storage of ours, so the offer
 * card's ⋮ and the Settings lists see the same answer.</p>
 *
 * <p>Every GeckoResult callback re-checks {@code mView} — the sheet can be
 * dismissed while a lookup is in flight.</p>
 */
@AndroidEntryPoint
public class TranslateSheetDialogFragment extends BaseBottomSheetDialogFragment {

    private static final String TAG = TranslateSheetDialogFragment.class.getName();

    private GeckoState mGeckoState;

    private View mTranslatedHeader;
    private View mPicker;
    private View mActions;
    private TextView mTranslatedTitle;
    private TextView mTranslatedSubtitle;
    private MaterialAutoCompleteTextView mFrom;
    private MaterialAutoCompleteTextView mTo;
    private TextView mHint;
    private MaterialButton mGo;

    private View mLanguageOptions;
    private TextView mLanguageSection;
    private TextView mAlwaysLabel;
    private TextView mNeverLabel;
    private MaterialSwitch mAlwaysSwitch;
    private MaterialSwitch mNeverSwitch;
    private TextView mNeverSiteLabel;
    private MaterialSwitch mNeverSiteSwitch;

    private final List<TranslationsController.Language> mFromLanguages = new ArrayList<>();
    private final List<TranslationsController.Language> mToLanguages = new ArrayList<>();
    private int mFromIndex = -1;
    private int mToIndex = -1;
    private boolean mLanguagesLoaded;
    /** The language the option rows currently describe (bare code), or null. */
    @Nullable
    private String mOptionsLanguage;
    private LiveData<GeckoState> mTranslationStateChanges;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GeckoStateViewModel geckoStateViewModel =
                new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        IncognitoStateViewModel incognitoStateViewModel =
                new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);
        mGeckoState = mIsIncognito
                ? incognitoStateViewModel.peekCurrentGeckoState()
                : geckoStateViewModel.peekCurrentGeckoState();
        mTranslationStateChanges = mIsIncognito
                ? incognitoStateViewModel.getTranslationStateChanges()
                : geckoStateViewModel.getTranslationStateChanges();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // LiveData replays its last value on subscribe — possibly another
        // tab's, or this tab's state as it already stood at preselect() —
        // which is why the callback compares identity and only ever FILLS
        // empty fields (idempotent on a replay).
        mTranslationStateChanges.observe(getViewLifecycleOwner(), changed -> {
            if (mView == null || changed == null || changed != mGeckoState) return;
            applyDetectedLanguages();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mTranslatedHeader = null;
        mPicker = null;
        mActions = null;
        mTranslatedTitle = null;
        mTranslatedSubtitle = null;
        mFrom = null;
        mTo = null;
        mHint = null;
        mGo = null;
        mLanguageOptions = null;
        mLanguageSection = null;
        mAlwaysLabel = null;
        mNeverLabel = null;
        mAlwaysSwitch = null;
        mNeverSwitch = null;
        mNeverSiteLabel = null;
        mNeverSiteSwitch = null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_translate, container, false);

        mTranslatedHeader = mView.findViewById(R.id.translate_translated_header);
        mPicker = mView.findViewById(R.id.translate_picker);
        mActions = mView.findViewById(R.id.translate_actions);
        mTranslatedTitle = mView.findViewById(R.id.translate_translated_title);
        mTranslatedSubtitle = mView.findViewById(R.id.translate_translated_subtitle);
        mFrom = mView.findViewById(R.id.translate_from);
        mTo = mView.findViewById(R.id.translate_to);
        mHint = mView.findViewById(R.id.translate_hint);
        mGo = mView.findViewById(R.id.translate_go);
        mLanguageOptions = mView.findViewById(R.id.translate_language_options);
        mLanguageSection = mView.findViewById(R.id.translate_language_section);
        mAlwaysLabel = mView.findViewById(R.id.translate_always_label);
        mNeverLabel = mView.findViewById(R.id.translate_never_label);
        mAlwaysSwitch = mView.findViewById(R.id.translate_always_switch);
        mNeverSwitch = mView.findViewById(R.id.translate_never_switch);
        mNeverSiteLabel = mView.findViewById(R.id.translate_never_site_label);
        mNeverSiteSwitch = mView.findViewById(R.id.translate_never_site_switch);

        mView.findViewById(R.id.translate_cancel).setOnClickListener(v -> close());
        mView.findViewById(R.id.translate_show_original).setOnClickListener(v -> showOriginal());
        mView.findViewById(R.id.translate_change_languages).setOnClickListener(v -> showPickerFace());
        mView.findViewById(R.id.translate_swap).setOnClickListener(v -> swapLanguages());
        // The rows toggle their switch (the switch itself is not clickable,
        // so the whole 56dp row is the target).
        mView.findViewById(R.id.translate_always_row).setOnClickListener(v ->
                setLanguageSetting(mAlwaysSwitch.isChecked()
                        ? TranslationLanguageSettings.OFFER : TranslationLanguageSettings.ALWAYS));
        mView.findViewById(R.id.translate_never_row).setOnClickListener(v ->
                setLanguageSetting(mNeverSwitch.isChecked()
                        ? TranslationLanguageSettings.OFFER : TranslationLanguageSettings.NEVER));
        mView.findViewById(R.id.translate_never_site_row).setOnClickListener(v ->
                setNeverTranslateSite(!mNeverSiteSwitch.isChecked()));
        mGo.setOnClickListener(v -> translate());

        mFrom.setOnItemClickListener((parent, view, position, id) -> {
            mFromIndex = position;
            onPairChanged();
            bindLanguageOptions();
        });
        mTo.setOnItemClickListener((parent, view, position, id) -> {
            mToIndex = position;
            onPairChanged();
        });

        bindSiteOption();

        if (sessionTranslation() == null) {
            // No live session behind the sheet (tab closed under it) — say
            // so rather than offer a Translate button that can do nothing.
            showPickerFace();
            mHint.setText(R.string.translate_error);
            return mView;
        }

        if (mGeckoState.isPageTranslated()) {
            showTranslatedFace();
        } else {
            showPickerFace();
        }
        loadLanguages();
        return mView;
    }

    // ── Faces ─────────────────────────────────────────────────────────────

    /**
     * The TRANSLATED face: names the pair in use (from the tab's requested
     * pair — the state Gecko reported when the translation took) and offers
     * Show original / Change languages. The picker and action row are
     * hidden; the options stay.
     */
    private void showTranslatedFace() {
        TranslationsController.SessionTranslation.TranslationState state =
                mGeckoState.getTranslationState();
        TranslationsController.SessionTranslation.TranslationPair pair =
                state == null ? null : state.requestedTranslationPair;
        String to = TranslationLanguages.displayName(pair == null ? null : pair.toLanguage);
        String from = TranslationLanguages.displayName(pair == null
                ? mGeckoState.getDetectedDocLanguage() : pair.fromLanguage);
        mTranslatedTitle.setText(getString(R.string.translate_translated_title, to));
        mTranslatedSubtitle.setText(getString(R.string.translate_translated_subtitle, from));
        mTranslatedHeader.setVisibility(View.VISIBLE);
        mPicker.setVisibility(View.GONE);
        mActions.setVisibility(View.GONE);
    }

    private void showPickerFace() {
        mTranslatedHeader.setVisibility(View.GONE);
        mPicker.setVisibility(View.VISIBLE);
        mActions.setVisibility(View.VISIBLE);
    }

    @Nullable
    private TranslationsController.SessionTranslation sessionTranslation() {
        if (mGeckoState == null) return null;
        GeckoSession session = mGeckoState.getGeckoSession();
        if (session == null) return null;
        return session.getSessionTranslation();
    }

    // ── Languages ─────────────────────────────────────────────────────────

    private void loadLanguages() {
        mHint.setText(R.string.translate_loading);
        TranslationsController.RuntimeTranslation.listSupportedLanguages().accept(
                support -> {
                    if (mView == null) return;
                    mFromLanguages.clear();
                    mToLanguages.clear();
                    if (support != null) {
                        if (support.fromLanguages != null) mFromLanguages.addAll(support.fromLanguages);
                        if (support.toLanguages != null) mToLanguages.addAll(support.toLanguages);
                    }
                    Collections.sort(mFromLanguages);
                    Collections.sort(mToLanguages);
                    bindDropdown(mFrom, mFromLanguages);
                    bindDropdown(mTo, mToLanguages);
                    mLanguagesLoaded = true;
                    preselect();
                    onPairChanged();
                    bindLanguageOptions();
                },
                error -> {
                    if (mView == null) return;
                    Log.d(TAG, "listSupportedLanguages failed", error);
                    mHint.setText(isEngineUnsupported(error)
                            ? R.string.settings_translations_unavailable
                            : R.string.translate_error);
                });
    }

    private void bindDropdown(@NonNull MaterialAutoCompleteTextView view,
                              @NonNull List<TranslationsController.Language> languages) {
        List<String> names = new ArrayList<>(languages.size());
        for (TranslationsController.Language language : languages) {
            names.add(TranslationLanguages.displayName(language));
        }
        view.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, names));
    }

    /**
     * From = the language Gecko detected on the document; To = the user's
     * preferred language as Gecko sees it, else the device locale. On a
     * translated page the pair IN USE wins for both (Change languages then
     * starts from what the page shows). A tag that isn't in the supported
     * list leaves the field empty — the user picks, and Translate stays
     * disabled until both are set.
     */
    private void preselect() {
        TranslationsController.SessionTranslation.TranslationState state =
                mGeckoState.getTranslationState();
        TranslationsController.SessionTranslation.TranslationPair pair =
                mGeckoState.isPageTranslated() && state != null ? state.requestedTranslationPair : null;
        String fromTag = pair != null ? pair.fromLanguage : mGeckoState.getDetectedDocLanguage();
        String toTag = pair != null ? pair.toLanguage : mGeckoState.getDetectedUserLanguage();
        if (TextUtils.isEmpty(toTag)) toTag = Locale.getDefault().toLanguageTag();
        mFromIndex = indexOf(mFromLanguages, fromTag);
        mToIndex = indexOf(mToLanguages, toTag);
        paintPair();
    }

    private void paintPair() {
        if (mFromIndex >= 0) {
            mFrom.setText(TranslationLanguages.displayName(mFromLanguages.get(mFromIndex)), false);
        }
        if (mToIndex >= 0) {
            mTo.setText(TranslationLanguages.displayName(mToLanguages.get(mToIndex)), false);
        }
    }

    /**
     * From ⇄ To. Each side's list is looked up by code (the from- and
     * to-lists differ — a language may exist in one direction only), so a
     * side with no match ends up empty rather than wrong.
     */
    private void swapLanguages() {
        if (!mLanguagesLoaded) return;
        String fromCode = mFromIndex >= 0 ? mFromLanguages.get(mFromIndex).code : null;
        String toCode = mToIndex >= 0 ? mToLanguages.get(mToIndex).code : null;
        mFromIndex = indexOf(mFromLanguages, toCode);
        mToIndex = indexOf(mToLanguages, fromCode);
        mFrom.setText(mFromIndex >= 0
                ? TranslationLanguages.displayName(mFromLanguages.get(mFromIndex)) : "", false);
        mTo.setText(mToIndex >= 0
                ? TranslationLanguages.displayName(mToLanguages.get(mToIndex)) : "", false);
        onPairChanged();
        bindLanguageOptions();
    }

    /**
     * Re-runs the preselect for whichever field is STILL EMPTY after a
     * later {@code TranslationState} arrives on the tab. Runs only once the
     * lists exist (before that, {@code preselect()} will read the state
     * when they do), and never touches a field the user has set — a
     * detection landing after a manual From pick must not undo it.
     */
    private void applyDetectedLanguages() {
        if (!mLanguagesLoaded) return;
        boolean changed = false;
        if (mFromIndex < 0) {
            mFromIndex = indexOf(mFromLanguages, mGeckoState.getDetectedDocLanguage());
            if (mFromIndex >= 0) {
                mFrom.setText(TranslationLanguages.displayName(mFromLanguages.get(mFromIndex)), false);
                changed = true;
            }
        }
        if (mToIndex < 0) {
            String userTag = mGeckoState.getDetectedUserLanguage();
            if (TextUtils.isEmpty(userTag)) userTag = Locale.getDefault().toLanguageTag();
            mToIndex = indexOf(mToLanguages, userTag);
            if (mToIndex >= 0) {
                mTo.setText(TranslationLanguages.displayName(mToLanguages.get(mToIndex)), false);
                changed = true;
            }
        }
        if (changed) {
            onPairChanged();
            bindLanguageOptions();
        } else {
            showNoPairHint();
        }
    }

    /**
     * The hint while no pair is set: "Detecting language…" only while the
     * lists are loaded, From is empty AND Gecko has not yet reported a
     * detection result for the document. Once it has — a supported
     * language (From fills), an unsupported one, or an inconclusive run
     * (Gecko still posts a {@code detectedLanguages} block, with a null
     * tag) — there is nothing left to wait for and the line goes blank so
     * the user knows the pick is theirs. Keyed on the RESULT's presence, not
     * the tag's: keying on the tag would leave the hint up forever on a
     * page Gecko could not classify.
     */
    private void showNoPairHint() {
        boolean detecting = mLanguagesLoaded
                && mFromIndex < 0
                && mGeckoState != null
                && !mGeckoState.hasLanguageDetectionResult();
        mHint.setText(detecting ? getString(R.string.translate_detecting) : "");
    }

    /**
     * Matches a BCP 47 tag against the list by exact code first, then by
     * language subtag ({@code pt-BR} → {@code pt}): Gecko's model list
     * carries bare language codes while a document/user tag can carry a
     * region.
     */
    private static int indexOf(@NonNull List<TranslationsController.Language> languages,
                               @Nullable String tag) {
        if (TextUtils.isEmpty(tag)) return -1;
        for (int i = 0; i < languages.size(); i++) {
            if (tag.equalsIgnoreCase(languages.get(i).code)) return i;
        }
        String language = Locale.forLanguageTag(tag).getLanguage();
        if (TextUtils.isEmpty(language)) return -1;
        for (int i = 0; i < languages.size(); i++) {
            String code = languages.get(i).code;
            if (language.equalsIgnoreCase(Locale.forLanguageTag(code).getLanguage())) return i;
        }
        return -1;
    }

    private boolean hasPair() {
        return mFromIndex >= 0 && mFromIndex < mFromLanguages.size()
                && mToIndex >= 0 && mToIndex < mToLanguages.size()
                && !mFromLanguages.get(mFromIndex).code.equals(mToLanguages.get(mToIndex).code);
    }

    private void onPairChanged() {
        boolean ready = hasPair();
        mGo.setEnabled(ready);
        if (!ready) {
            showNoPairHint();
            return;
        }
        final String from = mFromLanguages.get(mFromIndex).code;
        final String to = mToLanguages.get(mToIndex).code;
        TranslationsController.RuntimeTranslation.checkPairDownloadSize(from, to).accept(
                size -> {
                    if (mView == null || !hasPair()) return;
                    // Only for the pair still selected — a slow answer for a
                    // previous pick must not label the current one.
                    if (!from.equals(mFromLanguages.get(mFromIndex).code)
                            || !to.equals(mToLanguages.get(mToIndex).code)) return;
                    if (size != null && size > 0) {
                        mHint.setText(getString(R.string.translate_download_hint,
                                Formatter.formatShortFileSize(requireContext(), size)));
                    } else {
                        mHint.setText("");
                    }
                },
                error -> {
                    if (mView == null) return;
                    Log.d(TAG, "checkPairDownloadSize failed", error);
                    mHint.setText("");
                });
    }

    private void translate() {
        TranslationsController.SessionTranslation translation = sessionTranslation();
        if (translation == null || !hasPair()) return;
        String from = mFromLanguages.get(mFromIndex).code;
        String to = mToLanguages.get(mToIndex).code;
        mGo.setEnabled(false);
        TranslationsController.SessionTranslation.TranslationOptions options =
                new TranslationsController.SessionTranslation.TranslationOptions.Builder()
                        .downloadModel(true)
                        .build();
        translation.translate(from, to, options).accept(
                unused -> {
                    if (mView == null) return;
                    close();
                },
                error -> {
                    if (mView == null) return;
                    Log.d(TAG, "translate failed", error);
                    mHint.setText(isEngineUnsupported(error)
                            ? R.string.settings_translations_unavailable
                            : R.string.translate_error);
                    mGo.setEnabled(hasPair());
                });
    }

    private void showOriginal() {
        TranslationsController.SessionTranslation translation = sessionTranslation();
        if (translation == null) {
            close();
            return;
        }
        translation.restoreOriginalPage().accept(
                unused -> {
                    if (mView == null) return;
                    close();
                },
                error -> {
                    if (mView == null) return;
                    Log.d(TAG, "restoreOriginalPage failed", error);
                    showPickerFace();
                    mHint.setText(R.string.translate_error);
                });
    }

    // ── Options: the language pair (Gecko's per-language store) ───────────

    /**
     * The language the two switches describe: the From pick when there is
     * one, else the detected document language. Relabels the rows and reads
     * the stored state; hides the block when no language is known (a page
     * Gecko could not classify, before the user picks From).
     */
    private void bindLanguageOptions() {
        String tag = mFromIndex >= 0 && mFromIndex < mFromLanguages.size()
                ? mFromLanguages.get(mFromIndex).code
                : mGeckoState == null ? null : mGeckoState.getDetectedDocLanguage();
        final String code = TranslationLanguageSettings.languageCode(tag);
        mOptionsLanguage = code;
        if (code == null) {
            mLanguageOptions.setVisibility(View.GONE);
            return;
        }
        String name = TranslationLanguages.displayName(code);
        mLanguageSection.setText(getString(R.string.translate_language_section, name));
        mAlwaysLabel.setText(getString(R.string.translate_always_language, name));
        mNeverLabel.setText(getString(R.string.translate_never_language, name));
        mLanguageOptions.setVisibility(View.VISIBLE);
        TranslationLanguageSettings.get(code).accept(
                state -> {
                    if (mView == null || !code.equals(mOptionsLanguage)) return;
                    paintLanguageSwitches(state);
                },
                error -> {
                    if (mView == null) return;
                    Log.d(TAG, "getLanguageSettings failed", error);
                    paintLanguageSwitches(TranslationLanguageSettings.OFFER);
                });
    }

    private void paintLanguageSwitches(@Nullable String state) {
        mAlwaysSwitch.setChecked(TranslationLanguageSettings.isAlways(state));
        mNeverSwitch.setChecked(TranslationLanguageSettings.isNever(state));
    }

    /**
     * Writes one of Gecko's three per-language states for the language the
     * rows describe. Painted optimistically (the switch answers the tap),
     * re-read from Gecko on failure so the sheet never shows a state it
     * didn't take. Never closes the sheet: "never translate Spanish" is a
     * preference, and the user may still want to translate THIS page.
     */
    private void setLanguageSetting(@NonNull String state) {
        final String code = mOptionsLanguage;
        if (code == null) return;
        paintLanguageSwitches(state);
        TranslationLanguageSettings.set(code, state).accept(
                unused -> { },
                error -> {
                    if (mView == null) return;
                    Log.d(TAG, "setLanguageSettings failed", error);
                    mHint.setText(R.string.translate_error);
                    bindLanguageOptions();
                });
    }

    // ── Options: this site (Gecko's per-site store) ───────────────────────

    private void bindSiteOption() {
        String host = hostOf(mGeckoState == null ? null : mGeckoState.getEntityUri());
        mNeverSiteLabel.setText(host == null
                ? getString(R.string.translate_never_site)
                : getString(R.string.translate_never_site_named, host));
        TranslationsController.SessionTranslation translation = sessionTranslation();
        if (translation == null) {
            mNeverSiteSwitch.setChecked(false);
            return;
        }
        translation.getNeverTranslateSiteSetting().accept(
                never -> {
                    if (mView == null) return;
                    mNeverSiteSwitch.setChecked(Boolean.TRUE.equals(never));
                },
                error -> {
                    if (mView == null) return;
                    Log.d(TAG, "getNeverTranslateSiteSetting failed", error);
                    mNeverSiteSwitch.setChecked(false);
                });
    }

    private void setNeverTranslateSite(boolean never) {
        TranslationsController.SessionTranslation translation = sessionTranslation();
        if (translation == null) return;
        mNeverSiteSwitch.setChecked(never);
        translation.setNeverTranslateSiteSetting(never).accept(
                unused -> { },
                error -> {
                    if (mView == null) return;
                    Log.d(TAG, "setNeverTranslateSiteSetting failed", error);
                    mHint.setText(R.string.translate_error);
                    bindSiteOption();
                });
    }

    @Nullable
    private static String hostOf(@Nullable String uri) {
        if (TextUtils.isEmpty(uri)) return null;
        try {
            String host = Uri.parse(uri).getHost();
            return TextUtils.isEmpty(host) ? null : host;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isEngineUnsupported(@Nullable Throwable error) {
        return error instanceof TranslationsController.TranslationsException exception
                && exception.code == TranslationsController.TranslationsException.ERROR_ENGINE_NOT_SUPPORTED;
    }

    private void close() {
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_translate);
    }
}
