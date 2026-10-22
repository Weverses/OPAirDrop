package com.tlsu.oshareairdrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Main-thread sender UI; native cards and fallback notifications share the same session. */
final class OShareSendPresentation {
    private static final String CHANNEL = "opairshare_send";
    private static final String CANCEL = "com.tlsu.oshareairdrop.CANCEL_OUTGOING";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LinkedHashMap<String, Task> TASKS = new LinkedHashMap<>();
    private static Context app;
    private static OShareNativeTransferPresenter nativePresenter;
    private static boolean notificationCancelAvailable;
    private static int nextNotification = 20000;
    private static final class Task {
        final long id;
        final SendUiState state;
        final int notification = nextNotification++;
        final String name, contents;
        final Intent panel;
        String message = "";
        Task(long id, String session, String name, int files, Intent panel) {
            this.id = id; state = new SendUiState(session); this.name = name;
            contents = files == 0 ? "文字或链接" : files + " 个文件";
            this.panel = panel;
        }
    }

    static void install(Entry module, Context context, ClassLoader loader) {
        if (app != null) return;
        app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (CANCEL.equals(intent.getAction())) cancel(intent.getStringExtra("session"));
                }
            }, new IntentFilter(CANCEL), Context.RECEIVER_NOT_EXPORTED);
            notificationCancelAvailable = true;
        } catch (Throwable failure) {
            module.log(Log.WARN, Entry.TAG, "Sender notification cancel unavailable", failure);
        }
        new Thread(() -> {
            OShareNativeTransferPresenter prepared = OShareNativeTransferPresenter.installSending(module, app, loader,
                    new OShareNativeTransferPresenter.Host() {
                        public void changed(String session) { Task task = TASKS.get(session); if (task != null) notifyTask(task); }
                        public void open(String session) {
                            Task task = TASKS.get(session);
                            if (task != null && task.panel != null) try { app.startActivity(task.panel); }
                            catch (Throwable failure) { Log.w(Entry.TAG, "sender panel unavailable", failure); }
                        }
                        public boolean command(String session, String action) { return "cancel".equals(action) && cancel(session); }
                    });
            MAIN.post(() -> {
                nativePresenter = prepared;
                for (Task task : new ArrayList<>(TASKS.values())) if (!task.state.terminal) render(task);
            });
        }, "OShare-SenderInit").start();
    }

    static void begin(long id, String session, String name, int files, Intent panel) {
        for (Task previous : new ArrayList<>(TASKS.values())) if (previous.id == id) {
            if (nativePresenter != null) nativePresenter.dismiss(previous.state.session);
            cancelNotification(previous);
            TASKS.remove(previous.state.session);
        }
        Task task = new Task(id, session, name, files, panel);
        TASKS.put(session, task);
        render(task);
        // Only completed history is bounded; active concurrent transfers retain their UI.
        for (Task previous : new ArrayList<>(TASKS.values())) if (TASKS.size() > 64 && previous.state.terminal) {
            if (nativePresenter != null) nativePresenter.dismiss(previous.state.session);
            cancelNotification(previous); TASKS.remove(previous.state.session);
        }
    }

    /** Returns false for an event belonging to an older submission to the same peer. */
    static boolean event(JSONObject event) {
        String token = event.optString("session");
        Task task = TASKS.get(token);
        if (task == null || task.id != event.optLong("id")) return false;
        String type = event.optString("ev");
        if (task.state.terminal) return false;
        if (type.equals("transfer")) {
            if (!task.state.update(token, event.optInt("status"), event.optDouble("progress"), event.optBoolean("terminal"))) return false;
        } else if (type.equals("send_requested")) { if (!task.state.submitted()) return false; }
        else if (type.equals("error")) {
            if ("cancel".equals(event.optString("cmd"))) task.state.cancelling = false;
            else task.state.update(token, 1007, task.state.progress, true);
            task.message = event.optString("message");
        } else return true;
        render(task);
        return true;
    }

    static void disconnected() {
        for (Task task : new ArrayList<>(TASKS.values())) if (!task.state.terminal) {
            task.state.update(task.state.session, 1007, task.state.progress, true);
            task.message = "AirDrop 服务连接中断，请重新发送";
            render(task);
        }
    }

    static boolean cancel(String session) {
        Task task = TASKS.get(session);
        if (task == null || task.state.terminal || task.state.cancelling) return false;
        if (!OShareBridge.cancelOutgoing(task.id, session)) return false;
        task.state.cancelling = true; task.message = ""; render(task);
        return true;
    }

    static boolean cancelTarget(long id) {
        for (Task task : TASKS.values()) if (task.id == id && !task.state.terminal) {
            cancel(task.state.session);
            return true;
        }
        return false;
    }

    static SendUiState activeState(long id) {
        for (Task task : TASKS.values()) if (task.id == id && !task.state.terminal) return task.state;
        return null;
    }

    private static void render(Task task) {
        notifyTask(task);
        if (nativePresenter == null) return;
        try {
            nativePresenter.update(new JSONObject().put("session", task.state.session)
                    .put("phase", task.state.cancelling ? "cancelling" : "sending")
                    .put("status", task.state.status).put("terminal", task.state.terminal)
                    .put("progress", task.state.progress).put("capsule", task.state.capsule()),
                    task.notification, "AirDrop · " + task.state.label(), summary(task));
        } catch (Exception failure) { Log.w(Entry.TAG, "sender presentation failed", failure); }
    }

    private static String summary(Task task) {
        return "发送至 " + task.name + "\n" + task.contents + (task.message.isEmpty() ? "" : "\n" + task.message);
    }

    private static void notifyTask(Task task) {
        if (app == null) return;
        try {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(CHANNEL, "AirDrop 发送", NotificationManager.IMPORTANCE_LOW);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            manager.createNotificationChannel(channel);
            Notification.Builder builder = new Notification.Builder(app, CHANNEL)
                    .setSmallIcon(app.getApplicationInfo().icon != 0 ? app.getApplicationInfo().icon : android.R.drawable.stat_sys_upload)
                    .setContentTitle("AirDrop · " + task.state.label()).setContentText(summary(task))
                    .setStyle(new Notification.BigTextStyle().bigText(summary(task)))
                    .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setCategory(Notification.CATEGORY_PROGRESS).setOngoing(!task.state.terminal).setAutoCancel(task.state.terminal);
            if (task.panel != null) builder.setContentIntent(PendingIntent.getActivity(app, task.notification, task.panel,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            if (!task.state.terminal) {
                builder.setProgress(100, task.state.progress, task.state.status != 1005);
                if (!task.state.cancelling && notificationCancelAvailable) {
                    Intent cancel = new Intent(CANCEL).setPackage(app.getPackageName())
                            .setData(android.net.Uri.parse("opairshare://send/" + task.state.session))
                            .putExtra("session", task.state.session);
                    PendingIntent action = PendingIntent.getBroadcast(app, task.notification, cancel,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    builder.addAction(new Notification.Action.Builder(null, "取消", action)
                            .setAuthenticationRequired(true).build());
                }
            }
            manager.notify(CHANNEL, task.notification, builder.build());
        } catch (Throwable failure) { Log.w(Entry.TAG, "sender notification unavailable", failure); }
    }

    private static void cancelNotification(Task task) {
        if (app == null) return;
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(CHANNEL, task.notification);
    }
}
