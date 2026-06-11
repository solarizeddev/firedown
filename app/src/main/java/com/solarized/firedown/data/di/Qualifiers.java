package com.solarized.firedown.data.di;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Qualifier;

/**
 * Custom Hilt Qualifiers to distinguish between different types of Executors.
 */
public class Qualifiers {

    /**
     * Used for background tasks like Disk I/O or Database operations.
     *
     * <p>SHORT, BOUNDED work only — this is a single shared thread and the
     * Downloads list (among others) depends on it for timely DB mutations.
     * Anything that can run long or block on the outside world (recursive
     * cache sweeps, full-mirror writes, FFmpeg probes) goes on {@link HeavyIO}
     * instead: a delete queued behind a minutes-long job here looked exactly
     * like "delete does nothing" in the UI, because Room invalidation only
     * fires when the DELETE actually runs.</p>
     */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface DiskIO {}

    /**
     * Used for long-running / unbounded background jobs (cache folder sweep,
     * backup-mirror rewrite, FFmpeg metadata probes). Serialized on its own
     * thread so they can only starve each other — never the short DB
     * mutations on {@link DiskIO}.
     */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface HeavyIO {}

    /**
     * Used for operations that must run on the UI/Main thread.
     */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface MainThread {}


    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Network {}


    /**
     * Used for autoComplete operations
     */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AutoComplete {}

    public @interface AppVersion {
    }
}