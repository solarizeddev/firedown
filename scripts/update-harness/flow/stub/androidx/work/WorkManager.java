package androidx.work;
/** Records unique work; the harness runs workers itself. */
public class WorkManager {
    public static final WorkManager INSTANCE = new WorkManager();
    public final java.util.Map<String, OneTimeWorkRequest> unique = new java.util.LinkedHashMap<>();
    public final java.util.List<String> log = new java.util.ArrayList<>();
    public static WorkManager getInstance(android.content.Context c) { return INSTANCE; }
    public final java.util.List<String> cancelled = new java.util.ArrayList<>();
    public Object cancelUniqueWork(String name) {
        cancelled.add(name);
        unique.remove(name);
        return null;
    }
    public Object enqueueUniqueWork(String name, ExistingWorkPolicy policy, OneTimeWorkRequest r) {
        log.add(name + " " + policy);
        if (policy == ExistingWorkPolicy.KEEP && unique.containsKey(name)) { return null; }
        unique.put(name, r);
        return null;
    }
}
