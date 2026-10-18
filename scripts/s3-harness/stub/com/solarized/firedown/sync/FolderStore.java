package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;

import com.solarized.firedown.sync.model.VaultEntry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Harness stand-in for the SAF folder store: configured when the prefs hold
 * a tree URI under the real key name, like the real one; every operation
 * refuses (the folder is not what these tests exercise).
 */
public class FolderStore implements BackupStore {
    static final String KEY_TREE = "com.solarized.firedown.preferences.cloud.backup.folder.tree";
    private final SharedPreferences prefs;

    public FolderStore(Context context, SharedPreferences prefs) {
        this.prefs = prefs;
    }

    @Override
    public boolean isConfigured() {
        return prefs.getString(KEY_TREE, null) != null;
    }

    @Override
    public boolean isReachable() {
        return false;
    }

    @Override
    public String providerLabel() {
        return isConfigured() ? "folder" : "";
    }

    @Override
    public String locationLabel() {
        return providerLabel();
    }

    private static IOException no() {
        return new UnreachableException("harness folder stand-in");
    }

    @Override
    public List<VaultEntry> listEntries(String account, byte[] storageKey) throws IOException {
        throw no();
    }

    @Override
    public void writeEntry(String account, VaultEntry entry, byte[] storageKey) throws IOException {
        throw no();
    }

    @Override
    public OutputStream openNewObject(String account, String objectId, long length) throws IOException {
        throw no();
    }

    @Override
    public InputStream openObject(String account, String objectId) throws IOException {
        throw no();
    }

    @Override
    public RangeReader openObjectRanges(String account, String objectId) throws IOException {
        throw no();
    }

    @Override
    public void deleteObject(String account, String objectId) throws IOException {
        throw no();
    }

    @Override
    public void deleteAccount(String account) throws IOException {
        throw no();
    }
}
