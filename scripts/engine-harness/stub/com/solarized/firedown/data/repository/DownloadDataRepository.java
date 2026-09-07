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
    /** Like Room: any row (not deleted) whose path matches — a finished or errored row still owns its path. */
    public DownloadEntity findByFilePath(String path) {
        DownloadEntity forced = byPath.get(path);
        if (forced != null) return forced;
        synchronized (history) {
            for (List<DownloadEntity> h : history.values()) {
                DownloadEntity e = h.isEmpty() ? null : h.get(h.size() - 1);
                if (e != null && path.equals(e.getFilePath()) && !isDeleted(e.getId())) return e;
            }
        }
        return null;
    }
    public boolean isDeleted(int id) {
        synchronized (deleted) { for (DownloadEntity d : deleted) if (d.getId() == id) return true; }
        synchronized (batchDeletes) { for (List<DownloadEntity> b : batchDeletes) for (DownloadEntity d : b) if (d.getId() == id) return true; }
        return false;
    }
    /** Like the real one: removes the row AND the file at the entity's path. */
    public void deleteDownload(DownloadEntity e) { deleteDownload(e, null); }
    public void deleteDownload(DownloadEntity e, Runnable onComplete) {
        deleted.add(new DownloadEntity(e));
        if (e.getFilePath() != null) new java.io.File(e.getFilePath()).delete();
        if (onComplete != null) onComplete.run();
    }
    public void deleteDownloads(List<DownloadEntity> list, Consumer<ArrayList<DownloadEntity>> onComplete) {
        batchDeletes.add(new ArrayList<>(list));
        for (DownloadEntity e : list) if (e.getFilePath() != null) new java.io.File(e.getFilePath()).delete();
        if (onComplete != null) onComplete.accept(new ArrayList<>());
    }
}
