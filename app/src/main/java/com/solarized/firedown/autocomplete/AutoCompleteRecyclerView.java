package com.solarized.firedown.autocomplete;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

public class AutoCompleteRecyclerView extends RecyclerView {
    private RecyclerView.OnScrollListener onKeyboardDismissingScrollListener;
    private InputMethodManager inputMethodManager;

    public AutoCompleteRecyclerView(Context context) {
        this(context, null);
    }

    public AutoCompleteRecyclerView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, -1);
    }

    public AutoCompleteRecyclerView(final Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setOnKeyboardDismissingListener();
    }

    /**
     * Creates {@link OnScrollListener} that will dismiss keyboard when scrolling if the keyboard
     * has not been dismissed internally before
     */
    private void setOnKeyboardDismissingListener() {
        onKeyboardDismissingScrollListener = new RecyclerView.OnScrollListener() {
            boolean isKeyboardDismissedByScroll;

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int state) {
                switch (state) {
                    case RecyclerView.SCROLL_STATE_DRAGGING:
                        if (!isKeyboardDismissedByScroll) {
                            hideKeyboard();
                            isKeyboardDismissedByScroll = !isKeyboardDismissedByScroll;
                        }
                        break;
                    case RecyclerView.SCROLL_STATE_IDLE:
                        isKeyboardDismissedByScroll = false;
                        break;
                }
            }
        };
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        addOnScrollListener(onKeyboardDismissingScrollListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeOnScrollListener(onKeyboardDismissingScrollListener);
    }

    /**
     * Hides the keyboard
     */
    public void hideKeyboard() {
        getInputMethodManager().hideSoftInputFromWindow(getWindowToken(), 0);
        clearFocus();
    }

    /**
     * Returns an {@link InputMethodManager}
     *
     * @return input method manager
     */
    public InputMethodManager getInputMethodManager() {
        if (null == inputMethodManager) {
            inputMethodManager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        }

        return inputMethodManager;
    }
}