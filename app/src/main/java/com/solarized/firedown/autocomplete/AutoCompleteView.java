package com.solarized.firedown.autocomplete;

import static android.content.Context.CLIPBOARD_SERVICE;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
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
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.solarized.firedown.R;
import com.solarized.firedown.ui.IncognitoColors;

public class AutoCompleteView extends FrameLayout {

    private static final String TAG = AutoCompleteView.class.getSimpleName();

    private final Context mContext;

    private AutoCompleteRecyclerView mSearchView;

    private MaterialButton mVisibilityView;

    private TextView mClipboardTextView;

    private View mClipboardView;

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

        mVisibilityView = v.findViewById(R.id.clipboard_button);

        mVisibilityView.setOnClickListener(v1 -> {
            int visibility = mClipboardTextView.getVisibility();
            if(visibility == View.VISIBLE){
                mVisibilityView.setIconResource(R.drawable.ic_visibility_20);
                mClipboardTextView.setVisibility(View.GONE);
            }else{
                mVisibilityView.setIconResource(R.drawable.ic_visibility_off_20);
                mClipboardTextView.setVisibility(View.VISIBLE);
            }
        });

        mSearchView = v.findViewById(R.id.search_view);

        mClipboardView = v.findViewById(R.id.clipboard_view);

        //Avoid blinking
        mSearchView.setItemAnimator(null);

        mClipboardView.setOnClickListener(v2 -> {
            if(mCallback != null)
                mCallback.onClipboardClick(mClipboardTextView.getText());
        });

        mClipboardView.setOnLongClickListener(view -> {
            if(mCallback != null){
                mCallback.onClipboardLongClick(mClipboardTextView.getText());
                return true;
            }
            return false;
        });

    }


    public void updateTheme(Activity activity, boolean incognito) {

        int surfaceColor = IncognitoColors.getSurface(activity, incognito);
        int surfaceContainerHighest = IncognitoColors.getSurfaceContainerHighest(activity, incognito);
        int onSurfaceColor = IncognitoColors.getOnSurface(activity, incognito);
        int onSurfaceVariant = IncognitoColors.getOnSurfaceVariant(activity, incognito);

        // 1. Root LinearLayout background
        View root = getChildAt(0);
        if (root != null) {
            root.setBackgroundColor(surfaceColor);
        }

        // 2. Clipboard card background
        if (mClipboardView instanceof com.google.android.material.card.MaterialCardView card) {
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

        // 5. Clipboard icon
        ImageView clipboardImage = findViewById(R.id.clipboard_image);
        if (clipboardImage != null) {
            ImageViewCompat.setImageTintList(clipboardImage,
                    ColorStateList.valueOf(onSurfaceVariant));
        }

        // 6. Visibility toggle button
        if (mVisibilityView != null) {
            mVisibilityView.setIconTint(ColorStateList.valueOf(onSurfaceVariant));
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
    }

    public void hideAll(){
        hideClipboard();
        mSearchView.setVisibility(View.VISIBLE);
    }

    public void updateVisibility(boolean hasFocus){
        setVisibility(hasFocus ? View.VISIBLE : View.GONE);
        mVisibilityView.setIconResource(R.drawable.ic_visibility_20);
        mClipboardTextView.setVisibility(View.GONE);
    }

    public void showClipboard(){

        ClipboardManager clipboardManager =  (ClipboardManager) mContext.getSystemService(CLIPBOARD_SERVICE);

        if (clipboardManager.getPrimaryClip() != null &&
                clipboardManager.getPrimaryClip().getItemCount() > 0 &&
                clipboardManager.getPrimaryClip().getDescription() != null &&
                (clipboardManager.getPrimaryClip().getDescription().getMimeType(0).equals(ClipDescription.MIMETYPE_TEXT_HTML) ||
                clipboardManager.getPrimaryClip().getDescription().getMimeType(0).equals(ClipDescription.MIMETYPE_TEXT_PLAIN))) {
            ClipData data = clipboardManager.getPrimaryClip();
            ClipDescription clipDescription = data.getDescription();
            Log.d(TAG, "showClipboard desc: " + clipDescription.getMimeType(0));
            CharSequence clipText = data.getItemAt(0).coerceToText(mContext);
            mClipboardTextView.setText(clipText);
            if (mClipboardView.getVisibility() == View.GONE) {
                mClipboardView.setVisibility(View.VISIBLE);
            }
        } else {
            mClipboardView.setVisibility(View.GONE);
        }
    }

    public void hideClipboard(){
        mClipboardView.setVisibility(View.GONE);
    }


}
