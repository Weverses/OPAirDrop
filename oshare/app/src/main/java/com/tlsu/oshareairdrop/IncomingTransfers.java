package com.tlsu.oshareairdrop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Protocol requests own tokens independent of peer identity and UI lifetime. */
public final class IncomingTransfers<T> {
    public static final class Task<T> {
        public final long id;
        public final String session;
        public final long created;
        public volatile T target;
        public volatile int status = 1002;
        public volatile double progress;
        public volatile String phase = "pending";
        public volatile String message = "";
        public volatile boolean terminal;
        private Task(long id, String session, long now, T target) {
            this.id = id; this.session = session; this.created = now; this.target = target;
        }
    }

    private final String generation = UUID.randomUUID().toString();
    private long sequence;
    private long highestId = Long.MIN_VALUE;
    private final LinkedHashMap<Long, Task<T>> tasks = new LinkedHashMap<>();

    public synchronized Task<T> request(long id, T target, long now) {
        Task<T> existing = tasks.get(id);
        if (existing != null) return existing;
        // Mosey 60715 assigns monotonically increasing per-ASK IDs (cfm/cfl).
        // Retain a high-watermark even after UI history is evicted.
        if (id <= highestId) throw new IllegalStateException("接收请求已过期");
        if (busy()) throw new IllegalStateException("另一个接收任务尚未结束");
        Task<T> task = new Task<>(id, generation + ":" + (++sequence), now, target);
        tasks.put(id, task);
        highestId = id;
        // Bound retained UI history. IDs removed here must never be used for a command
        // without their new generation token; unknown non-invitation updates are ignored.
        while (tasks.size() > 64) {
            Long first = tasks.keySet().iterator().next();
            if (!tasks.get(first).terminal) break;
            tasks.remove(first);
        }
        return task;
    }

    public synchronized Task<T> current(long id) { return tasks.get(id); }

    public synchronized Task<T> require(long id, String session) {
        Task<T> task = tasks.get(id);
        if (task == null || !task.session.equals(session) || task.terminal)
            throw new IllegalStateException("接收请求已失效");
        return task;
    }

    public synchronized Task<T> accept(long id, String session) {
        Task<T> task = require(id, session);
        if (!"pending".equals(task.phase)) throw new IllegalStateException("接收请求已处理");
        task.phase = "accepting";
        return task;
    }

    public synchronized boolean update(Task<T> task, T target, int status, double progress,
            boolean finalFlag) {
        if (tasks.get(task.id) != task || task.terminal || "publishing".equals(task.phase)) return false;
        // A delayed invitation must not restore Accept after the transfer began.
        if (status == 1002 && !"pending".equals(task.phase) && !"accepting".equals(task.phase)) return false;
        task.target = target;
        task.status = status;
        task.progress = OutgoingTransfers.progress(progress);
        if (status == 1006) {
            task.phase = "publishing";
        } else if (finalFlag || isFailure(status)) {
            finish(task, status == 1008 ? "rejected" : status == 1009 || status == 1018
                    ? "cancelled" : status == 1010 ? "expired" : "failed", "");
        } else if (status == 1017) {
            task.phase = "verifying";
        } else if (status != 1002) {
            task.phase = "receiving";
        }
        return true;
    }

    public synchronized void finish(Task<T> task, String phase, String message) {
        if (tasks.get(task.id) != task || task.terminal) return;
        task.phase = phase; task.message = message; task.terminal = true;
    }

    public synchronized boolean active(long id) {
        Task<T> task = tasks.get(id);
        return task != null && !task.terminal;
    }

    public synchronized boolean busy() {
        return tasks.values().stream().anyMatch(t -> !t.terminal);
    }

    public synchronized List<Task<T>> snapshot() { return new ArrayList<>(tasks.values()); }

    private static boolean isFailure(int status) {
        switch (status) {
            case 1004: case 1007: case 1008: case 1009: case 1010: case 1011:
            case 1013: case 1014: case 1015: case 1018: case 1020: case 1025: case 1029:
                return true;
            default: return false;
        }
    }
}
