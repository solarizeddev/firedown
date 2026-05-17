package com.solarized.firedown.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;

public class HomeViewpager extends FrameLayout {

    private RecyclerView mRecyclerView;
    private View mShortcutsCard;
    private View mEmptyState;
    private View mEmptyStateAddButton;

    public HomeViewpager(@NonNull Context context) {
        super(context);
        init(context);
    }

    public HomeViewpager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public HomeViewpager(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public HomeViewpager(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    private void init(Context context){
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        inflater.inflate(R.layout.fragment_home_viewpager, this, true);

        mRecyclerView = findViewById(R.id.recycler_view);
        mShortcutsCard = findViewById(R.id.recycler_view_holder);
        mEmptyState = findViewById(R.id.empty_state);
        mEmptyStateAddButton = findViewById(R.id.empty_state_add_button);

        applyWindowInsets();
    }


    public RecyclerView getRecyclerView() {
        return mRecyclerView;
    }

    /**
     * Toggle between the empty-state placeholder (Firedown glyph +
     * tagline + Add CTA) and the populated shortcuts grid card.
     * Caller drives this off ShortCutsViewModel's LiveData so the
     * surface flips automatically the moment the user adds or
     * removes a shortcut.
     */
    public void showEmptyState(boolean empty) {
        mEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        mShortcutsCard.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** Click listener for the empty-state 'Add a shortcut' button —
     *  HomeFragment routes this through its isFull() check and the
     *  edit-dialog 'add mode'. */
    public void setOnEmptyStateAddClick(@Nullable OnClickListener listener) {
        mEmptyStateAddButton.setOnClickListener(listener);
    }

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.systemBars());
            // Apply the insets as padding to the view. Here, set all the dimensions
            // as appropriate to your layout. You can also update the view's margin if
            // more appropriate.
            v.setPadding(insets.left, 0, insets.right, 0);

            // Return CONSUMED if you don't want the window insets to keep passing down
            // to descendant views.
            return WindowInsetsCompat.CONSUMED;
        });
    }
}
