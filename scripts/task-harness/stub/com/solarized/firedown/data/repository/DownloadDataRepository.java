package com.solarized.firedown.data.repository;
import com.solarized.firedown.data.entity.DownloadEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/** Records every row the tasks write (a snapshot, and the writing thread). */
public class DownloadDataRepository {
    public final List<DownloadEntity> added = Collections.synchronizedList(new ArrayList<>());
    public final List<String> threads = Collections.synchronizedList(new ArrayList<>());
    public void addSync(DownloadEntity e) {
        added.add(new DownloadEntity(e));
        threads.add(Thread.currentThread().getName());
    }
    public void clear() { added.clear(); threads.clear(); }
}
