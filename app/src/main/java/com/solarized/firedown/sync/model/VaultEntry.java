package com.solarized.firedown.sync.model;

/**
 * One backed-up file in the encrypted-storage vault manifest
 * (cloud-storage-spec.md §5). Everything here is client-side only — it lives
 * inside the encrypted manifest blob the server stores opaquely, so the server
 * never learns a filename, size, or key.
 *
 * <ul>
 *   <li>{@code objectId} — the server's random object id (hex), addresses the
 *       stored chunks.</li>
 *   <li>{@code wrappedDek} — the per-file AES-256 data key, wrapped under the
 *       storage master key (base64url). Unwrapped on restore to decrypt chunks.</li>
 *   <li>{@code name}/{@code mime}/{@code size}/{@code downloadedAt} — the local
 *       file's metadata, for the restore UI.</li>
 *   <li>{@code chunkCount} — how many encrypted chunks the object holds.</li>
 *   <li>{@code thumb} — a tiny base64 JPEG preview (nullable), generated at backup
 *       time and kept INSIDE the encrypted manifest so the list can show a
 *       thumbnail offline, even after the local copy is deleted. The server never
 *       sees it (the manifest is E2E-encrypted); kept small by design (~128px).</li>
 *   <li>{@code origin} — the download's origin URL (nullable), so a restored file's
 *       row shows its real {@code MIME · domain} meta line (e.g. {@code youtube.com})
 *       instead of a blank domain. Also inside the E2E-encrypted blob — the server
 *       never sees it. Entries backed up before this field existed carry null; the
 *       restore path then falls back to a generic "cloud" transport label.</li>
 * </ul>
 */
public final class VaultEntry {

    public final String objectId;
    public final String wrappedDek;
    public final String name;
    public final long size;
    public final String mime;
    public final long downloadedAt;
    public final int chunkCount;
    public final String thumb;
    public final String origin;

    public VaultEntry(String objectId, String wrappedDek, String name, long size,
                      String mime, long downloadedAt, int chunkCount, String thumb,
                      String origin) {
        this.objectId = objectId;
        this.wrappedDek = wrappedDek;
        this.name = name;
        this.size = size;
        this.mime = mime;
        this.downloadedAt = downloadedAt;
        this.chunkCount = chunkCount;
        this.thumb = thumb;
        this.origin = origin;
    }
}
