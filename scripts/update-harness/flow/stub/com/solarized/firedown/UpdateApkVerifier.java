package com.solarized.firedown;
/** The real SHA-256 check; the signature check is the harness's switch (PackageManager isn't on a JVM). */
public final class UpdateApkVerifier {
    public static boolean signatureOk = true;
    public static boolean verifySha256(java.io.File apk, String expectedHex) {
        try { return expectedHex != null && com.solarized.firedown.update.UpdatePatch.sha256Hex(apk, null).equalsIgnoreCase(expectedHex); }
        catch (java.io.IOException e) { return false; }
    }
    public static boolean verifySignature(android.content.Context c, java.io.File apk) { return signatureOk && apk.isFile(); }
}
