package com.solarized.firedown.manager.tasks;

import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.repository.TaskRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Same package as TaskManager: does what Hilt does (field injection into the
 * package-private @Inject fields) and reads the private static state the
 * harness reports on. Nothing here changes behaviour.
 */
public final class HarnessAccess {

    private HarnessAccess() {}

    /** What Hilt's generated injector does when Android creates the service. */
    public static TaskManager newTaskManager(DownloadDataRepository downloads, TaskRepository tasks) {
        TaskManager m = new TaskManager();
        m.mDownloadRepository = downloads;
        m.mTaskRepository = tasks;
        return m;
    }

    public static String cancelCurrentAction() {
        try {
            Field f = TaskManager.class.getDeclaredField("ACTION_CANCEL_CURRENT");
            f.setAccessible(true);
            return (String) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TaskManager.ACTION_CANCEL_CURRENT is gone", e);
        }
    }

    /** sCurrentRunnable, static in the real code; read from {@code instance}
     *  when a mutant made it an instance field. */
    public static Object currentRunnable(Object instance) {
        try {
            Field f = TaskManager.class.getDeclaredField("sCurrentRunnable");
            f.setAccessible(true);
            if (Modifier.isStatic(f.getModifiers())) return f.get(null);
            return instance != null ? f.get(instance) : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static boolean currentRunnableIsStatic() {
        try {
            return Modifier.isStatic(TaskManager.class.getDeclaredField("sCurrentRunnable").getModifiers());
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public static boolean isStopped(Object taskRunnable) {
        return taskRunnable instanceof TaskRunnable t && t.isStopped();
    }
}
