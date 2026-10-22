package com.tlsu.oshareairdrop;

import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs protocol state on BridgeMessenger's worker; file I/O has its own executor. */
final class IncomingReceiver {
    private static final IncomingTransfers<Object> TASKS = new IncomingTransfers<>();
    private static final Map<String, JSONArray> FILES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, JSONArray> PUBLISHED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Cleanup> CLEANUP = new java.util.concurrent.ConcurrentHashMap<>();
    private static final class Cleanup {
        final String method, phase, message;
        long attempted;
        Cleanup(String method, String phase, String message) { this.method = method; this.phase = phase; this.message = message; }
    }
    private static final java.util.Set<String> RETRYABLE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ExecutorService FILE_IO = Executors.newSingleThreadExecutor();
    private IncomingReceiver() {}

    static boolean busy() { return TASKS.busy(); }
    static boolean active(long id) { return TASKS.active(id); }
    static boolean ownsFile(long attachmentId) {
        for (IncomingTransfers.Task<Object> task : TASKS.snapshot()) {
            if (task.terminal) continue;
            JSONArray files = FILES.get(task.session);
            if (files != null) for (int i = 0; i < files.length(); i++)
                if (files.optJSONObject(i) != null && files.optJSONObject(i).optLong("attachmentId", Long.MIN_VALUE) == attachmentId)
                    return true;
        }
        return false;
    }

