package com.solarized.firedown.autocomplete;


import static org.mozilla.gecko.InputMethods.getCurrentInputMethod;

import android.content.Context;
import android.graphics.Rect;
import android.text.Editable;
import android.text.NoCopySpan;
import android.text.Selection;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.ArrowKeyMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.solarized.firedown.App;
import com.solarized.firedown.R;
import com.solarized.firedown.ui.FocusEditText;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class AutoCompleteEditText extends FocusEditText {

    private static final String TAG = AutoCompleteEditText.class.getSimpleName();

    public interface OnFocusChangedListener{
        void onFocusChanged(boolean hasFocus);
    }

    public interface OnCommitListener{
        void onCommit();
    }

    public interface OnTextChangedListener{
        void onTextChanged(String afterText, String currentText);
    }

    public interface OnWindowsFocusChangeListener{
        void onWindowsFocusChanged(boolean hasFocus);
    }

    public interface OnFilterListener{
        void onRefreshAutoComplete(String text);
    }

    public interface OnSelectionChangedListener{
        void onSelectionChanged(int start, int end);
    }

    public interface OnSearchStateChangeListener{
        void onSearchStateChanged(boolean isActive);
    }

    public interface OnKeyPreImeListener{
        boolean onKeyPreIme(int keyCode, KeyEvent event);
    }

    private String mLocationUri;

    // Length of the user-typed portion of the result
    private int mAutoCompletePrefixLength;

    private List<Object> mAutoCompleteSpans;

    // If text change is due to us setting autocomplete
    private boolean mSettingAutoComplete;

    private boolean mEnableSearchMode;

    // Spans used for marking the autocomplete text
    private String mAutoCompleteResult;

    // Do not process autocomplete result
    private boolean mDiscardAutoCompleteResult;

    private final Object AUTOCOMPLETE_SPAN = new NoCopySpan.Concrete();

    private BackgroundColorSpan mBackgroundSpanColor;

    private OnCommitListener mOnCommitListener;

    private OnSearchStateChangeListener mOnSearchStateChangeListener;

    private OnFilterListener mOnFilterListener;

    private OnTextChangedListener mOnTextChangedListener;

    private OnWindowsFocusChangeListener mOnWindowsFocusChangeListener;

    private OnSelectionChangedListener mOnSelectionChangedListener;

    private OnKeyPreImeListener mOnKeyPreImeListener;

    private OnFocusChangedListener mOnFocusChangeListener;

    public AutoCompleteEditText(@NonNull Context context) {
        super(context);
        init(context);
    }

    public AutoCompleteEditText(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public AutoCompleteEditText(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public void init(Context context){

        mBackgroundSpanColor = new BackgroundColorSpan(ContextCompat.getColor(context, R.color.md_theme_inversePrimary));

        mSettingAutoComplete = false;

        mEnableSearchMode = false;

        mDiscardAutoCompleteResult = false;

        mAutoCompletePrefixLength = 0;

        setFadingEdgeLength(getResources().getDimensionPixelSize(R.dimen.address_bar_padding));

        setHorizontalFadingEdgeEnabled(true);

        setMovementMethod(null);

    }

    public void setAutoCompleteHighlightColor(int color) {
        mBackgroundSpanColor = new BackgroundColorSpan(color);
        // Refresh the spans list so new autocomplete uses the updated color
        if (mAutoCompleteSpans != null) {
            mAutoCompleteSpans.clear();
            mAutoCompleteSpans.add(AUTOCOMPLETE_SPAN);
            mAutoCompleteSpans.add(mBackgroundSpanColor);
        }
    }

    public void setOnFocusChangeListener(OnFocusChangedListener onFocusChangeListener){
        mOnFocusChangeListener = onFocusChangeListener;
    }

    public void setOnWindowsFocusChangeListener(OnWindowsFocusChangeListener onWindowsFocusChangeListener){
        mOnWindowsFocusChangeListener = onWindowsFocusChangeListener;
    }

    public void setOnFilterListener(OnFilterListener onFilterListener){
        mOnFilterListener = onFilterListener;
    }

    public void setOnTextChangedListener(OnTextChangedListener onTextChangedListener){
        mOnTextChangedListener = onTextChangedListener;
    }

    public void setOnCommitListener(OnCommitListener onCommitListener){
        mOnCommitListener = onCommitListener;
    }

    public void setOnSearchStateChangeListener(OnSearchStateChangeListener onSearchStateChangeListener){
        mOnSearchStateChangeListener = onSearchStateChangeListener;
    }

    public void setOnKeyPreImeListener(OnKeyPreImeListener onKeyPreImeListener){
        mOnKeyPreImeListener = onKeyPreImeListener;
    }


    public void enableSearchMode(boolean value){
        mEnableSearchMode = value;
    }

    public void resetLocation(){
        mLocationUri = "";
    }

    public void setLocation(String uri){
        if(!hasFocus()) {
            setText(uri, false);
        }
        mLocationUri = uri;
        Log.d(TAG, "setLocation: " + mLocationUri);
    }

    private boolean removeAutocomplete(Editable text) {

        if(text == null)
            return false;

        int start = text.getSpanStart(AUTOCOMPLETE_SPAN);

        if (start < 0) {
            // No autocomplete text
            return false;
        }

        beginSettingAutocomplete();

        // When we call delete() here, the autocomplete spans we set are removed as well.
        text.delete(start, text.length());

        // Keep autoCompletePrefixLength the same because the prefix has not changed.
        // Clear mAutoCompleteResult to make sure we get fresh autocomplete text next time.
        mAutoCompleteResult = null;

        // Reshow the cursor.
        setCursorVisible(true);

        endSettingAutocomplete();
        return true;
    }


    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        if(mOnWindowsFocusChangeListener != null) mOnWindowsFocusChangeListener.onWindowsFocusChanged(hasWindowFocus);
        super.onWindowFocusChanged(hasWindowFocus);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mOnKeyPreImeListener == null) { mOnKeyPreImeListener = KeyPreImeListener; }
        if (mOnSelectionChangedListener == null) { mOnSelectionChangedListener = SelectionChangedListener; }


        setOnKeyListener(KeyListener);
        addTextChangedListener(TextChangeListener);
    }


    @Override
    public InputConnection onCreateInputConnection(@NonNull EditorInfo outAttrs) {
        return new MyInputConnection(super.onCreateInputConnection(outAttrs),
                true);
    }


    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        if(mOnSelectionChangedListener != null) mOnSelectionChangedListener.onSelectionChanged(selStart, selEnd);
        super.onSelectionChanged(selStart, selEnd);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);

        Log.d(TAG, "onFocusChanged: " + focused + " originalText: " + getOriginalText() + " mLocationUri: " + mLocationUri);

        if(focused){
            setMovementMethod(ArrowKeyMovementMethod.getInstance());
        } else {
            // Disable horizontal scrolling and snap back to the start
            setMovementMethod(null);
        }

        if(mEnableSearchMode) {
            if (mOnFocusChangeListener != null) mOnFocusChangeListener.onFocusChanged(focused);
            return;
        }

        if(!focused){
            String text = getOriginalText();
            if(StringUtils.compare(mLocationUri, text, true) != 0)
                setText(mLocationUri, false);
        }


        if(mOnFocusChangeListener != null)
            mOnFocusChangeListener.onFocusChanged(focused);

        // Make search icon inactive when edit toolbar search term isn't a user entered
        // search term
        boolean isActive = !TextUtils.isEmpty(getText());

        if(mOnSearchStateChangeListener != null)
            mOnSearchStateChangeListener.onSearchStateChanged(isActive);


        if (focused) {
            resetAutocompleteState();
            return;
        }

        removeAutocomplete(getText());

        try {
            restartInput();
            if(mInputMethodManager != null)
                mInputMethodManager.hideSoftInputFromWindow(getWindowToken(), 0);
        } catch (NullPointerException e) {
            Log.e(TAG, "onFocusChanged", e);
            // See bug 782096 for details
        }
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        String textString = text != null ? text.toString() : "";
        super.setText(textString, type);

        // Any autocomplete text would have been overwritten, so reset our autocomplete states.
        resetAutocompleteState();
    }

    public void reset(){
        if(hasFocus()) return;
        if (mOnFocusChangeListener != null) mOnFocusChangeListener.onFocusChanged(false);

        setText(mLocationUri, false);

        // Make search icon inactive when edit toolbar search term isn't a user entered
        // search term
        boolean isActive = !TextUtils.isEmpty(getText());

        if(mOnSearchStateChangeListener != null)
            mOnSearchStateChangeListener.onSearchStateChanged(isActive);

        removeAutocomplete(getText());

        try {
            restartInput();
            if(mInputMethodManager != null)
                mInputMethodManager.hideSoftInputFromWindow(getWindowToken(), 0);
        } catch (NullPointerException e) {
            Log.e(TAG, "onFocusChanged", e);
            // See bug 782096 for details
        }
    }

    private String getOriginalText(){
        try{
            Editable editable = getText();
            if(editable != null){
                return editable.toString().subSequence(0, Math.min(mAutoCompletePrefixLength, editable.length())).toString();
            }
        }catch(StringIndexOutOfBoundsException e){
            Log.e(TAG, "getOriginalText",e);
        }
        return null;
    }


    private void beginSettingAutocomplete() {
        beginBatchEdit();
        mSettingAutoComplete = true;
    }

    /**
     * Mark the end of autocomplete changes
     */
    private void endSettingAutocomplete() {
        mSettingAutoComplete = false;
        endBatchEdit();
    }

    private void resetAutocompleteState() {

        mAutoCompleteSpans = new ArrayList<>();

        mAutoCompleteSpans.add(AUTOCOMPLETE_SPAN);
        mAutoCompleteSpans.add(mBackgroundSpanColor);


        mAutoCompleteResult = null;
        // Pretend we already autocompleted the existing text,
        // so that actions like backspacing don't trigger autocompletion.
        mAutoCompletePrefixLength = getText() != null ? getText().length() : 0;
        setCursorVisible(true);
    }

    public void noAutocompleteResult() {
        removeAutocomplete(getText());
    }


    /**
     * Applies the provided result by updating the current autocomplete
     * text and selection, if any.
     *
     * @param result the [AutocompleteResult] to apply
     */
    public void applyAutocompleteResult(String result) {
        // If discardAutoCompleteResult is true, we temporarily disabled
        // autocomplete (due to backspacing, etc.) and we should bail early.
        if (mDiscardAutoCompleteResult || mEnableSearchMode) {
            return;
        }

        if (!isEnabled()) {
            mAutoCompleteResult = null;
            return;
        }

        Editable text = getText();

        if(text == null) {
            mAutoCompleteResult = null;
            return;
        }

        int autoCompleteStart = text.getSpanStart(AUTOCOMPLETE_SPAN);

        mAutoCompleteResult = result;

        if (autoCompleteStart > -1) {
            // Autocomplete text already exists; we should replace existing autocomplete text.
            replaceAutocompleteText(result, autoCompleteStart);
        } else {
            // No autocomplete text yet; we should add autocomplete text
            addAutocompleteText(result);
        }

        announceForAccessibility(text.toString());
    }


    private void replaceAutocompleteText(String result, int autoCompleteStart) {
        // Autocomplete text already exists; we should replace existing autocomplete text.

        Editable text = getText();

        if(text == null)
            return;

        int resultLength = result.length();

        // If the result and the current text don't have the same prefixes,
        // the result is stale and we should wait for the another result to come in.
        if (!TextUtils.regionMatches(result, 0, text, 0, autoCompleteStart)) {
            return;
        }

        beginSettingAutocomplete();

        // Replace the existing autocomplete text with new one.
        // replace() preserves the autocomplete spans that we set before.
        text.replace(autoCompleteStart, text.length(), result, autoCompleteStart, resultLength);

        // Reshow the cursor if there is no longer any autocomplete text.
        if (autoCompleteStart == resultLength) {
            setCursorVisible(true);
        }

        endSettingAutocomplete();
    }

    private void addAutocompleteText(String result) {
        // No autocomplete text yet; we should add autocomplete text
        Editable text = getText();

        if(text == null)
            return;

        int textLength = text.length();
        int resultLength = result.length();

        // If the result prefix doesn't match the current text,
        // the result is stale and we should wait for the another result to come in.
        if (resultLength <= textLength || !TextUtils.regionMatches(result, 0, text, 0, textLength)) {
            return;
        }

        Object[] spans = text.getSpans(textLength, textLength, Object.class);
        int[] spanStarts = new int[spans.length];
        int[] spanEnds = new int[spans.length];
        int[] spanFlags = new int[spans.length];

        // Save selection/composing span bounds so we can restore them later.
        for (int i = 0; i < spans.length; i++) {
            Object span = spans[i];
            int spanFlag = text.getSpanFlags(span);

            // We don't care about spans that are not selection or composing spans.
            // For those spans, spanFlag[i] will be 0 and we don't restore them.

            if ((spanFlag & Spanned.SPAN_COMPOSING) != 0 || span == Selection.SELECTION_START || span == Selection.SELECTION_END) {
                spanStarts[i] = text.getSpanStart(span);
                spanEnds[i] = text.getSpanEnd(span);
                spanFlags[i] = spanFlag;
            }

        }

        beginSettingAutocomplete();

        // First add trailing text.
        text.append(result, textLength, resultLength);

        // Restore selection/composing spans.
        for (int i = 0; i < spans.length; i++) {
            int spanFlag = spanFlags[i];
            if (spanFlag != 0) {
                text.setSpan(spans[i], spanStarts[i], spanEnds[i], spanFlag);
            }
        }

        // Mark added text as autocomplete text.
        if(mAutoCompleteSpans != null){
            for(Object span : mAutoCompleteSpans)
                text.setSpan(span, textLength, resultLength, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }


        // Hide the cursor.
        setCursorVisible(false);

        // Make sure the autocomplete text is visible. If the autocomplete text is too
        // long, it would appear the cursor will be scrolled out of view. However, this
        // is not the case in practice, because EditText still makes sure the cursor is
        // still in view.
        bringPointIntoView(resultLength);

        endSettingAutocomplete();
    }

    /**
     * Convert any autocomplete text to regular text
     *
     * @param text Current text content that may include autocomplete text
     */
    private boolean commitAutocomplete(Editable text) {
        int start = text.getSpanStart(AUTOCOMPLETE_SPAN);
        if (start < 0) {
            // No autocomplete text
            return false;
        }

        beginSettingAutocomplete();

        // Remove all spans here to convert from autocomplete text to regular text
        if(mAutoCompleteSpans != null) {
            for (Object span : mAutoCompleteSpans)
                text.removeSpan(span);
        }

        // Keep mAutoCompleteResult the same because the result has not changed.
        // Reset autoCompletePrefixLength because the prefix now includes the autocomplete text.
        mAutoCompletePrefixLength = text.length();

        // Reshow the cursor.
        setCursorVisible(true);

        endSettingAutocomplete();

        // Invoke textChangeListener manually, because previous autocomplete text is now committed
        if(mOnTextChangedListener != null){
            String fullText = text.toString();
            mOnTextChangedListener.onTextChanged(fullText, fullText);
        }

        return true;
    }


    /**
     * Sets the text of the edit text.
     * @param text The text to set.
     * @param shouldAutoComplete If false, [TextChangeListener] the text watcher will be disabled for this set.
     */
    public void setText(CharSequence text, boolean shouldAutoComplete) {
        boolean wasSettingAutoComplete = mSettingAutoComplete;

        // Disable listeners in order to stop auto completion
        mSettingAutoComplete = !shouldAutoComplete;
        setText(text, BufferType.EDITABLE);
        mSettingAutoComplete = wasSettingAutoComplete;
    }


    /**
     * Appends the given text to the end of the current text.
     * @param text The text to append.
     * @param shouldAutoComplete If false, [TextChangeListener] text watcher will be disabled for this append.
     */
    private void appendText(CharSequence text, boolean shouldAutoComplete) {
        boolean wasSettingAutoComplete = mSettingAutoComplete;

        // Disable listeners in order to stop auto completion
        mSettingAutoComplete = !shouldAutoComplete;
        append(text);
        mSettingAutoComplete = wasSettingAutoComplete;
    }


    @Override
    public void sendAccessibilityEventUnchecked(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED &&
                getParent() != null && !isShown()
        ) {
            onInitializeAccessibilityEvent(event);
            dispatchPopulateAccessibilityEvent(event);
            getParent().requestSendAccessibilityEvent(this, event);
        } else {
            super.sendAccessibilityEventUnchecked(event);
        }
        super.sendAccessibilityEventUnchecked(event);
    }


    private final OnKeyPreImeListener KeyPreImeListener = new OnKeyPreImeListener() {

        private boolean hasCompositionString(Editable content) {
            Object[] spans = content.getSpans(0, content.length(), Object.class);
            for(Object span : spans){
                int flags = content.getSpanFlags(span);
                if((flags & Spanned.SPAN_COMPOSING) != 0){
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            // We only want to process one event per tap
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }

            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                // If the edit text has a composition string, don't submit the text yet.
                // ENTER is needed to commit the composition string.
                Editable content = getText();

                if(content == null)
                    return false;

                if (!hasCompositionString(content)) {
                    if(mOnCommitListener != null)
                        mOnCommitListener.onCommit();
                    return true;
                }
            }

            if (keyCode == KeyEvent.KEYCODE_BACK) {

                Editable content = getText();

                if(content == null)
                    return false;

                removeAutocomplete(content);

                clearFocus();

                return false;
            }

            return false;
        }
    };

    private final OnKeyListener KeyListener = new OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return true;
                }

                if(mOnCommitListener != null)
                    mOnCommitListener.onCommit();

                return true;
            }

            // Delete autocomplete text when backspacing or forward deleting.
            return (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) && removeAutocomplete(getText());
        }
    };

    private final OnSelectionChangedListener SelectionChangedListener = new OnSelectionChangedListener() {
        @Override
        public void onSelectionChanged(int selStart, int selEnd) {
            Editable text = getText();

            if(text == null)
                return;

            int start = text.getSpanStart(AUTOCOMPLETE_SPAN);

            boolean nothingSelected = start == selStart && start == selEnd;

            if (mSettingAutoComplete || nothingSelected || start < 0) {
                // Do not commit autocomplete text if there is no autocomplete text
                // or if selection is still at start of autocomplete text
                return;
            }

            if (selStart <= start && selEnd <= start) {
                // The cursor is in user-typed text; remove any autocomplete text.
                removeAutocomplete(text);
            } else {
                // The cursor is in the autocomplete text; commit it so it becomes regular text.
                commitAutocomplete(text);
            }
        }
    };

    private final TextWatcher TextChangeListener = new TextWatcher() {

        /**
         * Holds the value of the non-autocomplete text before any changes have been made.
         * */
        private String mBeforeChangedTextNonAutocomplete = "";

        /**
         * The number of characters that have been changed in [onTextChanged].
         * When using keyboards that do not have their own text correction enabled
         * and the user is pressing backspace this value will be 0.
         * */
        private int mTextChangedCount = 0;


        private String getNonAutocompleteText(Editable text) {
            if(text == null)
                return "";
            int start = text.getSpanStart(AUTOCOMPLETE_SPAN);
            if (start < 0) {
                // No autocomplete text; return the whole string.
                return text.toString();
            } else {
                // Only return the portion that's not autocomplete text
                return TextUtils.substring(text, 0, start);
            }
        }


        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            if (!isEnabled() || mSettingAutoComplete) return ;

            mBeforeChangedTextNonAutocomplete = getNonAutocompleteText(getText());
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (mSettingAutoComplete) return;

            if (!isEnabled()) return;

            mTextChangedCount = count;
        }

        @Override
        public void afterTextChanged(Editable s) {

            if(!isEnabled() || mSettingAutoComplete) return;

            String mAfterNonAutocompleteText = getNonAutocompleteText(s);

            boolean hasTextShortenedByOne =
                    mBeforeChangedTextNonAutocomplete.length() == mAfterNonAutocompleteText.length() + 1;

            // Covers both keyboards with text correction activated and those without.
            boolean hasBackspaceBeenPressed =
                    mTextChangedCount == 0 || hasTextShortenedByOne;

            boolean afterTextIsSearch = mAfterNonAutocompleteText.contains(" ");

            boolean hasTextBeenAdded =
                    (
                            mAfterNonAutocompleteText.contains(mBeforeChangedTextNonAutocomplete) ||
                                    TextUtils.isEmpty(mBeforeChangedTextNonAutocomplete)
                    ) &&
                            mAfterNonAutocompleteText.length() > mBeforeChangedTextNonAutocomplete.length();

            boolean shouldAddAutocomplete = hasTextBeenAdded || (!afterTextIsSearch && !hasBackspaceBeenPressed);

            mAutoCompletePrefixLength = mAfterNonAutocompleteText.length();

            mDiscardAutoCompleteResult = !shouldAddAutocomplete;

            if (!shouldAddAutocomplete) {
                // Remove the old autocomplete text until any new autocomplete text gets added.
                removeAutocomplete(s);
            } else {
                // If this text already matches our autocomplete text, autocomplete likely
                // won't change. Just reuse the old autocomplete value.
                if(!TextUtils.isEmpty(mAutoCompleteResult) && mAutoCompleteResult.startsWith(mAfterNonAutocompleteText)){
                    applyAutocompleteResult(mAutoCompleteResult);
                    shouldAddAutocomplete = false;
                }
            }

            // Update search icon with an active state since user is typing
            if(mOnSearchStateChangeListener != null)
                mOnSearchStateChangeListener.onSearchStateChanged(!TextUtils.isEmpty(mAfterNonAutocompleteText));

            if (shouldAddAutocomplete) {
                if(mOnFilterListener != null)
                    mOnFilterListener.onRefreshAutoComplete(getOriginalText());
            }

            if(mOnTextChangedListener != null) {
                mOnTextChangedListener.onTextChanged(mAfterNonAutocompleteText, getText() != null ? getText().toString() : "");
            }

            Log.d(TAG, "hasBackspaceBeenPressed: " + hasBackspaceBeenPressed + " before: " + mBeforeChangedTextNonAutocomplete + " after: " + mAfterNonAutocompleteText + " hasTextShortenedByOne: " + hasTextShortenedByOne);

        }
    };

    private class MyInputConnection extends InputConnectionWrapper {

        private final static String INPUT_METHOD_AMAZON_ECHO_SHOW = "com.amazon.bluestone.keyboard/.DictationIME";

        private final static String INPUT_METHOD_SONY = "com.sonyericsson.textinput.uxp/.glue.InputMethodServiceGlue";

        public MyInputConnection(InputConnection target, boolean mutable) {
            super(target, mutable);
        }

        private boolean isAmazonEchoShowKeyboard(){
            return INPUT_METHOD_AMAZON_ECHO_SHOW.equals(getCurrentInputMethod(getContext()));
        }

        private boolean isSonyKeyboard(){
            return INPUT_METHOD_SONY.equals(getCurrentInputMethod(getContext()));
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {

            Editable editable = getText();

            if(editable == null)
                return false;

            if (removeAutocomplete(editable)) {
                // If we have autocomplete text, the cursor is at the boundary between
                // regular and autocomplete text. So regardless of which direction we
                // are deleting, we should delete the autocomplete text first.
                //
                // On Amazon Echo Show devices, restarting input prevents us from backspacing
                // the last few characters of autocomplete: #911. However, on non-Echo devices,
                // not restarting input will cause the keyboard to desync when backspacing: #1489.
                if (!isAmazonEchoShowKeyboard()) {
                    restartInput();
                }
                return false;
            }
            return super.deleteSurroundingText(beforeLength, afterLength);
        }

        private boolean removeAutocompleteOnComposing(CharSequence text) {
            Editable editable = getText();

            if(editable == null)
                return false;

            // Remove the autocomplete text as soon as possible if not applicable anymore.
            if (!getEditableText().toString().startsWith(text.toString()) && removeAutocomplete(editable)) {
                return false; // If the user modified their input then allow the new text to be set.
            }

            int composingStart = BaseInputConnection.getComposingSpanStart(editable);
            int composingEnd = BaseInputConnection.getComposingSpanEnd(editable);
            // We only delete the autocomplete text when the user is backspacing,
            // i.e. when the composing text is getting shorter.
            if (composingStart >= 0 &&
                    composingEnd >= 0 &&
                    composingEnd - composingStart > text.length() &&
                    removeAutocomplete(editable)
            ) {
                // Make the IME aware that we interrupted the setComposingText call,
                // by having finishComposingText() send change notifications to the IME.
                finishComposingText();
                return true;
            }
            return false;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition){
            if (removeAutocompleteOnComposing(text)){
                return false;
            }
            else{
                return super.commitText(text, newCursorPosition);
            }
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            if (removeAutocompleteOnComposing(text)) {
                if (isSonyKeyboard()) {
                    restartInput();
                }
                return false;
            } else {
                return super.setComposingText(text, newCursorPosition);
            }
        }

    }


}
