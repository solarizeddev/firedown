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
     */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface DiskIO {}

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