package com.solarized.firedown.phone.dialogs;

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
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.solarized.firedown.R;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
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
 * "Translate page" sheet for Gecko's built-in translator. The engine
 * (Bergamot, on-device WASM) and the per-language models are GeckoView's;
 * this sheet only picks the pair and asks the current tab's
 * {@link TranslationsController.SessionTranslation} to translate.
 *
 * <p>Pre-selection comes from the tab's last
 * {@code TranslationState} (the document language Gecko detected, the
 * user's preferred language); the supported lists come from
 * {@code RuntimeTranslation.listSupportedLanguages()}. Once a pair is chosen
 * the hint line states the size of any model download the pair needs —
 * the one network touch of the whole feature (Mozilla's Remote Settings +
 * attachment CDN), so it is named before it happens rather than after.
 * Translating with {@code downloadModel=true} then fetches and translates in
 * one step; the sheet closes as soon as Gecko accepts the request and the
 * page changes in place.</p>
 *
 * <p>Every GeckoResult callback re-checks {@code mView} — the sheet can be
 * dismissed while a lookup is in flight.</p>
 */
@AndroidEntryPoint
public class TranslateSheetDialogFragment extends BaseBottomSheetDialogFragment {

    private static final String TAG = TranslateSheetDialogFragment.class.getName();

    private GeckoState mGeckoState;

    private MaterialAutoCompleteTextView mFrom;
    private MaterialAutoCompleteTextView mTo;
    private TextView mHint;
    private MaterialButton mGo;

    private final List<TranslationsController.Language> mFromLanguages = new ArrayList<>();
    private final List<TranslationsController.Language> mToLanguages = new ArrayList<>();
    private int mFromIndex = -1;
    private int mToIndex = -1;

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
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mFrom = null;
        mTo = null;
        mHint = null;
        mGo = null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_translate, container, false);

        mFrom = mView.findViewById(R.id.translate_from);
        mTo = mView.findViewById(R.id.translate_to);
        mHint = mView.findViewById(R.id.translate_hint);
        mGo = mView.findViewById(R.id.translate_go);

        mView.findViewById(R.id.translate_cancel).setOnClickListener(v -> close());
        mView.findViewById(R.id.translate_never_site).setOnClickListener(v -> neverTranslateSite());
        mGo.setOnClickListener(v -> translate());

        mFrom.setOnItemClickListener((parent, view, position, id) -> {
            mFromIndex = position;
            onPairChanged();
        });
        mTo.setOnItemClickListener((parent, view, position, id) -> {
            mToIndex = position;
            onPairChanged();
        });

        if (sessionTranslation() == null) {
            // No live session behind the sheet (tab closed under it) — say
            // so rather than offer a Translate button that can do nothing.
            mHint.setText(R.string.translate_error);
            return mView;
        }

        loadLanguages();
        return mView;
    }

    @Nullable
    private TranslationsController.SessionTranslation sessionTranslation() {
        if (mGeckoState == null) return null;
        GeckoSession session = mGeckoState.getGeckoSession();
        if (session == null) return null;
        return session.getSessionTranslation();
    }

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
                    preselect();
                    onPairChanged();
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
     * preferred language as Gecko sees it, else the device locale. A tag
     * that isn't in the supported list leaves the field empty — the user
     * picks, and Translate stays disabled until both are set.
     */
    private void preselect() {
        mFromIndex = indexOf(mFromLanguages, mGeckoState.getDetectedDocLanguage());
        String userTag = mGeckoState.getDetectedUserLanguage();
        if (TextUtils.isEmpty(userTag)) userTag = Locale.getDefault().toLanguageTag();
        mToIndex = indexOf(mToLanguages, userTag);
        if (mFromIndex >= 0) {
            mFrom.setText(TranslationLanguages.displayName(mFromLanguages.get(mFromIndex)), false);
        }
        if (mToIndex >= 0) {
            mTo.setText(TranslationLanguages.displayName(mToLanguages.get(mToIndex)), false);
        }
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
            mHint.setText("");
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

    private void neverTranslateSite() {
        TranslationsController.SessionTranslation translation = sessionTranslation();
        if (translation == null) {
            close();
            return;
        }
        translation.setNeverTranslateSiteSetting(true).accept(
                unused -> {
                    if (mView == null) return;
                    close();
                },
                error -> {
                    if (mView == null) return;
                    Log.d(TAG, "setNeverTranslateSiteSetting failed", error);
                    mHint.setText(R.string.translate_error);
                });
    }

    private static boolean isEngineUnsupported(@Nullable Throwable error) {
        return error instanceof TranslationsController.TranslationsException exception
                && exception.code == TranslationsController.TranslationsException.ERROR_ENGINE_NOT_SUPPORTED;
    }

    private void close() {
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_translate);
    }
}
