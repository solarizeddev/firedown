package simapi;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * One simulated phone, as the driver sees it. The implementation
 * ({@code com.solarized.firedown.sync.SimPhone}) is loaded by its OWN class
 * loader, child-first, together with its own copy of every real backup class,
 * OkHttp and the stubs: two phones share no static state (the S3 clock, the
 * BackupStores cache, the settings generations), the way two processes on two
 * phones would not. Only JDK types and this package cross the boundary.
 */
public interface Phone {

    String name();

    /** Picks an S3 bucket through the real S3Check, saves it with the real
     *  S3Settings and makes it the place (BackupStores.choose). */
    void useS3(String endpoint, String bucket, String prefix, String keyId, String secret)
            throws Exception;

    /** Signs in to a WebDAV server through the real WebDavCheck, saves it and
     *  makes it the place. */
    void useDav(String url, String user, String password) throws Exception;

    /** The shared recovery code (32 bytes). */
    void setCode(byte[] code);

    /** The account directory name the code derives. */
    String account();

    /** This phone's clock against the real one (S3Client.phoneClock). */
    void setClockOffset(long millis);

    /** Backs up {@code size} bytes read from {@code source} as file
     *  {@code name} through the real VaultEngine; returns the object id. */
    String backup(String name, long size, Callable<InputStream> source, String thumb,
                  String origin) throws Exception;

    /** Asks every backup this phone runs to stop at its next chunk. */
    void cancelBackups();

    /** The committed entries: objectId, name, size, thumb, origin,
     *  backedUpAt, chunkCount. */
    List<Map<String, Object>> list() throws Exception;

    /** Restores one entry (looked up in a fresh listing) and returns its bytes. */
    byte[] restore(String objectId) throws Exception;

    /** Restores an entry from metadata the caller holds (no listing). */
    byte[] restore(Map<String, Object> entry) throws Exception;

    /** Opens a streaming reader over one entry (looked up in a listing). */
    Reader open(String objectId) throws Exception;

    /** Opens a streaming reader from metadata the caller holds: no listing
     *  first, so the store's first request is the reader's own HEAD. */
    Reader open(Map<String, Object> entry) throws Exception;

    /** Deletes one entry (sidecar, then object), looked up in a listing. */
    void delete(String objectId) throws Exception;

    /** Deletes one entry from metadata the caller holds (no listing). */
    void delete(Map<String, Object> entry) throws Exception;

    /** Deletes everything the account holds. */
    void deleteAll() throws Exception;

    /** S3 only: runs the stale-upload sweep now; returns how many it aborted. */
    int sweepUploadsNow();

    /** The failure class of something this phone threw: refused:AUTH,
     *  refused:DENIED, unreachable, full, notfound, server:404:NoSuchUpload,
     *  io, crypto, or the class name. */
    String describe(Throwable t);

    /** A positional reader over one backup, as the stream player uses it. */
    interface Reader {
        long length();

        int readAt(long pos, byte[] buf, int off, int len) throws Exception;

        void close();
    }
}
