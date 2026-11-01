package android.util;
public final class Log {
    public static boolean quiet = true;
    private static int p(String l, String t, String m, Throwable e) { if (!quiet) { System.out.println("    [" + l + "/" + t + "] " + m + (e == null ? "" : " " + e)); } return 0; }
    public static int d(String t, String m) { return p("D", t, m, null); }
    public static int w(String t, String m) { return p("W", t, m, null); }
    public static int w(String t, String m, Throwable e) { return p("W", t, m, e); }
    public static int e(String t, String m) { return p("E", t, m, null); }
    public static int e(String t, String m, Throwable e) { return p("E", t, m, e); }
}
