package com.solarized.firedown.phone.dialogs;

import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.OptionItem;
import com.solarized.firedown.data.entity.ContextElementEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.ui.HorizontalDividerItemDecoration;
import com.solarized.firedown.ui.adapters.OptionsAdapter;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.FragmentArgs;
import com.solarized.firedown.utils.NavigationUtils;

import org.mozilla.geckoview.GeckoSession;

import java.util.ArrayList;
import java.util.List;

public class BrowserContentDialogFragment extends BaseDialogFragment
        implements OptionsAdapter.OnItemClickListener {

    private ContextElementEntity mContextElementEntity;
    private BrowserDialogViewModel mBrowserDialogViewModel;

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mContextElementEntity = null;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContextElementEntity = FragmentArgs.parcelable(this, Keys.ITEM_ID, ContextElementEntity.class);
        mBrowserDialogViewModel = new ViewModelProvider(mActivity)
                .get(BrowserDialogViewModel.class);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dialog_content, container, false);

        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        }

        // Title
        TextView title = view.findViewById(R.id.title);
        int type = mContextElementEntity.getType();
        if (type == GeckoSession.ContentDelegate.ContextElement.TYPE_NONE) {
            title.setText(mContextElementEntity.getLinkUri());
        } else if (type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE) {
            title.setText(mContextElementEntity.getSrcUri());
        } else {
            if (!TextUtils.isEmpty(mContextElementEntity.getSrcUri()))
                title.setText(mContextElementEntity.getSrcUri());
            else if (!TextUtils.isEmpty(mContextElementEntity.getLinkUri()))
                title.setText(mContextElementEntity.getLinkUri());
            else
                title.setText(mContextElementEntity.getBaseUri());
        }

        // Image elements get a thumbnail beside the URL. Fetched like the
        // page would fetch it — a Referer of the page's ORIGIN plus an
        // image Accept — so a hotlink-gated CDN (pixiv's i.pximg.net, the
        // save-image lesson) serves it instead of 403'ing a bare request;
        // the same header shape GlideHelper's favicon loader sends. A
        // failed load just leaves the slot hidden (no glyph: the title
        // already names the element).
        AppCompatImageView thumbnail = view.findViewById(R.id.thumbnail);
        String srcUri = mContextElementEntity.getSrcUri();
        if (type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE
                && !TextUtils.isEmpty(srcUri)) {
            thumbnail.setVisibility(View.VISIBLE);
            thumbnail.setClipToOutline(true);
            LazyHeaders.Builder headers = new LazyHeaders.Builder()
                    .addHeader(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString())
                    .addHeader(BrowserHeaders.ACCEPT,
                            "image/avif,image/webp,image/png,image/svg+xml,image/*,*/*;q=0.8");
            String referer = BrowserHeaders.originWithSlash(mContextElementEntity.getBaseUri());
            if (referer != null) {
                headers.addHeader(BrowserHeaders.REFERER, referer);
            }
            Object model = srcUri.startsWith("data:") ? srcUri : new GlideUrl(srcUri, headers.build());
            Glide.with(this).load(model).into(thumbnail);
        }

        // Build items — no final item, uniform layout
        List<OptionItem> optionItemList = buildOptionItems(type);

        OptionsAdapter optionsAdapter = new OptionsAdapter(
                optionItemList, this, R.layout.fragment_dialog_content_item);

        RecyclerView recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setAdapter(optionsAdapter);

        // Divider between link-only items and image+link items
        int dividerPosition = getResources().getStringArray(R.array.context_link).length;
        if (optionItemList.size() > dividerPosition) {
            recyclerView.addItemDecoration(new HorizontalDividerItemDecoration(
                    mActivity, HorizontalDividerItemDecoration.VERTICAL, dividerPosition - 1));
        }

        return view;
    }

    @Override
    public void onItemClick(int position, OptionItem item) {
        if (position == RecyclerView.NO_POSITION)
            return;
        mBrowserDialogViewModel.onEventSelected(mContextElementEntity, item.getLabelRes());
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_browser_content);
    }

    private List<OptionItem> buildOptionItems(int type) {
        boolean isImage = type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE;

        String[] labels = getResources().getStringArray(
                isImage ? R.array.context_image : R.array.context_link);

        TypedArray typedArray = getResources().obtainTypedArray(isImage ?
                R.array.context_image : R.array.context_link);
        // Index-paired glyph arrays (see arrays.xml). The string id stays
        // the dispatch key (BrowserFragment switches on getLabelRes()); the
        // glyph is presentation only, so it rides iconRes.
        TypedArray icons = getResources().obtainTypedArray(isImage ?
                R.array.context_image_icon : R.array.context_link_icon);

        List<OptionItem> items = new ArrayList<>(labels.length);

        try{

            for(int i = 0; i < labels.length; i++){
                int iconRes = i < icons.length() ? icons.getResourceId(i, 0) : 0;
                items.add(new OptionItem(labels[i], iconRes, typedArray.getResourceId(i, 0)));
            }
        } finally {
            typedArray.recycle();
            icons.recycle();
        }

        return items;
    }
}