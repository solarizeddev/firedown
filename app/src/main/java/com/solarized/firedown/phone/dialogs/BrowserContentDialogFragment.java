package com.solarized.firedown.phone.dialogs;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestOptions;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.OptionItem;
import com.solarized.firedown.data.entity.ContextElementEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.glide.MimeTypeThumbnail;
import com.solarized.firedown.ui.adapters.OptionsAdapter;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.FileUriHelper;
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

    /**
     * The dialog's width: 90% of the screen, capped at max_dialog_width —
     * the rule RenameFileDialog / SaveFileDialog already set, applied in
     * onResume like them (the window exists by then; onCreateView is too
     * early for setLayout). The window is transparent and the root paints
     * the surface, so without an explicit width the dialog is as wide as
     * its widest child: the old rows declared a 500dp width that pushed it
     * EDGE TO EDGE on every phone (issue #306's follow-up: "center this
     * menu so it doesn't touch the edges"), and the card rows are
     * match_parent, which would make it as narrow as the longest label.
     */
    @Override
    public void onResume() {
        super.onResume();
        if (getDialog() == null || getDialog().getWindow() == null) {
            return;
        }
        Window window = getDialog().getWindow();
        Resources resources = getResources();
        int width = Math.min((int) (resources.getDisplayMetrics().widthPixels * 0.90),
                resources.getDimensionPixelOffset(R.dimen.max_dialog_width));
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
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
        } else if (type == GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO
                && !TextUtils.isEmpty(srcUri) && srcUri.startsWith("http")) {
            // A <video> with a direct http(s) src gets a FRAME, decoded the
            // way the Captured sheet decodes a poster-less capture: the Uri
            // model routes to FFmpegUriDecoder, which reads its request
            // headers from GlideRequestOptions.HEADERS (the page-origin
            // Referer, same hotlink rule as the image above) and auto-seeks
            // past the black opening frame. The mime glyph holds the slot
            // while the demux runs and stays if it fails. A blob: src (an
            // MSE player) has no fetchable bytes and keeps the slot hidden.
            thumbnail.setVisibility(View.VISIBLE);
            thumbnail.setClipToOutline(true);
            RequestOptions frameOptions = new RequestOptions()
                    .set(GlideRequestOptions.HEADERS,
                            BrowserHeaders.refererOriginHeaders(mContextElementEntity.getBaseUri()))
                    .placeholder(MimeTypeThumbnail.generateDrawable(mActivity, FileUriHelper.MIMETYPE_MP4, true))
                    .error(MimeTypeThumbnail.generateDrawable(mActivity, FileUriHelper.MIMETYPE_MP4, true));
            Glide.with(this).load(Uri.parse(srcUri))
                    .apply(frameOptions)
                    .centerCrop()
                    .into(thumbnail);
        }

        // The rows: grouped sheet cards (fragment_dialog_content_item), no
        // final item. On a linked image the link verbs and the image verbs
        // are two groups, split by the separator item buildOptionItems
        // inserts — OptionsAdapter renders it as the 12dp group gap and
        // closes one corner group / opens the next at it (the Downloads
        // media-tools sub-sheet's shape). It replaced a hairline
        // ItemDecoration at the same position: a rule between cards is the
        // one thing the grouped design never draws.
        List<OptionItem> optionItemList = buildOptionItems(type);

        OptionsAdapter optionsAdapter = new OptionsAdapter(
                optionItemList, this, R.layout.fragment_dialog_content_item);

        RecyclerView recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setAdapter(optionsAdapter);

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

        // context_image repeats context_link's rows first, then adds the
        // image rows; the group break goes between the two halves. A
        // separator is never clickable (OptionsAdapter gives it a holder
        // with no listener), and onItemClick dispatches on the ITEM's
        // label id, not the position, so the extra row shifts nothing.
        int linkCount = getResources().getStringArray(R.array.context_link).length;

        List<OptionItem> items = new ArrayList<>(labels.length + 1);

        try{

            for(int i = 0; i < labels.length; i++){
                if (isImage && i == linkCount) {
                    items.add(OptionItem.separator());
                }
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