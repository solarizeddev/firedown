package android.util;
/** android.util.Base64 over java.util.Base64 — only the flags the sabr package uses. */
public class Base64 {
    public static final int DEFAULT = 0, NO_WRAP = 2, URL_SAFE = 8, NO_PADDING = 1;
    public static byte[] decode(String s, int flags) {
        if (s == null) return new byte[0];
        return ((flags & URL_SAFE) != 0 ? java.util.Base64.getUrlDecoder()
                                        : java.util.Base64.getMimeDecoder()).decode(pad(s, flags));
    }
    public static String encodeToString(byte[] b, int flags) {
        java.util.Base64.Encoder e = (flags & URL_SAFE) != 0
                ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) e = e.withoutPadding();
        return e.encodeToString(b);
    }
    private static String pad(String s, int flags) {
        String t = s.trim();
        int m = t.length() % 4;
        if (m == 0) return t;
        StringBuilder sb = new StringBuilder(t);
        for (int i = m; i < 4; i++) sb.append('=');
        return sb.toString();
    }
}
