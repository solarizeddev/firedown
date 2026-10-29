package android.app;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/** Everything NotificationCompat.Builder was given, readable by the harness. */
public class Notification {
    public static final class Action {
        public final int icon; public final CharSequence title; public final PendingIntent intent;
        public Action(int icon, CharSequence title, PendingIntent intent) { this.icon = icon; this.title = title; this.intent = intent; }
    }
    public String channelId;
    public int smallIcon;
    public CharSequence title;
    public CharSequence text;
    public PendingIntent contentIntent;
    public boolean ongoing, onlyAlertOnce, silent, showWhen = true;
    public int progressMax, progress;
    public boolean progressIndeterminate;
    public boolean hasProgress;
    public List<Action> actions = Collections.emptyList();
    public Notification() {}
    public Notification copyActions(List<Action> a) { actions = Collections.unmodifiableList(new ArrayList<>(a)); return this; }
    @Override public String toString() {
        return "Notification{" + title + " / " + text + ", icon=" + smallIcon + ", progress=" + (hasProgress ? (progressIndeterminate ? "indeterminate" : progress + "/" + progressMax) : "none") + ", actions=" + actions.size() + "}";
    }
}
