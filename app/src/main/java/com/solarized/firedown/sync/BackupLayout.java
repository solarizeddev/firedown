package com.solarized.firedown.sync;

import com.solarized.firedown.sync.crypto.BookmarkBlob;
import com.solarized.firedown.sync.crypto.Hex;
import com.solarized.firedown.sync.model.VaultEntry;
import com.solarized.firedown.sync.model.VaultManifest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The backup layout and its commit rules, ONE copy for every
 * {@link BackupStore}:
 * <pre>
 *   &lt;place&gt;/Firedown Backup/&lt;account base32&gt;/objects/
 *       &lt;objectId&gt;.fdvc    the ciphertext: FDVC2 chunks, back to back
 *       &lt;objectId&gt;.entry   the encrypted sidecar: ONE VaultEntry
 * </pre>
 * The sidecar is written LAST and is the commit marker. An object without
 * one is an upload that never finished and is swept once it is older than
 * {@link #ORPHAN_MIN_AGE_MS}; a sidecar without its object is skipped and
 * never deleted (another phone's object may still be on its way). Names are
 * matched by PREFIX, so a provider's conflict copy ({@code <id> (1).entry})
 * still belongs to its object. A folder written through one store reads
 * through any other: the names, the sidecar framing and the sweep are here,
 * not in the stores.
 */
final class BackupLayout {

    /** The root made inside the chosen place, visible to the user there. */
    static final String ROOT_DIR = "Firedown Backup";
    static final String OBJECTS_DIR = "objects";
    static final String OBJECT_EXT = ".fdvc";
    static final String ENTRY_EXT = ".entry";
    /** An object with no sidecar younger than this is an upload in flight. */
    static final long ORPHAN_MIN_AGE_MS = TimeUnit.HOURS.toMillis(24);
    /** A sidecar is a few KB (one entry + a thumbnail); anything larger is
     *  not one, and is not read into memory. */
    static final long MAX_ENTRY_BYTES = 512 * 1024;

    private BackupLayout() {
    }

    /** A fresh object id: 16 random bytes as lowercase hex, the id shape the
     *  FDVC2 chunk AAD hex-decodes. */
    static String newObjectId() {
        byte[] id = new byte[16];
        new SecureRandom().nextBytes(id);
        return Hex.encode(id);
    }

    /** {@code <32 hex>.ext} → the id; a provider's conflict copy
     *  ({@code <id> (1).fdvc}, {@code <id>.fdvc (1)}) still matches;
     *  anything else → null. */
    static String objectIdOf(String name, String ext) {
        if (name == null || name.length() < 32 + ext.length()) {
            return null;
        }
        if (!name.startsWith(ext, 32) && !name.startsWith(ext, conflictSuffixEnd(name))) {
            return null;
        }
        for (int i = 0; i < 32; i++) {
            char c = name.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
        }
        return name.substring(0, 32);
    }

    /** Where {@code " (<digits>)"} right after the id ends, or -1. */
    private static int conflictSuffixEnd(String name) {
        if (!name.startsWith(" (", 32)) {
            return -1;
        }
        int i = 34;
        while (i < name.length() && Character.isDigit(name.charAt(i))) {
            i++;
        }
        if (i == 34 || i >= name.length() || name.charAt(i) != ')') {
            return -1;
        }
        return i + 1;
    }

    /** {@code <32 hex>.ext} exactly: the file the app wrote, not a copy. */
    static boolean isExactName(String name, String ext) {
        return name != null && name.length() == 32 + ext.length() && objectIdOf(name, ext) != null;
    }

    /** One file in an objects directory, with the store's own handle to it. */
    static final class Item<H> {
        final H handle;
        final String name;
        final long size;
        /** Last modified, epoch ms; 0 when the place does not say. */
        final long modified;

        Item(H handle, String name, long size, long modified) {
            this.handle = handle;
            this.name = name;
            this.size = size;
            this.modified = modified;
        }
    }

    /**
     * An objects directory split by object id; names that are neither an
     * object nor a sidecar are dropped. Per id the exactly named file wins
     * over a provider's conflict copy, whatever order the place lists them
     * in (a server's key order puts {@code <id>.entry (1)} after
     * {@code <id>.entry}). The sidecar's copies are kept as fallbacks: a copy
     * that does not open, or an older one, must not hide the real sidecar,
     * and a real sidecar that does not open must not hide a copy that does.
     */
    static final class Listing<H> {
        final Map<String, Item<H>> objects = new HashMap<>();
        /** The sidecar per id: the exactly named one when there is one. */
        final Map<String, Item<H>> entries = new HashMap<>();
        /** The other sidecars of an id (conflict copies), in listing order. */
        final Map<String, List<Item<H>>> entryCopies = new HashMap<>();

        void add(Item<H> item) {
            String id = objectIdOf(item.name, OBJECT_EXT);
            if (id != null) {
                Item<H> was = objects.get(id);
                if (was == null || (isExactName(item.name, OBJECT_EXT)
                        && !isExactName(was.name, OBJECT_EXT))) {
                    objects.put(id, item);
                }
                return;
            }
            id = objectIdOf(item.name, ENTRY_EXT);
            if (id == null) {
                return;
            }
            Item<H> was = entries.get(id);
            if (was == null) {
                entries.put(id, item);
            } else if (isExactName(item.name, ENTRY_EXT) && !isExactName(was.name, ENTRY_EXT)) {
                entries.put(id, item);
                copiesOf(id).add(was);
            } else {
                copiesOf(id).add(item);
            }
        }

        private List<Item<H>> copiesOf(String id) {
            List<Item<H>> copies = entryCopies.get(id);
            if (copies == null) {
                copies = new ArrayList<>();
                entryCopies.put(id, copies);
            }
            return copies;
        }
    }

    /** Reads and decodes a batch of sidecars: object id → entry. An id
     *  missing from the answer could not be read or opened, and is skipped. */
    interface EntryLoader<H> {
        Map<String, VaultEntry> load(List<Item<H>> sidecars) throws IOException;
    }

    /** Deletes an orphaned object, best-effort. */
    interface Sweeper<H> {
        void delete(Item<H> object);
    }

    /**
     * The committed entries of a listing, and the orphan sweep. A sidecar
     * with no object is not listed and NEVER deleted (it may be another
     * phone's commit marker whose object is still syncing in). A sidecar the
     * key cannot open (another code's file, a corrupt one) is skipped, never
     * deleted. A sidecar naming another object than its file name is a
     * conflict copy glued to the wrong name: the name on disk wins. When an
     * id's sidecar does not open, its conflict copies are tried in turn, one
     * batch per round, so a corrupt file never hides a readable one.
     */
    static <H> List<VaultEntry> collectEntries(Listing<H> listing, EntryLoader<H> loader,
                                               Sweeper<H> sweeper, long now) throws IOException {
        List<Item<H>> committed = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, Item<H>> e : listing.entries.entrySet()) {
            if (listing.objects.containsKey(e.getKey())) {
                ids.add(e.getKey());
                committed.add(e.getValue());
            }
        }
        Map<String, VaultEntry> loaded = new HashMap<>();
        if (!committed.isEmpty()) {
            loaded.putAll(loader.load(committed));
        }
        for (int round = 0; ; round++) {
            List<Item<H>> copies = new ArrayList<>();
            for (String id : ids) {
                List<Item<H>> c = listing.entryCopies.get(id);
                if (!loaded.containsKey(id) && c != null && round < c.size()) {
                    copies.add(c.get(round));
                }
            }
            if (copies.isEmpty()) {
                break;
            }
            loaded.putAll(loader.load(copies));
        }
        List<VaultEntry> out = new ArrayList<>();
        for (String id : ids) {
            VaultEntry entry = loaded.get(id);
            if (entry == null) {
                continue;
            }
            if (!id.equals(entry.objectId)) {
                entry = withObjectId(entry, id);
            }
            out.add(entry);
        }
        for (Map.Entry<String, Item<H>> e : listing.objects.entrySet()) {
            if (listing.entries.containsKey(e.getKey())) {
                continue;
            }
            Item<H> object = e.getValue();
            if (object.modified > 0 && now - object.modified > ORPHAN_MIN_AGE_MS) {
                sweeper.delete(object);
            }
        }
        return out;
    }

    static VaultEntry withObjectId(VaultEntry e, String objectId) {
        return new VaultEntry(objectId, e.wrappedDek, e.name, e.size, e.mime,
                e.downloadedAt, e.chunkCount, e.thumb, e.origin, e.backedUpAt);
    }

    /** The sidecar bytes of {@code entry}: a one-entry manifest under the
     *  bookmark blob framing and the storage key. */
    static byte[] encodeEntry(VaultEntry entry, byte[] storageKey) throws GeneralSecurityException {
        List<VaultEntry> one = new ArrayList<>(1);
        one.add(entry);
        return BookmarkBlob.encrypt(VaultManifest.toJson(one, System.currentTimeMillis()), storageKey);
    }

    /** The entry a sidecar holds, or null when it is not a sidecar this key
     *  opens (too large, another code's, corrupt). */
    static VaultEntry decodeEntry(byte[] blob, byte[] storageKey) {
        if (blob == null || blob.length > MAX_ENTRY_BYTES) {
            return null;
        }
        try {
            List<VaultEntry> entries = VaultManifest.fromJson(BookmarkBlob.decrypt(blob, storageKey));
            return entries.isEmpty() ? null : entries.get(0);
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    /** Reads a stream whole, refusing more than {@code max} bytes. */
    static byte[] readAll(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) {
                throw new IOException("more than " + max + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
