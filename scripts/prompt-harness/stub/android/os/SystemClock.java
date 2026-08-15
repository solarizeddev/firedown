package android.os;
/** Settable fake clock so the harness can drain the flood window without sleeping. */
public class SystemClock {
    public static long now = 100_000;
    public static long uptimeMillis() { return now; }
}
