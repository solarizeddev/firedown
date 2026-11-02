package com.solarized.firedown.addons;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.Image;
import org.mozilla.geckoview.WebExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the screens show about one installed add-on, read once from Gecko's
 * metadata. Immutable: a change in Gecko produces a new snapshot.
 */
public final class AddonInfo {

    @NonNull public final String id;
    @NonNull public final String name;
    @NonNull public final String description;
    @NonNull public final String version;
    @Nullable public final String creator;
    @Nullable public final String homepageUrl;
    @Nullable public final String amoListingUrl;
    @Nullable public final String optionsPageUrl;
    public final boolean openOptionsPageInTab;
    public final boolean enabled;
    /** {@link WebExtension.DisabledFlags} bits; USER alone means the user turned it off. */
    public final int disabledFlags;
    public final boolean allowedInPrivateBrowsing;
    /** Gecko's "incognito" manifest key: "spanning", "split" or "not_allowed". */
    @Nullable public final String incognito;
    @NonNull public final List<String> requiredPermissions;
    @NonNull public final List<String> requiredOrigins;
    @NonNull public final List<String> requiredDataCollection;
    /** Asked for at run time (or by the user); the granted ones are what it has now. */
    @NonNull public final List<String> optionalPermissions;
    @NonNull public final List<String> optionalOrigins;
    @NonNull public final List<String> optionalDataCollection;
    @NonNull public final List<String> grantedOptionalPermissions;
    @NonNull public final List<String> grantedOptionalOrigins;
    @NonNull public final List<String> grantedOptionalDataCollection;
    @Nullable public final String updateDate;
    @NonNull public final String baseUrl;
    @NonNull public final Image icon;
    @NonNull public final WebExtension extension;

    private AddonInfo(@NonNull WebExtension extension) {
        WebExtension.MetaData m = extension.metaData;
        this.extension = extension;
        this.id = extension.id;
        String declared = m.name == null ? "" : m.name.trim();
        this.name = declared.isEmpty() ? extension.id : declared;
        this.description = m.description == null ? "" : m.description;
        this.version = m.version;
        this.creator = m.creatorName;
        this.homepageUrl = m.homepageUrl;
        this.amoListingUrl = m.amoListingUrl;
        this.optionsPageUrl = m.optionsPageUrl;
        this.openOptionsPageInTab = m.openOptionsPageInTab;
        this.enabled = m.enabled;
        this.disabledFlags = m.disabledFlags;
        this.allowedInPrivateBrowsing = m.allowedInPrivateBrowsing;
        this.incognito = m.incognito;
        this.requiredPermissions = list(m.requiredPermissions);
        this.requiredOrigins = list(m.requiredOrigins);
        this.requiredDataCollection = list(m.requiredDataCollectionPermissions);
        this.optionalPermissions = list(m.optionalPermissions);
        this.optionalOrigins = list(m.optionalOrigins);
        this.optionalDataCollection = list(m.optionalDataCollectionPermissions);
        this.grantedOptionalPermissions = list(m.grantedOptionalPermissions);
        this.grantedOptionalOrigins = list(m.grantedOptionalOrigins);
        this.grantedOptionalDataCollection = list(m.grantedOptionalDataCollectionPermissions);
        this.updateDate = m.updateDate;
        this.baseUrl = m.baseUrl;
        this.icon = m.icon;
    }

    @NonNull
    public static AddonInfo from(@NonNull WebExtension extension) {
        return new AddonInfo(extension);
    }

    /** Off for a reason the user cannot undo (blocked, unsigned, incompatible). */
    public boolean disabledBySystem() {
        int systemBits = WebExtension.DisabledFlags.BLOCKLIST
                | WebExtension.DisabledFlags.SIGNATURE
                | WebExtension.DisabledFlags.APP_VERSION
                | WebExtension.DisabledFlags.APP;
        return (disabledFlags & systemBits) != 0;
    }

    /** The add-on may be offered in private tabs at all (its manifest doesn't forbid it). */
    public boolean canRunInPrivate() {
        return !"not_allowed".equals(incognito);
    }

    @NonNull
    private static List<String> list(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(values)));
    }
}
