package androidx.core.app;
public final class NotificationManagerCompat {
    public static NotificationManagerCompat from(android.content.Context c) { return null; }
    @android.annotation.RequiresPermission("android.permission.POST_NOTIFICATIONS")
    public void notify(int id, android.app.Notification n) {}
    public void cancel(int id) {}
}
