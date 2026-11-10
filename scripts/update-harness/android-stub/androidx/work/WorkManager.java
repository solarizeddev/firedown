package androidx.work;
public abstract class WorkManager {
    public static WorkManager getInstance(android.content.Context c) { return null; }
    public abstract Object enqueueUniqueWork(String name, ExistingWorkPolicy p, OneTimeWorkRequest r);
    public abstract Object enqueueUniquePeriodicWork(String name, ExistingPeriodicWorkPolicy p, PeriodicWorkRequest r);
    public abstract Object cancelUniqueWork(String name);
}
