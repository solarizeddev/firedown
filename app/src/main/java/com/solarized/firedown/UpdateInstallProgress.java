package com.solarized.firedown;

/**
 * What tapping Install is doing right now, for every surface that shows it
 * (the update sheet, the update notification, the About row). Pure Java: the
 * transitions and their guards live here, {@link UpdateInstaller} publishes the
 * snapshots on the main thread.
 *
 * <p>The phases follow what the user waits on: PREPARING while Firedown copies
 * the APK into Android's install session (a percent, because the bytes are
 * counted), INSTALLING once the session is committed and Android has it (no
 * percent: how long Android takes is not ours to know), then IDLE when Android
 * answers (its confirmation dialog, or the app being replaced) or FAILED.
 *
 * <p>ONE install at a time: {@link #begin} claims the slot and every later
 * transition names the install it belongs to ({@code generation}), so a late
 * callback from an earlier install can never move a newer one.
 */
public final class UpdateInstallProgress {

    public enum Phase { IDLE, PREPARING, INSTALLING, FAILED }

    public static final class Snapshot {
        public static final Snapshot IDLE = new Snapshot(Phase.IDLE, -1, null, 0, false);

        public final Phase phase;
        /** 0–100 while PREPARING, -1 otherwise. */
        public final int percent;
        public final String versionName;
        /** Which install this is; 0 before the first one. */
        public final long generation;
        /** Started from the update notification, which then shows the progress. */
        public final boolean fromNotification;

        public Snapshot(Phase phase, int percent, String versionName, long generation,
                        boolean fromNotification) {
            this.phase = phase;
            this.percent = percent;
            this.versionName = versionName;
            this.generation = generation;
            this.fromNotification = fromNotification;
        }

        public boolean busy() {
            return phase == Phase.PREPARING || phase == Phase.INSTALLING;
        }
    }

    private Phase mPhase = Phase.IDLE;
    private int mPercent = -1;
    private String mName;
    private long mGeneration;
    private boolean mFromNotification;

    public synchronized Snapshot current() {
        return snapshot();
    }

    /** Claims the install slot; null while another install is running. */
    public synchronized Snapshot begin(String versionName, boolean fromNotification) {
        if (busy()) {
            return null;
        }
        mGeneration++;
        mPhase = Phase.PREPARING;
        mPercent = 0;
        mName = versionName;
        mFromNotification = fromNotification;
        return snapshot();
    }

    /**
     * Bytes copied into the session. A snapshot only when the whole percent
     * moved, so a 205 MB copy publishes at most 101 times.
     */
    public synchronized Snapshot copied(long generation, long done, long total) {
        if (generation != mGeneration || mPhase != Phase.PREPARING) {
            return null;
        }
        int percent = percentOf(done, total);
        if (percent == mPercent) {
            return null;
        }
        mPercent = percent;
        return snapshot();
    }

    /** The session is being committed: Android has the APK from here on. */
    public synchronized Snapshot committed(long generation) {
        if (generation != mGeneration || mPhase != Phase.PREPARING) {
            return null;
        }
        mPhase = Phase.INSTALLING;
        mPercent = -1;
        return snapshot();
    }

    /**
     * Firedown's part failed (no file, no space, the session refused). The
     * commit itself can throw after {@link #committed}, so INSTALLING counts.
     */
    public synchronized Snapshot failed(long generation) {
        if (generation != mGeneration || !busy()) {
            return null;
        }
        mPhase = Phase.FAILED;
        mPercent = -1;
        return snapshot();
    }

    /** Android answered: its dialog is up, or the install finished. */
    public synchronized Snapshot answered() {
        if (mPhase != Phase.INSTALLING) {
            return null;
        }
        mPhase = Phase.IDLE;
        return snapshot();
    }

    /**
     * Android refused the committed session. Only an install still waiting
     * counts: a refusal after Android's dialog was shown (the user cancelling
     * it) arrives when this install is already IDLE and changes nothing.
     */
    public synchronized Snapshot refused() {
        if (mPhase != Phase.INSTALLING) {
            return null;
        }
        mPhase = Phase.FAILED;
        return snapshot();
    }

    /** Android said nothing for too long; stop showing the wait. */
    public synchronized Snapshot timedOut(long generation) {
        if (generation != mGeneration || mPhase != Phase.INSTALLING) {
            return null;
        }
        mPhase = Phase.IDLE;
        return snapshot();
    }

    static int percentOf(long done, long total) {
        if (total <= 0 || done <= 0) {
            return 0;
        }
        if (done >= total) {
            return 100;
        }
        return (int) (done * 100 / total);
    }

    private boolean busy() {
        return mPhase == Phase.PREPARING || mPhase == Phase.INSTALLING;
    }

    private Snapshot snapshot() {
        return new Snapshot(mPhase, mPercent, mName, mGeneration, mFromNotification);
    }
}
