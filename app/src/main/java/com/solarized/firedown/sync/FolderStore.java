package com.solarized.firedown.sync;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.model.VaultEntry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The backup FOLDER: a Storage Access Framework tree the user picked once
 * ({@code ACTION_OPEN_DOCUMENT_TREE}) — Google Drive, Dropbox, Nextcloud, an
 * SD card, anything with a {@code DocumentsProvider} — holding the encrypted
 * objects {@link VaultEngine} writes. There is no server: the folder IS the
 * truth, and the app that owns the folder does whatever syncing it does.
 * Firedown never learns whether a byte left the phone.
 *
 * <p><b>Layout inside the picked tree:</b>
 * <pre>
 *   &lt;picked folder&gt;/Firedown Backup/&lt;account base32&gt;/objects/
 *       &lt;objectId&gt;.fdvc    the ciphertext: FDVC2 chunks, back to back
 *       &lt;objectId&gt;.entry   the encrypted sidecar: ONE VaultEntry (name, size,
 *                           mime, wrapped DEK, thumbnail, origin, dates)
 * </pre>
 * The account directory is the recovery-code-derived account id
 * ({@code SyncIdentity.accountBase32}), so two codes sharing one folder never
 * see each other's files, and a new phone that adopts the same code and picks
 * the same folder finds them. The sidecar is written LAST and is the commit
 * marker: an object without one is an upload that never finished and is swept
 * once it is older than {@link BackupLayout#ORPHAN_MIN_AGE_MS}; a sidecar
 * without its object is skipped and never deleted (see
 * {@link BackupLayout#collectEntries}). Everything the server used to hold
 * encrypted — the manifest — is now these sidecars, under the same
 * {@code BookmarkBlob} framing and the same storage key, so the provider sees
 * only ciphertext and opaque names. The layout and its rules are
 * {@link BackupLayout}'s, shared with {@link WebDavStore}; this class is the
 * Storage Access Framework half.
 *
 * <p><b>Why sidecars, not one index file.</b> A sync-lagged folder has no
 * optimistic concurrency: two phones writing one index would overwrite each
 * other's entries through the provider's own conflict handling, silently.
 * One small file per object has no shared state to lose — a listing is the
 * index, rebuilt on every load, and a conflict copy of a sidecar is at worst a
 * duplicate row.
 *
 * <p><b>Why {@link DocumentsContract}, not {@code DocumentFile}.</b> The
 * contract is what the app already speaks for the download mirror restore
 * ({@code DownloadBackupMirror}); one query per directory listing instead of
 * a stat per file. Object files are created under a mime type no provider
 * maps to an extension ({@link #OBJECT_MIME}): the external-storage provider
 * appends the mime's extension to a name whose own extension does not map
 * back to it, and {@code application/octet-stream} would have turned
 * {@code <id>.fdvc} into {@code <id>.fdvc.bin}. Names are matched by PREFIX
 * when listing for the same reason.
 *
 * <p>Every method that touches the resolver can throw {@link
 * BackupStore.UnreachableException}: the grant was revoked (the provider app signed out,
 * updated, or was uninstalled; the SD card left), the tree is gone, or the
 * provider is not responding. Callers treat it as "the folder, not the
 * network" — the worker names it, the Backup screen shows the folder-lost
 * state and asks for the folder again.
 */
public final class FolderStore implements BackupStore {

    private static final String TAG = FolderStore.class.getSimpleName();

    private static final String ROOT_DIR = BackupLayout.ROOT_DIR;
    private static final String OBJECTS_DIR = BackupLayout.OBJECTS_DIR;
    private static final String OBJECT_EXT = BackupLayout.OBJECT_EXT;
    private static final String ENTRY_EXT = BackupLayout.ENTRY_EXT;
    /** No registered extension anywhere, so every provider keeps the display
     *  name we hand it verbatim (see the class doc). */
    static final String OBJECT_MIME = "application/vnd.firedown.vault";

    private static final String[] CHILD_PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    private static final String AUTHORITY_EXTERNAL = "com.android.externalstorage.documents";
    private static final String AUTHORITY_DOWNLOADS = "com.android.providers.downloads.documents";

    /** One file of a directory listing. */
    private static final class Doc {
        final Uri uri;
        final String name;
        final long size;
        final long modified;

        Doc(Uri uri, String name, long size, long modified) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.modified = modified;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final ContentResolver resolver;

    public FolderStore(@NonNull Context context, @NonNull SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.resolver = this.context.getContentResolver();
    }

    // ---- the grant ----

    /** Whether a folder has been chosen (not whether it is reachable now). */
    @Override
    public boolean isConfigured() {
        return treeUri() != null;
    }

    /** The picked tree, or null. */
    @Nullable
    public Uri treeUri() {
        String stored = prefs.getString(Preferences.FOLDER_BACKUP_TREE_URI, null);
        return TextUtils.isEmpty(stored) ? null : Uri.parse(stored);
    }

    /** The system external-storage provider: the one whose tree ids name a
     *  filesystem path ({@code <volume>:<relative path>}). */
    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";
    private static final String PRIMARY_VOLUME = "primary";

    /**
     * The directory an external-storage tree URI denotes, or null for a tree
     * on any other provider (Drive, Dropbox, Nextcloud… have no path). The
     * provider derives its ids from the volume: {@code primary} is the shared
     * storage root, any other volume id is a removable card mounted under
     * {@code /storage/<id>} — the same mapping {@code RestoredFileAccess}
     * applies in the other direction for the restore grant.
     */
    @Nullable
    public static File directoryOfTree(@NonNull Uri treeUri) {
        if (!EXTERNAL_STORAGE_AUTHORITY.equals(treeUri.getAuthority())) {
            return null;
        }
        String docId;
        try {
            docId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException e) {
            return null; // not a tree URI
        }
        int colon = docId.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String volume = docId.substring(0, colon);
        String relative = docId.substring(colon + 1);
        File volumeRoot = PRIMARY_VOLUME.equals(volume)
                ? Environment.getExternalStorageDirectory()
                : new File("/storage", volume);
        if (volumeRoot == null) {
            return null;
        }
        return TextUtils.isEmpty(relative) ? volumeRoot : new File(volumeRoot, relative);
    }

    /**
     * True when the tree is {@code dir} itself or anything under it — the
     * check behind the "that's your downloads folder" guard: a backup written
     * into the app's own download directory is a second copy on the same
     * storage as the originals, which survives a deleted file and nothing
     * else. Paths are compared canonically (symlinked storage roots), falling
     * back to the absolute form; a tree on a cloud provider is never within.
     */
    public static boolean treeIsWithin(@NonNull Uri treeUri, @NonNull File dir) {
        File treeDir = directoryOfTree(treeUri);
        if (treeDir == null) {
            return false;
        }
        String tree = canonical(treeDir);
        String target = canonical(dir);
        return tree.equals(target) || tree.startsWith(target + File.separator);
    }

    /** The user-readable path of an external-storage tree ({@code Download/
     *  Firedown}), or null for any other provider. */
    @Nullable
    public static String treePathLabel(@NonNull Uri treeUri) {
        if (!EXTERNAL_STORAGE_AUTHORITY.equals(treeUri.getAuthority())) {
            return null;
        }
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            int colon = docId.indexOf(':');
            return colon < 0 ? docId : docId.substring(colon + 1);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String canonical(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    /**
     * Adopts a tree the user just picked: takes the persistable read+write
     * grant (the picker's result carries it only for this process until we
     * do) and remembers the tree. A previously chosen tree's grant is released
     * — the old folder keeps its files; nothing is deleted.
     */
    public void adopt(@NonNull Uri treeUri) {
        Uri previous = treeUri();
        try {
            resolver.takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            // The picker result did not carry a persistable grant (a provider
            // that only offers transient access). Remember the tree anyway;
            // the next reachability check reports it honestly.
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "adopt: no persistable grant", e);
            }
        }
        prefs.edit().putString(Preferences.FOLDER_BACKUP_TREE_URI, treeUri.toString())
                .remove(Preferences.FOLDER_BACKUP_TREE_NAME)
                .apply();
        if (previous != null && !previous.equals(treeUri)) {
            releaseQuietly(previous);
        }
    }

    /** What the write check writes. Small: the check is about permission, not throughput. */
    private static final byte[] WRITE_CHECK_BYTES =
            "firedown write check".getBytes(StandardCharsets.US_ASCII);

    /**
     * Proves a JUST-PICKED tree can hold backups, BEFORE it is adopted: in the
     * tree's {@link #ROOT_DIR} (created when missing) it creates a small file,
     * writes it and deletes it, the operations a backup and a removal need
     * (a failed backup cleans up after itself, and Remove deletes, so a
     * provider that cannot delete is no backup folder either). It runs on the
     * grant the picker's result carries, so a failure leaves the current
     * folder and its persisted grant untouched, and nothing is adopted.
     *
     * <p>Why it exists: the system picker can offer a folder no backup can
     * use, a provider that lists folders but refuses to create or write in
     * them. Before this check that surfaced as the FIRST backup failing,
     * minutes or days after the pick, as a folder error nobody could connect
     * to the choice. It replaces any attempt to judge providers by name (see
     * {@link BackupPlaces}): it tests the folder itself, so it needs no list.
     *
     * <p>Deliberately NOT a read-back: a provider that uploads on close may
     * serve the old (empty) content for a moment, and a check that fails a
     * working folder is worse than none. Reading is covered by the grant and
     * by the first listing that follows the adopt. A {@link #ROOT_DIR} this
     * call created is removed again on failure; on success it stays (the
     * first backup would create it anyway). Off the main thread only: a cloud
     * provider may go to the network, and SAF has no timeout to give it.
     */
    public void checkWritable(@NonNull Uri tree) throws IOException {
        Uri root;
        try {
            root = rootDocument(tree);
        } catch (RuntimeException e) {
            throw new UnreachableException("not a folder tree", e);
        }
        if (!exists(root)) {
            throw new UnreachableException("picked folder is gone");
        }
        Uri backupRoot = childDir(tree, root, ROOT_DIR, false);
        boolean createdRoot = false;
        if (backupRoot == null) {
            backupRoot = childDir(tree, root, ROOT_DIR, true);
            createdRoot = true;
        }
        Uri probe = null;
        boolean passed = false;
        try {
            String name = "write-check-" + BackupLayout.newObjectId().substring(0, 8) + ".tmp";
            try {
                probe = DocumentsContract.createDocument(resolver, backupRoot, OBJECT_MIME, name);
            } catch (SecurityException e) {
                throw new UnreachableException("cannot create in the picked folder", e);
            } catch (RuntimeException e) {
                throw new IOException("cannot create in the picked folder", e);
            }
            if (probe == null) {
                throw new IOException("provider refused to create the check file");
            }
            try (OutputStream out = openWrite(probe)) {
                out.write(WRITE_CHECK_BYTES);
            }
            Uri written = probe;
            probe = null;
            delete(written);
            passed = true;
        } finally {
            if (probe != null) {
                deleteQuietly(probe);
            }
            if (!passed && createdRoot) {
                deleteQuietly(backupRoot);
            }
        }
    }

    /** Forgets the folder and releases its grant. Nothing in it is touched. */
    public void forget() {
        Uri previous = treeUri();
        prefs.edit().remove(Preferences.FOLDER_BACKUP_TREE_URI)
                .remove(Preferences.FOLDER_BACKUP_TREE_NAME)
                .apply();
        if (previous != null) {
            releaseQuietly(previous);
        }
    }

    private void releaseQuietly(Uri treeUri) {
        try {
            resolver.releasePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Already gone — the provider was uninstalled, or the grant lapsed.
        }
    }

    /**
     * Whether the folder can be used RIGHT NOW: the persisted grant is still
     * held with read+write, and the tree's root document answers a query.
     * One resolver round trip; safe off the main thread only (a provider may
     * take its time — Drive answers from the network when its cache is cold).
     * The same query carries the folder's display name, remembered for
     * {@link #locationLabel()} so no surface ever asks the provider for it on
     * its own.
     */
    @Override
    public boolean isReachable() {
        Uri tree = treeUri();
        if (tree == null || !holdsGrant(tree)) {
            return false;
        }
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        };
        try (Cursor c = resolver.query(rootDocument(tree), projection, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                return false;
            }
            rememberFolderName(tree, c.isNull(1) ? null : c.getString(1));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The picked folder's own name as its provider reports it ("My Drive",
     * "Backups"), remembered by the last {@link #isReachable()} read; "" until
     * one has answered. Cheap (prefs only).
     */
    @NonNull
    public String folderName() {
        String name = prefs.getString(Preferences.FOLDER_BACKUP_TREE_NAME, "");
        return name == null ? "" : name;
    }

    private void rememberFolderName(Uri tree, @Nullable String name) {
        if (!tree.equals(treeUri())) {
            return; // a different folder was adopted while the query ran
        }
        String next = name == null ? "" : name.trim();
        if (!next.equals(folderName())) {
            prefs.edit().putString(Preferences.FOLDER_BACKUP_TREE_NAME, next).apply();
        }
    }

    /**
     * WHERE the backup is, in the user's terms, for the folder row and the
     * hero: the provider, then the picked folder's name — "Google Drive ›
     * Backups". "On Google Drive" alone (the first cut) never said WHICH
     * folder, which is the question a cloud picker leaves open: Drive's root
     * or a folder inside it both work, and the app adds its own
     * {@code Firedown Backup} folder under either. The name is dropped when
     * unknown (no read yet) or when it only repeats the provider.
     */
    @NonNull
    @Override
    public String locationLabel() {
        String provider = providerLabel();
        String name = folderName();
        if (TextUtils.isEmpty(name) || name.equalsIgnoreCase(provider)) {
            return provider;
        }
        if (TextUtils.isEmpty(provider)) {
            return name;
        }
        return context.getString(R.string.folder_backup_location, provider, name);
    }

    private boolean holdsGrant(Uri tree) {
        for (UriPermission p : resolver.getPersistedUriPermissions()) {
            if (tree.equals(p.getUri())) {
                return p.isReadPermission() && p.isWritePermission();
            }
        }
        return false;
    }

    /**
     * A human name for WHERE the folder lives, for "Back up to Google Drive"
     * and the hero's "on Google Drive": the provider app's label, or for the
     * system providers the device's own words (this phone / SD card /
     * Downloads). Falls back to the authority, never to an empty string.
     */
    @NonNull
    @Override
    public String providerLabel() {
        Uri tree = treeUri();
        if (tree == null) {
            return "";
        }
        return providerLabelOf(tree);
    }

    /** {@link #providerLabel()} for any tree, adopted or not (the write
     *  check names a picked folder before it is adopted). */
    @NonNull
    public String providerLabelOf(@NonNull Uri tree) {
        String authority = tree.getAuthority();
        if (authority == null) {
            return "";
        }
        if (AUTHORITY_EXTERNAL.equals(authority)) {
            String docId = treeDocumentIdOrNull(tree);
            boolean primary = docId == null || docId.startsWith("primary:");
            return context.getString(primary
                    ? R.string.folder_backup_provider_phone
                    : R.string.folder_backup_provider_sd);
        }
        if (AUTHORITY_DOWNLOADS.equals(authority)) {
            return context.getString(R.string.folder_backup_provider_downloads);
        }
        PackageManager pm = context.getPackageManager();
        ProviderInfo info = pm.resolveContentProvider(authority, 0);
        if (info != null) {
            CharSequence label = info.loadLabel(pm);
            if (!TextUtils.isEmpty(label)) {
                return label.toString();
            }
            if (info.applicationInfo != null) {
                label = info.applicationInfo.loadLabel(pm);
                if (!TextUtils.isEmpty(label)) {
                    return label.toString();
                }
            }
        }
        return authority;
    }

    @Nullable
    private static String treeDocumentIdOrNull(Uri tree) {
        try {
            return DocumentsContract.getTreeDocumentId(tree);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- layout ----

    private Uri rootDocument(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
    }

    /** The configured tree, or an {@link UnreachableException} when none. */
    private Uri requireTree() throws UnreachableException {
        Uri tree = treeUri();
        if (tree == null) {
            throw new UnreachableException("no backup folder chosen");
        }
        if (!holdsGrant(tree)) {
            throw new UnreachableException("backup folder grant lost");
        }
        return tree;
    }

    /**
     * The {@code Firedown Backup/<account>/objects} directory of the tree,
     * created when {@code create} and missing; null when absent and not
     * creating. Three listings on a cold path; cheap against a local provider,
     * a network round trip each against a cold Drive cache.
     */
    @Nullable
    private Uri objectsDir(String account, boolean create) throws IOException {
        Uri tree = requireTree();
        Uri root = rootDocument(tree);
        if (!exists(root)) {
            throw new UnreachableException("backup folder is gone");
        }
        Uri backupRoot = childDir(tree, root, ROOT_DIR, create);
        if (backupRoot == null) {
            return null;
        }
        Uri accountDir = childDir(tree, backupRoot, account, create);
        if (accountDir == null) {
            return null;
        }
        return childDir(tree, accountDir, OBJECTS_DIR, create);
    }

    /** The named child directory of {@code parent}, created on demand. */
    @Nullable
    private Uri childDir(Uri tree, Uri parent, String name, boolean create) throws IOException {
        for (Doc d : listChildren(tree, parent, true)) {
            if (name.equals(d.name)) {
                return d.uri;
            }
        }
        if (!create) {
            return null;
        }
        Uri created;
        try {
            created = DocumentsContract.createDocument(resolver, parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, name);
        } catch (SecurityException e) {
            throw new UnreachableException("cannot create " + name, e);
        } catch (RuntimeException e) {
            throw new IOException("cannot create " + name, e);
        }
        if (created == null) {
            throw new IOException("provider refused to create " + name);
        }
        return created;
    }

    /** Lists a directory. {@code dirsOnly} keeps the projection walk cheap
     *  for the layout lookups; the objects listing wants every file. */
    private List<Doc> listChildren(Uri tree, Uri parent, boolean dirsOnly) throws IOException {
        List<Doc> out = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getDocumentId(parent));
        Cursor cursor;
        try {
            cursor = resolver.query(children, CHILD_PROJECTION, null, null, null);
        } catch (SecurityException e) {
            throw new UnreachableException("folder listing refused", e);
        } catch (RuntimeException e) {
            throw new IOException("folder listing failed", e);
        }
        if (cursor == null) {
            throw new UnreachableException("folder listing returned nothing");
        }
        try {
            while (cursor.moveToNext()) {
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if (dirsOnly != dir) {
                    continue;
                }
                long size = cursor.isNull(3) ? 0L : cursor.getLong(3);
                long modified = cursor.isNull(4) ? 0L : cursor.getLong(4);
                out.add(new Doc(DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                        name, size, modified));
            }
        } finally {
            cursor.close();
        }
        return out;
    }

    private boolean exists(Uri doc) {
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID};
        try (Cursor c = resolver.query(doc, projection, null, null, null)) {
            return c != null && c.getCount() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- the objects directory ----

    /** The objects directory, split by object id. Empty when it does not
     *  exist yet. */
    private BackupLayout.Listing<Uri> list(String account) throws IOException {
        BackupLayout.Listing<Uri> listing = new BackupLayout.Listing<>();
        Uri dir = objectsDir(account, false);
        if (dir == null) {
            return listing;
        }
        Uri tree = requireTree();
        for (Doc d : listChildren(tree, dir, false)) {
            listing.add(new BackupLayout.Item<>(d.uri, d.name, d.size, d.modified));
        }
        return listing;
    }

    /** Every committed entry — the folder's index ({@link
     *  BackupLayout#collectEntries}: orphans swept, a sidecar without its
     *  object or one this key cannot open skipped, never deleted). */
    @Override
    public List<VaultEntry> listEntries(String account, byte[] storageKey) throws IOException {
        return BackupLayout.collectEntries(list(account), sidecars -> {
            Map<String, VaultEntry> out = new HashMap<>();
            for (BackupLayout.Item<Uri> item : sidecars) {
                VaultEntry entry = readEntry(item, storageKey);
                if (entry != null) {
                    String id = BackupLayout.objectIdOf(item.name, ENTRY_EXT);
                    out.put(id, entry);
                }
            }
            return out;
        }, object -> deleteQuietly(object.handle), System.currentTimeMillis());
    }

    @Nullable
    private VaultEntry readEntry(BackupLayout.Item<Uri> item, byte[] storageKey) {
        if (item.size > BackupLayout.MAX_ENTRY_BYTES) {
            return null;
        }
        byte[] blob;
        try (InputStream in = resolver.openInputStream(item.handle)) {
            if (in == null) {
                return null;
            }
            blob = BackupLayout.readAll(in, BackupLayout.MAX_ENTRY_BYTES);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return BackupLayout.decodeEntry(blob, storageKey);
    }

    /** Creates the (empty) object file for {@code objectId}; returns its URI. */
    private Uri createObject(String account, String objectId) throws IOException {
        Uri dir = objectsDir(account, true);
        if (dir == null) {
            throw new IOException("objects directory unavailable");
        }
        Uri created;
        try {
            created = DocumentsContract.createDocument(resolver, dir, OBJECT_MIME,
                    objectId + OBJECT_EXT);
        } catch (SecurityException e) {
            throw new UnreachableException("cannot create object", e);
        } catch (RuntimeException e) {
            throw new IOException("cannot create object", e);
        }
        if (created == null) {
            throw new IOException("provider refused to create the object");
        }
        return created;
    }

    /** Creates the object file and opens it for writing. {@code length} is
     *  not needed here: the provider takes the bytes as they come. A failed
     *  or short write leaves a file without a sidecar, which the engine
     *  deletes (and the orphan sweep would). */
    @Override
    public OutputStream openNewObject(String account, String objectId, long length)
            throws IOException {
        return openWrite(createObject(account, objectId));
    }

    /** Opens a document for a fresh write (truncating). */
    private OutputStream openWrite(Uri doc) throws IOException {
        OutputStream out;
        try {
            out = resolver.openOutputStream(doc, "wt");
        } catch (SecurityException e) {
            throw new UnreachableException("cannot write to the backup folder", e);
        } catch (FileNotFoundException e) {
            throw new IOException("object vanished before it was written", e);
        }
        if (out == null) {
            throw new IOException("provider returned no output stream");
        }
        return out;
    }

    /**
     * Writes (or rewrites) the sidecar for {@code entry} — the commit marker.
     * The whole sidecar is one small write; a provider that cannot overwrite
     * in place gets a delete + create.
     */
    @Override
    public void writeEntry(String account, VaultEntry entry, byte[] storageKey)
            throws IOException, GeneralSecurityException {
        Uri dir = objectsDir(account, true);
        if (dir == null) {
            throw new IOException("objects directory unavailable");
        }
        byte[] blob = BackupLayout.encodeEntry(entry, storageKey);
        Uri existing = findDoc(account, entry.objectId, ENTRY_EXT);
        if (existing != null) {
            try (OutputStream out = openWrite(existing)) {
                out.write(blob);
                out.flush();
                return;
            } catch (IOException rewrite) {
                deleteQuietly(existing);
            }
        }
        Uri created;
        try {
            created = DocumentsContract.createDocument(resolver, dir, OBJECT_MIME,
                    entry.objectId + ENTRY_EXT);
        } catch (SecurityException e) {
            throw new UnreachableException("cannot create sidecar", e);
        } catch (RuntimeException e) {
            throw new IOException("cannot create sidecar", e);
        }
        if (created == null) {
            throw new IOException("provider refused to create the sidecar");
        }
        try (OutputStream out = openWrite(created)) {
            out.write(blob);
            out.flush();
        }
    }

    /** The object (or sidecar) document of {@code objectId}, or null. */
    @Nullable
    private Uri findDoc(String account, String objectId, String ext) throws IOException {
        BackupLayout.Listing<Uri> listing = list(account);
        BackupLayout.Item<Uri> d = OBJECT_EXT.equals(ext)
                ? listing.objects.get(objectId) : listing.entries.get(objectId);
        return d == null ? null : d.handle;
    }

    /** The object document of {@code objectId}, or a FileNotFoundException. */
    private Uri requireObject(String account, String objectId) throws IOException {
        Uri doc = findDoc(account, objectId, OBJECT_EXT);
        if (doc == null) {
            throw new FileNotFoundException("object " + objectId + " is not in the folder");
        }
        return doc;
    }

    /** Opens an object read-only. The descriptor is the caller's to close. */
    private ParcelFileDescriptor openRead(Uri doc) throws IOException {
        ParcelFileDescriptor pfd;
        try {
            pfd = resolver.openFileDescriptor(doc, "r");
        } catch (SecurityException e) {
            throw new UnreachableException("cannot read from the backup folder", e);
        }
        if (pfd == null) {
            throw new IOException("provider returned no descriptor");
        }
        return pfd;
    }

    /** Positional reads over an open descriptor (see {@link #readRange}). */
    @Override
    public RangeReader openObjectRanges(String account, String objectId) throws IOException {
        Uri doc = requireObject(account, objectId);
        ParcelFileDescriptor pfd = openRead(doc);
        return new RangeReader() {
            @Override
            public byte[] read(long offset, int length) throws IOException {
                return readRange(doc, pfd, offset, length);
            }

            @Override
            public void close() {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                    // nothing further to do with a descriptor that will not close
                }
            }
        };
    }

    /**
     * Reads {@code length} bytes at {@code offset} from an open object: a
     * positional channel read when the provider handed over a real file (the
     * common case — the external-storage provider, Drive's cache), else a
     * fresh stream skipped forward (a provider that serves a pipe). Returns
     * fewer bytes at the end of the file.
     */
    private byte[] readRange(Uri doc, ParcelFileDescriptor pfd, long offset, int length)
            throws IOException {
        byte[] buf = new byte[length];
        int got = 0;
        try {
            FileChannel channel = new FileInputStream(pfd.getFileDescriptor()).getChannel();
            ByteBuffer bb = ByteBuffer.wrap(buf);
            while (got < length) {
                int n = channel.read(bb, offset + got);
                if (n < 0) {
                    break;
                }
                got += n;
            }
        } catch (IOException positional) {
            // Not seekable — read the range through a fresh sequential stream.
            got = 0;
            try (InputStream in = resolver.openInputStream(doc)) {
                if (in == null) {
                    throw new IOException("provider returned no input stream");
                }
                skipFully(in, offset);
                while (got < length) {
                    int n = in.read(buf, got, length - got);
                    if (n < 0) {
                        break;
                    }
                    got += n;
                }
            } catch (SecurityException e) {
                throw new UnreachableException("cannot read from the backup folder", e);
            }
        }
        if (got == length) {
            return buf;
        }
        byte[] out = new byte[got];
        System.arraycopy(buf, 0, out, 0, got);
        return out;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        byte[] sink = null;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (sink == null) {
                    sink = new byte[64 * 1024];
                }
                int read = in.read(sink, 0, (int) Math.min(sink.length, remaining));
                if (read < 0) {
                    throw new IOException("object shorter than the requested offset");
                }
                skipped = read;
            }
            remaining -= skipped;
        }
    }

    /** Opens an object as a sequential stream (restore). */
    @Override
    public InputStream openObject(String account, String objectId) throws IOException {
        Uri doc = requireObject(account, objectId);
        InputStream in;
        try {
            in = resolver.openInputStream(doc);
        } catch (SecurityException e) {
            throw new UnreachableException("cannot read from the backup folder", e);
        }
        if (in == null) {
            throw new IOException("provider returned no input stream");
        }
        return in;
    }

    /**
     * Deletes one backed-up file: the SIDECAR first (unreference — a listing
     * stops showing it the moment this lands), then the object, best-effort.
     * An object left behind by a failed second delete is an orphan the next
     * listing sweeps after {@link BackupLayout#ORPHAN_MIN_AGE_MS}.
     */
    @Override
    public void deleteObject(String account, String objectId) throws IOException {
        BackupLayout.Listing<Uri> listing = list(account);
        BackupLayout.Item<Uri> entry = listing.entries.get(objectId);
        if (entry != null) {
            delete(entry.handle);
        }
        // A provider's conflict copy of the sidecar would list the backup
        // again for as long as the object outlived a failed delete.
        List<BackupLayout.Item<Uri>> copies = listing.entryCopies.get(objectId);
        if (copies != null) {
            for (BackupLayout.Item<Uri> copy : copies) {
                deleteQuietly(copy.handle);
            }
        }
        BackupLayout.Item<Uri> object = listing.objects.get(objectId);
        if (object != null) {
            deleteQuietly(object.handle);
        }
    }

    private void delete(Uri doc) throws IOException {
        try {
            if (!DocumentsContract.deleteDocument(resolver, doc)) {
                throw new IOException("provider refused the delete");
            }
        } catch (SecurityException e) {
            throw new UnreachableException("cannot delete from the backup folder", e);
        } catch (FileNotFoundException e) {
            // Already gone — the outcome we wanted.
        } catch (RuntimeException e) {
            throw new IOException("delete failed", e);
        }
    }

    private void deleteQuietly(Uri doc) {
        try {
            delete(doc);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Deletes everything this account holds in the folder — every object and
     * sidecar, then the account directory. Only this account's directory:
     * another code's files beside it are untouched, as is the folder itself.
     */
    @Override
    public void deleteAccount(String account) throws IOException {
        Uri tree = requireTree();
        Uri root = rootDocument(tree);
        if (!exists(root)) {
            throw new UnreachableException("backup folder is gone");
        }
        Uri backupRoot = childDir(tree, root, ROOT_DIR, false);
        if (backupRoot == null) {
            return;
        }
        Uri accountDir = childDir(tree, backupRoot, account, false);
        if (accountDir == null) {
            return;
        }
        Uri objects = childDir(tree, accountDir, OBJECTS_DIR, false);
        if (objects != null) {
            // Sidecars first, so a listing that races this delete sees the
            // entries disappear before their objects do.
            for (Doc d : listChildren(tree, objects, false)) {
                if (BackupLayout.objectIdOf(d.name, ENTRY_EXT) != null) {
                    delete(d.uri);
                }
            }
            for (Doc d : listChildren(tree, objects, false)) {
                delete(d.uri);
            }
            delete(objects);
        }
        delete(accountDir);
    }
}
