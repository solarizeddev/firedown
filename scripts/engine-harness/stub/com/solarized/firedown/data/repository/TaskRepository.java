package com.solarized.firedown.data.repository;
import com.solarized.firedown.data.TaskEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/** Records what the engine publishes to the UI layer. */
public class TaskRepository {
    public static final class Count { public final int regular, safe; Count(int r, int s) { regular = r; safe = s; } }
    public final List<Count> counts = Collections.synchronizedList(new ArrayList<>());
    public final List<TaskEvent> events = Collections.synchronizedList(new ArrayList<>());
    public void updateCount(int regular, int safe) { counts.add(new Count(regular, safe)); }
    public void sendEvent(TaskEvent e) { events.add(e); }
    public Count lastCount() { return counts.isEmpty() ? null : counts.get(counts.size() - 1); }
}
