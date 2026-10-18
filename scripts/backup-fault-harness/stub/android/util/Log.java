package android.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Harness stand-in for android.util.Log that CAPTURES every line, so the
 * secret sweep can assert that no key, password, Authorization value or
 * signature ever reached a log call (none of the backup classes under test
 * log today; this keeps that true if one starts to).
 */
public final class Log {
    public static final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    private Log() {
    }

    private static int record(String level, String tag, String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(level).append('/').append(tag).append(": ").append(msg);
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(" | ").append(c);
        }
        lines.add(sb.toString());
        return 0;
    }

    public static int v(String tag, String msg) {
        return record("V", tag, msg, null);
    }

    public static int d(String tag, String msg) {
        return record("D", tag, msg, null);
    }

    public static int i(String tag, String msg) {
        return record("I", tag, msg, null);
    }

    public static int w(String tag, String msg) {
        return record("W", tag, msg, null);
    }

    public static int w(String tag, String msg, Throwable t) {
        return record("W", tag, msg, t);
    }

    public static int e(String tag, String msg) {
        return record("E", tag, msg, null);
    }

    public static int e(String tag, String msg, Throwable t) {
        return record("E", tag, msg, t);
    }
}
