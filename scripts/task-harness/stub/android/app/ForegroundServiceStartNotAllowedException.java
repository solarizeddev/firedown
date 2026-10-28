package android.app;
/** Android 12+: startForeground refused (app in the background, quota spent). */
public class ForegroundServiceStartNotAllowedException extends IllegalStateException {
    public ForegroundServiceStartNotAllowedException(String m) { super(m); }
}
