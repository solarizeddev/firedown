package com.solarized.firedown.phone.dialogs;

import android.content.DialogInterface;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.SitePermissions;
import com.solarized.firedown.utils.SheetGroups;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission;

import java.util.ArrayList;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The answers a site holds, opened from the security sheet's "Site
 * permissions" row: location, keeping its data on the phone, an embedded
 * site's cookies (SitePermissions decides which are listed). Each row has a
 * Reset, which forgets the answer so the site asks again; Reset all forgets
 * them all. The page is reloaded when the sheet closes after a Reset, so it
 * runs without the answer it had.
 */
@AndroidEntryPoint
public class SitePermissionsDialogFragment extends BaseBottomSheetDialogFragment {

    private static final String STATE_CHANGED = "site_permissions_changed";

    private String mUrl;
    private String mHost;
    private final List<SitePermissions.Row<ContentPermission>> mRows = new ArrayList<>();
    private boolean mChanged;

    /** "2 allowed", "1 blocked", "2 allowed · 1 blocked": the security sheet's row. */
    @NonNull
    public static String summary(@NonNull Resources res, @NonNull SitePermissions<?> permissions) {
        String allowed = res.getQuantityString(R.plurals.site_permissions_allowed,
                permissions.allowedCount, permissions.allowedCount);
        String blocked = res.getQuantityString(R.plurals.site_permissions_blocked,
                permissions.blockedCount, permissions.blockedCount);
        if (permissions.blockedCount == 0) {
            return allowed;
        }
        if (permissions.allowedCount == 0) {
            return blocked;
        }
        return allowed + " · " + blocked;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        mUrl = args != null ? args.getString(Keys.ITEM_ID) : null;
        mHost = SitePermissions.hostOf(mUrl);
        mChanged = savedInstanceState != null && savedInstanceState.getBoolean(STATE_CHANGED);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_CHANGED, mChanged);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;
        mView = themedInflater.inflate(R.layout.fragment_dialog_site_permissions, container, false);
        ((TextView) mView.findViewById(R.id.site_permissions_host)).setText(mHost);
        mView.findViewById(R.id.site_permissions_reset_all).setOnClickListener(v -> {
            List<ContentPermission> all = new ArrayList<>();
            for (SitePermissions.Row<ContentPermission> row : mRows) {
                all.addAll(row.handles);
            }
            reset(all, () -> {
                if (isAdded()) {
                    dismiss();
                }
            });
        });
        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mUrl == null) {
            dismiss();
            return;
        }
        load();
    }

    /** The answers as Gecko holds them now; the list is redrawn from them. */
    private void load() {
        mGeckoRuntimeHelper.loadSitePermissions(mUrl, mIsIncognito, permissions -> {
            if (mView == null || !isAdded()) {
                return;
            }
            mRows.clear();
            mRows.addAll(permissions.rows);
            render();
        });
    }

    private void render() {
        ViewGroup group = mView.findViewById(R.id.site_permissions_group);
        group.removeAllViews();
        if (mRows.isEmpty()) {
            dismiss();
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(group.getContext());
        for (SitePermissions.Row<ContentPermission> row : mRows) {
            View card = inflater.inflate(R.layout.item_site_permission, group, false);
            String title = titleOf(row);
            ((ImageView) card.findViewById(R.id.site_permission_icon)).setImageResource(iconOf(row));
            ((TextView) card.findViewById(R.id.site_permission_title)).setText(title);
            ((TextView) card.findViewById(R.id.site_permission_status)).setText(
                    row.allowed ? R.string.site_permission_allowed : R.string.site_permission_blocked);
            View reset = card.findViewById(R.id.site_permission_reset);
            reset.setContentDescription(getString(R.string.site_permission_reset_description, title));
            reset.setOnClickListener(v -> {
                v.setEnabled(false);
                reset(row.handles, this::load);
            });
            group.addView(card);
        }
        SheetGroups.applyCorners(group);
    }

    /**
     * Forgets these answers, then {@code then} once Gecko is done. The list
     * is read again afterwards rather than trimmed here: what Gecko holds
     * is what the list shows (GeckoRuntimeHelper.resetSitePermissions may
     * have to clear the site and write the other answers back).
     */
    private void reset(@NonNull List<ContentPermission> perms, @NonNull Runnable then) {
        if (perms.isEmpty()) {
            then.run();
            return;
        }
        mChanged = true;
        mGeckoRuntimeHelper.resetSitePermissions(mUrl, mIsIncognito, perms, () -> {
            if (mView != null && isAdded()) {
                then.run();
            }
        });
    }

    @NonNull
    private String titleOf(@NonNull SitePermissions.Row<?> row) {
        if (row.type == SitePermissions.TYPE_LOCATION) {
            return getString(R.string.site_permission_location);
        }
        if (row.type == SitePermissions.TYPE_PERSISTENT_STORAGE) {
            return getString(R.string.site_permission_storage);
        }
        return row.thirdPartyHost != null
                ? getString(R.string.site_permission_cookies, row.thirdPartyHost)
                : getString(R.string.site_permission_cookies_unknown);
    }

    private static int iconOf(@NonNull SitePermissions.Row<?> row) {
        if (row.type == SitePermissions.TYPE_LOCATION) {
            return R.drawable.location_on_24;
        }
        if (row.type == SitePermissions.TYPE_PERSISTENT_STORAGE) {
            return R.drawable.database_24;
        }
        return R.drawable.cookie_24;
    }

    /**
     * After a Reset, the page on screen is reloaded so it runs without the
     * answer it had (a page holding the location or extra storage keeps
     * them until it loads again). Only the tab still showing this site.
     */
    /** Left with Back or a tap outside: return to the shield sheet ({@link ShieldReturn}). */
    private boolean mCancelled;

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        mCancelled = true;
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        ShieldReturn.onDismiss(this, mCancelled);
        if (!mChanged || mHost == null || mActivity == null) {
            return;
        }
        mChanged = false;
        GeckoState state = mIsIncognito
                ? new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class).peekCurrentGeckoState()
                : new ViewModelProvider(mActivity).get(GeckoStateViewModel.class).peekCurrentGeckoState();
        if (state == null || !mHost.equals(SitePermissions.hostOf(state.getEntityUri()))) {
            return;
        }
        GeckoSession session = state.getGeckoSession();
        if (session != null) {
            session.reload();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mView = null;
    }
}
