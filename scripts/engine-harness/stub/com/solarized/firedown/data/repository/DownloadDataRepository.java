package com.solarized.firedown.data.repository;
import com.solarized.firedown.data.entity.DownloadEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
/**
 * Recording repository. The real one snapshots the entity and writes on a
 * disk executor; here the snapshot is kept so the harness can read the
 * status HISTORY per id (the order of writes is part of what is asserted).
 */
public class DownloadDataRepository {
    public final Map<Integer, List<DownloadEntity>> history = Collections.synchronizedMap(new LinkedHashMap<>());
    public final List<DownloadEntity> deleted = Collections.synchronizedList(new ArrayList<>());
    public final List<List<DownloadEntity>> batchDeletes = Collections.synchronizedList(new ArrayList<>());
    /** Rows the harness pretends already exist in the DB, keyed by path (the errored-row-owns-path case). */
    public final Map<String, DownloadEntity> byPath = Collections.synchronizedMap(new LinkedHashMap<>());
    public void add(DownloadEntity e) {
        DownloadEntity snap = new DownloadEntity(e);
        history.computeIfAbsent(snap.getId(), k -> Collections.synchronizedList(new ArrayList<>())).add(snap);
    }
    public DownloadEntity latest(int id) {
        List<DownloadEntity> h = history.get(id);
        return h == null || h.isEmpty() ? null : h.get(h.size() - 1);
    }
    public List<Integer> statuses(int id) {
        List<Integer> out = new ArrayList<>();
        List<DownloadEntity> h = history.get(id);
        if (h != null) synchronized (h) { for (DownloadEntity e : h) out.add(e.getFileStatus()); }
        return out;
    }
    public DownloadEntity findByFilePath(String path) { return byPath.get(path); }
    public void deleteDownload(DownloadEntity e) { deleted.add(new DownloadEntity(e)); }
    public void deleteDownloads(List<DownloadEntity> list, Consumer<ArrayList<DownloadEntity>> onComplete) {
        batchDeletes.add(new ArrayList<>(list));
        if (onComplete != null) onComplete.accept(new ArrayList<>());
    }
}
