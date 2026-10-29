package android.os;

/** A clock the harness moves by hand. */
public final class SystemClock {
    private static long sNow = 1_000_000L;

    public static long uptimeMillis() {
        return sNow;
    }

    public static void advance(long ms) {
        sNow += ms;
    }
}
