package com.solarized.firedown.sync;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;

/**
 * A sequential {@link InputStream} over a {@link VaultObjectReader} — decrypting
 * each vault chunk on demand — so a backed-up cloud object can be fed to any
 * stream consumer (notably Glide's image decoders) WITHOUT restoring it to disk
 * first. Closing the stream closes the reader (wiping the DEK), so it owns the
 * reader's lifetime; callers hand it off and forget it.
 */
public final class VaultObjectInputStream extends InputStream {

    private final VaultObjectReader reader;
    private final byte[] one = new byte[1];
    private long pos;
    private boolean closed;

    public VaultObjectInputStream(VaultObjectReader reader) {
        this.reader = reader;
    }

    @Override
    public int read() throws IOException {
        int n = read(one, 0, 1);
        return n < 0 ? -1 : (one[0] & 0xFF);
    }

    @Override
    public int read(@NonNull byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("stream closed");
        }
        if (len == 0) {
            return 0;
        }
        int n = reader.readAt(pos, b, off, len);
        if (n < 0) {
            return -1;
        }
        pos += n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        long remaining = reader.length() - pos;
        long advanced = Math.min(n, Math.max(0, remaining));
        pos += advanced;
        return advanced;
    }

    @Override
    public int available() {
        long remaining = reader.length() - pos;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, remaining));
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            reader.close();
        }
    }
}
