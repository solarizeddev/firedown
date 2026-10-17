# Strips every android.util.Log call from release builds: logs can carry URLs
# and titles, and release builds must be silent (CLAUDE.md, "Logging
# discipline"). Applied to every release build except the diagnostic one
# (-Pfiredown.diagnostic=true, see app/build.gradle), which exists to keep them.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}
