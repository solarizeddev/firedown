package android.content;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/** The engine only ever asks for the application context and hands it on;
 *  DownloadIntents.start hands it the Intent a real app would send the service. */
public class Context {
    /** Every Intent startService was given, in order — the harness dispatches them itself. */
    public static final List<Intent> STARTED = Collections.synchronizedList(new ArrayList<>());
    /** The system refusing the start (the app in the background on Android 8+). */
    public static volatile boolean refuseStarts = false;
    public Context getApplicationContext() { return this; }
    public Object startService(Intent intent) {
        STARTED.add(intent);
        if (refuseStarts) throw new IllegalStateException("Not allowed to start service Intent");
        return null;
    }
}