    static void onTransfer(Object target, Object metadata) {
        BridgeMessenger.execute(() -> update(target, metadata));
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void update(Object target, Object metadata) {
        IncomingTransfers.Task<Object> task = null;
        try {
            long id = ((Number) field(target, "a")).longValue();
            int status = ((Number) field(metadata, "a")).intValue();
            task = TASKS.current(id);
            if (task == null) {
                if (status != 1002) return;
                if (!ReceiveController.admitting()) { ProviderApi.receiveAction("c", target); return; }
                try { task = TASKS.request(id, target, SystemClock.elapsedRealtime()); }
                catch (IllegalStateException busy) { ProviderApi.receiveAction("c", target); return; }
                try { FILES.put(task.session, ReceiveFiles.attachments(target)); }
                catch (Exception unsupported) {
                    cleanup(task, "c", "failed", unsupported.getMessage());
                    return;
                }
            }
            if (task.terminal) return;
            boolean finalFlag = Boolean.TRUE.equals(field(metadata, "e"));
            double progress = ((Number) field(metadata, "b")).doubleValue();
            if (!TASKS.update(task, target, status, progress, finalFlag)) return;
            if (status == 1006) {
                FILES.put(task.session, ReceiveFiles.attachments(target));
                emit(task);
                publish(task);
            } else emit(task);
            trim();
        } catch (Throwable failure) {
            Log.e(Entry.TAG, "incoming callback failed", failure);
            if (task != null && !task.terminal) {
                cleanup(task, "b", "failed", "无法处理接收更新：" + failure.getMessage());
            }
        }
    }

    static void action(String action, long id, String session) {
        IncomingTransfers.Task<Object> task = TASKS.require(id, session);
        if ("accept".equals(action)) {
            if (!ReceiveController.acceptingExisting()) throw new IllegalStateException("接收已关闭或界面已断开");
            if (SystemClock.elapsedRealtime() - task.created > 60_000)
                throw new IllegalStateException("接收邀请已超时");
            TASKS.accept(id, session);
            try { ProviderApi.receiveAction("a", task.target); }
            catch (RuntimeException failure) {
                cleanup(task, "b", "failed", failure.getMessage()); throw failure;
            }
            emit(task);
        } else if ("deny".equals(action)) {
            if (!"pending".equals(task.phase)) throw new IllegalStateException("任务已接受，请使用取消");
            cleanup(task, "c", "rejected", "已拒绝接收");
        } else {
            if ("publishing".equals(task.phase)) throw new IllegalStateException("文件正在保存，请等待完成");
            cleanup(task, "b", "cancelled", "已取消接收");
        }
    }

    private static void publish(IncomingTransfers.Task<Object> task) {
        JSONArray sources = FILES.get(task.session);
        FILE_IO.execute(() -> {
            JSONArray published = null;
            Throwable error = null;
            try { published = ReceiveFiles.publish(HostContext.get(), sources); }
            catch (Throwable failure) { error = failure; }
            JSONArray output = published;
            Throwable failure = error;
            BridgeMessenger.execute(() -> {
                if (output != null) {
                    RETRYABLE.remove(task.session);
                    PUBLISHED.put(task.session, output);
                    TASKS.finish(task, "complete", "文件已保存");
                } else {
                    RETRYABLE.add(task.session);
                    TASKS.finish(task, "failed", "文件已收到，但保存失败：" + failure.getMessage());
                }
                emit(task);
            });
        });
    }

    static void retryPublish(long id, String session) {
        IncomingTransfers.Task<Object> task = TASKS.current(id);
        if (task == null || !task.session.equals(session) || !task.terminal
                || !RETRYABLE.contains(session) || task.status != 1006 || !"failed".equals(task.phase) || TASKS.busy())
            throw new IllegalStateException("无法重试此接收任务");
        task.terminal = false; task.phase = "publishing"; task.message = "";
        emit(task); publish(task);
    }

    private static void cleanup(IncomingTransfers.Task<Object> task, String method, String phase, String message) {
        task.phase = method.equals("c") ? "rejecting" : "cancelling";
        task.message = message;
        CLEANUP.put(task.session, new Cleanup(method, phase, message));
        advanceCleanup(task);
        emit(task);
    }

    private static void advanceCleanup(IncomingTransfers.Task<Object> task) {
        Cleanup cleanup = CLEANUP.get(task.session);
        if (cleanup == null) return;
        if (task.terminal) { CLEANUP.remove(task.session); return; }
        try {
            if (!ProviderApi.receiveExists(task.id)) {
                TASKS.finish(task, cleanup.phase, cleanup.message);
                CLEANUP.remove(task.session); emit(task); return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - cleanup.attempted >= 5000) {
                cleanup.attempted = now;
                ProviderApi.receiveAction(cleanup.method, task.target);
            }
        } catch (Throwable failure) {
            Log.w(Entry.TAG, "receive cleanup pending; retaining session", failure);
        }
    }

    static void tick(boolean admit) {
        long now = SystemClock.elapsedRealtime();
        for (IncomingTransfers.Task<Object> task : TASKS.snapshot()) {
            if (CLEANUP.containsKey(task.session)) { advanceCleanup(task); continue; }
            if (task.terminal || !"pending".equals(task.phase)) continue;
            if (admit && now - task.created < 60_000) continue;
            cleanup(task, "c", admit ? "expired" : "rejected", admit ? "接收邀请已超时" : "接收已关闭");
        }
    }

    static void snapshot() { for (IncomingTransfers.Task<Object> task : TASKS.snapshot()) emit(task); }

    private static void emit(IncomingTransfers.Task<Object> task) {
        try {
            JSONObject event = ProviderApi.targetJson(task.target);
            JSONArray published = PUBLISHED.get(task.session);
            JSONArray source = FILES.get(task.session);
            JSONArray display = new JSONArray();
            if (published != null) display = published;
            else if (source != null) for (int i = 0; i < source.length(); i++) {
                JSONObject file = source.getJSONObject(i);
                display.put(new JSONObject().put("name", file.getString("name"))
                        .put("size", file.optLong("size", -1)).put("mime", file.optString("mime")));
            }
            event.put("incoming", true).put("session", task.session).put("status", task.status)
                    .put("phase", task.phase).put("progress", task.progress).put("terminal", task.terminal)
                    .put("published", published != null).put("retryable", RETRYABLE.contains(task.session)).put("files", display).put("message", task.message);
            ProviderApi.emit("incoming_transfer", event);
        } catch (Exception failure) { Log.e(Entry.TAG, "incoming event failed", failure); }
    }

    private static void trim() {
        java.util.Set<String> sessions = new java.util.HashSet<>();
        for (IncomingTransfers.Task<Object> task : TASKS.snapshot()) sessions.add(task.session);
        FILES.keySet().retainAll(sessions); PUBLISHED.keySet().retainAll(sessions); CLEANUP.keySet().retainAll(sessions); RETRYABLE.retainAll(sessions);
    }
}
