package com.solarized.firedown.geckoview.toolbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.solarized.firedown.R;

public class BottomProgressView extends FrameLayout {

    private TextView mTitle;

    private MaterialButton mActionButton;

    private LinearProgressIndicator mProgressIndicator;

    /** Told how much of the screen above the navigation bar the bar covers:
     *  its height without the inset it pads itself by, 0 while it is gone. */
    public interface OnCoverChangedListener {
        void onCoverChanged(int height);
    }

    private OnCoverChangedListener mCoverListener;

    private int mReportedCover = -1;

    public BottomProgressView(Context context) {
        super(context);
        init(context);
    }

    public BottomProgressView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public BottomProgressView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    public BottomProgressView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context){

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        View v = inflater.inflate(R.layout.fragment_bottom_progress, this, true);

        mTitle = v.findViewById(R.id.title);

        mActionButton = v.findViewById(R.id.single_action_button);

        mProgressIndicator = v.findViewById(R.id.progress_bar);

        applyWindowInsets();

    }

    public void setOnCoverChangedListener(@Nullable OnCoverChangedListener listener) {
        mCoverListener = listener;
        mReportedCover = -1;
        reportCover();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this) {
            reportCover();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        reportCover();
    }

    private void reportCover() {
        OnCoverChangedListener listener = mCoverListener;
        if (listener == null) {
            return;
        }
        int cover = getVisibility() == VISIBLE ? Math.max(0, getHeight() - getPaddingBottom()) : 0;
        if (cover != mReportedCover) {
            mReportedCover = cover;
            listener.onCoverChanged(cover);
        }
    }

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                    WindowInsetsCompat.Type.displayCutout());
            // Apply the insets as padding to the view. Here, set all the dimensions
            // as appropriate to your layout. You can also update the view's margin if
            // more appropriate.
            v.setPadding(insets.left, 0, insets.right, insets.bottom);

            // Return CONSUMED if you don't want the window insets to keep passing down
            // to descendant views.
            return WindowInsetsCompat.CONSUMED;
        });
    }


    public void setTitle(int resId){
        mTitle.setText(resId);
    }

    public void setTitle(String s){
        mTitle.setText(s);
    }

    public void setActionButtonText(int resId){
        mActionButton.setText(resId);
    }

    public void setActionButtonVisibility(int visibility){
        mActionButton.setVisibility(visibility);
    }

    public void setActionButtonListener(OnClickListener onClickListener){
        mActionButton.setOnClickListener(onClickListener);
    }

    public void setProgress(int progress){
        mProgressIndicator.setProgress(progress);
    }

    /** Switch the bar between determinate (task progress, the default) and
     *  indeterminate (a spinner for work with no progress signal — the SAF
     *  restore). Material throws if switched to indeterminate while the
     *  indicator is shown, so callers must flip this while the view is GONE. */
    public void setIndeterminate(boolean indeterminate){
        mProgressIndicator.setIndeterminate(indeterminate);
    }
}
