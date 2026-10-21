package com.tlsu.oshareairdrop;

import android.app.Activity;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.libxposed.api.XposedInterface.HookHandle;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Independent, session-scoped receive UI hosted by OShare's private dialog Activity. */
public final class OShareReceiveBridge {
    private static final String EXTRA_SESSION = "opairshare_receive_session";
    private static final String CHANNEL = "opairshare_receive";
    private static final String NOTIFICATION_TAG = "opairshare_receive";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Messenger CLIENT = new Messenger(new Handler(Looper.getMainLooper(), OShareReceiveBridge::receive));
    private static final LinkedHashMap<String, Task> TASKS = new LinkedHashMap<>();
    private static final Map<String, String> SUBMITTED = new LinkedHashMap<>();
    private static final Map<Activity, Screen> SCREENS = new java.util.WeakHashMap<>();
    private static final Map<Activity, Boolean> NATIVE = new java.util.WeakHashMap<>();
    private static final ArrayList<HookHandle> HOOKS = new ArrayList<>();
    private static Context app;
    private static Entry module;
    private static OShareReceiveResolver.Roles roles;
    private static Messenger server;
    private static WeakReference<Activity> visible = new WeakReference<>(null);
    private static boolean installed;
    private static String launching = "";
    private static int nextNotification = 10000;

    private OShareReceiveBridge() {}

    public static synchronized void install(Entry owner, Context context, ClassLoader loader) throws Exception {
        if (installed) return;
        OShareReceiveResolver.Roles resolved = OShareReceiveResolver.resolve(owner, context, loader);
        module = owner; roles = resolved;
        app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            HOOKS.add(owner.hook(roles.methods.get("onCreate")).intercept(chain -> {
                Activity activity = (Activity) chain.getThisObject();
                if (!tagged(activity.getIntent())) {
                    NATIVE.put(activity, true);
                    return chain.proceed();
                }
                // Call the real superclass lifecycle without running OShare's protocol observers,
                // notification bookkeeping, invitation timeout or singleton receive-state changes.
                owner.getInvoker(roles.supers.get("onCreate")).invokeSpecial(activity, chain.getArg(0));
                activity.setTitle("");
                Screen screen = new Screen(activity, activity.getIntent().getStringExtra(EXTRA_SESSION));
                SCREENS.put(activity, screen); visible = new WeakReference<>(activity); launching = "";
                screen.render();
                command("receive_snapshot", null);
                return null;
            }));
            HOOKS.add(owner.hook(roles.methods.get("onNewIntent")).intercept(chain -> {
                Activity activity = (Activity) chain.getThisObject();
                Intent intent = (Intent) chain.getArg(0);
                Screen screen = SCREENS.get(activity);
                if (screen == null) {
                    // singleInstance native receive owns this Activity; do not replace its Intent or UI.
                    if (tagged(intent)) {
                        launching = "";
                        Toast.makeText(activity, "OShare 正在处理另一项接收，请稍后从通知打开 AirDrop", Toast.LENGTH_LONG).show();
                        return null;
                    }
                    return chain.proceed();
                }
                owner.getInvoker(roles.supers.get("onNewIntent")).invokeSpecial(activity, intent);
                if (!tagged(intent)) {
                    // A genuine OShare invitation arrived while our isolated instance was alive.
                    // Recreate it through its native onCreate after this instance has been destroyed.
                    screen.nativeReplacement = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.finish(); return null;
                }
                String session = intent.getStringExtra(EXTRA_SESSION);
                Task current = TASKS.get(screen.session);
                Task next = TASKS.get(session);
                // A delayed terminal notification must not cover a newer active invitation.
                if (current != null && !current.terminal() && (next == null || next.terminal())) return null;
                activity.setIntent(intent); screen.session = session; screen.phase = "";
                launching = ""; screen.render();
                return null;
            }));
            HOOKS.add(owner.hook(roles.methods.get("onDestroy")).intercept(chain -> {
                Activity activity = (Activity) chain.getThisObject();
                NATIVE.remove(activity);
                Screen screen = SCREENS.remove(activity);
                if (screen == null) return chain.proceed();
                screen.dismiss();
                if (visible.get() == activity) visible.clear();
                owner.getInvoker(roles.supers.get("onDestroy")).invokeSpecial(activity);
                if (screen.nativeReplacement != null) {
                    Intent nativeIntent = screen.nativeReplacement;
                    MAIN.post(() -> { try { app.startActivity(nativeIntent); }
                        catch (Throwable failure) { log("native receive Activity restore failed", failure); } });
                }
                return null;
            }));
            for (String lifecycle : new String[]{"onSaveInstanceState", "onConfigurationChanged"}) {
                HOOKS.add(owner.hook(roles.methods.get(lifecycle)).intercept(chain -> {
                    Activity activity = (Activity) chain.getThisObject();
                    Screen screen = SCREENS.get(activity);
                    if (screen == null) return chain.proceed();
                    owner.getInvoker(roles.supers.get(lifecycle)).invokeSpecial(activity, chain.getArg(0));
                    if (lifecycle.equals("onConfigurationChanged")) { screen.phase = ""; screen.render(); }
                    return null;
                }));
            }
            HOOKS.add(owner.hook(roles.methods.get("render")).intercept(chain -> {
                Activity activity = (Activity) chain.getThisObject();
                Screen screen = SCREENS.get(activity);
                if (screen == null) return chain.proceed();
                screen.render(); return null;
            }));
            installed = true;
            MAIN.post(OShareReceiveBridge::maintainConnection);
            owner.log(Log.INFO, Entry.TAG, "OShare isolated receive UI hooks installed");
        } catch (Throwable failure) {
            for (HookHandle hook : HOOKS) try { hook.unhook(); } catch (Throwable ignored) {}
            HOOKS.clear();
            throw new IllegalStateException("OShare receive installation rolled back", failure);
        }
    }

    private static boolean tagged(Intent intent) {
        return intent != null && intent.getStringExtra(EXTRA_SESSION) != null
                && !intent.getStringExtra(EXTRA_SESSION).isEmpty();
    }

    private static void maintainConnection() {
        if (!installed) return;
        if (server == null || !server.getBinder().isBinderAlive()) {
            disconnected(server);
            try { BridgeMessenger.connect(app, CLIENT); }
            catch (Throwable failure) { log("receive background connection failed", failure); }
        }
        MAIN.postDelayed(OShareReceiveBridge::maintainConnection, server == null ? 5000 : 30000);
    }

    private static boolean receive(Message message) {
        if (message.what == BridgeMessenger.MSG_HELLO && message.replyTo != null) {
            Messenger connected = message.replyTo;
            if (server != null && !server.getBinder().equals(connected.getBinder())) disconnected(server);
            server = connected;
            try { connected.getBinder().linkToDeath(() -> MAIN.post(() -> disconnected(connected)), 0); }
            catch (Throwable ignored) { disconnected(connected); return true; }
            command("receive_ui_ready", null);
            command("receive_snapshot", null);
        } else if (message.what == BridgeMessenger.MSG_EVENT) {
            try { onEvent(new JSONObject(message.getData().getString(BridgeMessenger.EXTRA_JSON, "{}"))); }
            catch (Exception failure) { log("invalid receive event", failure); }
        }
        return true;
    }

    private static void disconnected(Messenger expected) {
        if (server != null && expected != null && !server.getBinder().equals(expected.getBinder())) return;
        server = null;
        for (Task task : new ArrayList<>(TASKS.values())) {
            if (task.terminal()) continue;
            SUBMITTED.remove(task.session);
            try {
                onEvent(new JSONObject(task.json.toString()).put("status", 1007).put("phase", "failed")
                        .put("terminal", true).put("retryable", false)
                        .put("message", "接收连接已断开，请重新发送"));
            } catch (Exception failure) { log("receive disconnect cleanup failed", failure); }
        }
    }

    public static void onEvent(JSONObject event) {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(() -> onEvent(event)); return; }
        if (!installed) return;
        if ("command_result".equals(event.optString("ev"))) {
            String token = event.optString("session");
            if (event.optString("requestId").equals(SUBMITTED.get(token))) {
                // Keep the latch after successful submission until authoritative state arrives.
                if (!event.optBoolean("ok")) {
                    SUBMITTED.remove(token);
                    Toast.makeText(app, event.optString("error", event.optString("message", "接收操作失败")), Toast.LENGTH_SHORT).show();
                    Screen screen = currentScreen();
                    if (screen != null && token.equals(screen.session)) { screen.phase = ""; screen.render(); }
                }
            }
            return;
        }
        if (!"incoming_transfer".equals(event.optString("ev"))) return;
        String session = event.optString("session");
        if (session.isEmpty() || !event.has("id")) return;
        Task old = TASKS.get(session);
        Task task = new Task(event, old == null ? nextNotification++ : old.notification);
        // Snapshot replay must not turn a completed task back into a pending invitation.
        if (old != null && old.terminal() && !task.terminal()
                && !(old.json.optInt("status") == 1006 && "failed".equals(old.phase())
                && "publishing".equals(task.phase()))) return;
        TASKS.put(session, task);
        if (!task.pending()) SUBMITTED.remove(session);
        pruneHistory();
        notifyTask(task);
        Screen screen = currentScreen();
        if (screen != null && session.equals(screen.session)) screen.render();
        if (screen != null && old == null && task.pending() && !task.terminal()) {
            Task shown = TASKS.get(screen.session);
            if (shown == null || shown.terminal()) {
                screen.session = session; screen.phase = "";
                screen.activity.setIntent(intent(task)); screen.render();
            }
        }
        if (old == null && !task.terminal() && task.pending() && screen == null && launching.isEmpty() && canPresent()) {
            launching = session;
            try { app.startActivity(intent(task)); }
            catch (Throwable failure) { launching = ""; log("receive invitation available in notification", failure); }
            MAIN.postDelayed(() -> { if (session.equals(launching)) launching = ""; }, 5000);
        }
    }

    private static Screen currentScreen() {
        Activity activity = visible.get();
        return activity == null || activity.isDestroyed() || activity.isFinishing() ? null : SCREENS.get(activity);
    }

    private static boolean canPresent() {
        for (Activity activity : NATIVE.keySet()) if (!activity.isDestroyed() && !activity.isFinishing()) return false;
        PowerManager power = app.getSystemService(PowerManager.class);
        KeyguardManager keyguard = app.getSystemService(KeyguardManager.class);
        return power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
    }

    private static Intent intent(Task task) {
        return new Intent().setClassName(app.getPackageName(), roles.activity.getName())
                .setAction("com.tlsu.oshareairdrop.RECEIVE." + task.session)
                .putExtra(EXTRA_SESSION, task.session)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static void notifyTask(Task task) {
        try {
            NotificationManager notifications = app.getSystemService(NotificationManager.class);
            if (notifications == null) return;
            NotificationChannel channel = new NotificationChannel(CHANNEL, "AirDrop 接收", NotificationManager.IMPORTANCE_HIGH);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            notifications.createNotificationChannel(channel);
            PendingIntent open = PendingIntent.getActivity(app, task.notification, intent(task),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = new Notification.Builder(app, CHANNEL)
                    .setSmallIcon(app.getApplicationInfo().icon != 0 ? app.getApplicationInfo().icon : android.R.drawable.stat_sys_download)
                    .setContentTitle(task.title()).setContentText(task.summary()).setContentIntent(open)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
                    .setOngoing(!task.terminal()).setAutoCancel(task.terminal())
                    .setCategory(task.pending() ? Notification.CATEGORY_CALL : Notification.CATEGORY_PROGRESS);
            if (!task.pending() && !task.terminal()) builder.setProgress(100, task.progress(), task.progress() < 0);
            notifications.notify(NOTIFICATION_TAG, task.notification, builder.build());
        } catch (Throwable failure) { log("receive notification unavailable", failure); }
    }

    private static void pruneHistory() {
        if (TASKS.size() <= 128) return;
        java.util.Iterator<Map.Entry<String, Task>> iterator = TASKS.entrySet().iterator();
        while (TASKS.size() > 128 && iterator.hasNext()) {
            Task task = iterator.next().getValue();
            Screen screen = currentScreen();
            if (task.terminal() && (screen == null || !task.session.equals(screen.session))) iterator.remove();
        }
    }

    private static boolean command(String name, Task task) {
        try {
            if (server == null || !server.getBinder().isBinderAlive()) throw new IllegalStateException("Mosey 尚未连接");
            String request = java.util.UUID.randomUUID().toString();
            JSONObject json = new JSONObject().put("cmd", name).put("requestId", request);
            if (task != null) {
                if (SUBMITTED.containsKey(task.session)) return false;
                json.put("id", task.id()).put("session", task.session);
            }
            BridgeMessenger.send(server, json, CLIENT);
            if (task != null) {
                SUBMITTED.put(task.session, request);
                MAIN.postDelayed(() -> {
                    if (request.equals(SUBMITTED.get(task.session))) {
                        SUBMITTED.remove(task.session);
                        command("receive_snapshot", null);
                        Screen screen = currentScreen();
                        if (screen != null && task.session.equals(screen.session)) { screen.phase = ""; screen.render(); }
                    }
                }, 15000);
            }
            return true;
        } catch (Throwable failure) {
            log("receive command failed: " + name, failure);
            if (task != null) Toast.makeText(app, "AirDrop 连接中断，请稍后重试", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static void openFiles(Activity activity, Task task) {
        if (!task.json.optBoolean("published")) return;
        JSONArray files = task.json.optJSONArray("files");
        if (files == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> types = new ArrayList<>();
        String mime = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            Uri uri = Uri.parse(file.optString("uri"));
            if (!"content".equals(uri.getScheme())) continue;
            uris.add(uri);
            String next = file.optString("mime", "application/octet-stream");
            labels.add(file.optString("name", "文件 " + uris.size()));
            types.add(next);
            mime = mime == null ? next : mime.equals(next) ? mime : "*/*";
        }
        if (uris.isEmpty()) return;
        try {
            // ACTION_VIEW has no interoperable multiple-file contract. Open each file via a
            // chooser when there are several; selecting a file never shares it externally.
            if (uris.size() > 1) {
                CharSequence[] names = new CharSequence[uris.size()];
                for (int i = 0; i < names.length; i++) names[i] = labels.get(i);
                new android.app.AlertDialog.Builder(activity).setTitle("打开接收的文件")
                        .setItems(names, (dialog, which) -> viewFile(activity, uris.get(which), types.get(which))).show();
            } else viewFile(activity, uris.get(0), mime == null ? "*/*" : mime);
        } catch (Throwable failure) { Toast.makeText(activity, "无法打开文件", Toast.LENGTH_SHORT).show(); }
    }

    private static void viewFile(Activity activity, Uri uri, String mime) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            view.setClipData(ClipData.newRawUri("AirDrop", uri));
            activity.startActivity(Intent.createChooser(view, "打开文件"));
        } catch (Throwable failure) { Toast.makeText(activity, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show(); }
    }

    private static void log(String text, Throwable failure) {
        if (module != null) module.log(Log.WARN, Entry.TAG, text, failure);
    }

    private static final class Screen {
        final Activity activity;
        String session, phase = "";
        Dialog dialog;
        Intent nativeReplacement;
        Screen(Activity activity, String session) { this.activity = activity; this.session = session; }
        void dismiss() { if (dialog != null) { dialog.dismiss(); dialog = null; } }
        void render() {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            Task task = TASKS.get(session);
            if (task == null) {
                if (server != null) command("receive_snapshot", null);
                // A pending intent can survive a process restart; never fabricate old task state.
                if (dialog == null) show(null);
                return;
            }
            String next = task.phase() + ":" + task.json.optBoolean("published") + ":" + SUBMITTED.containsKey(session);
            if (dialog != null && dialog.isShowing() && next.equals(phase)) {
                dialog.setTitle(task.title());
                try { dialog.getClass().getMethod("setMessage", CharSequence.class).invoke(dialog, task.summary()); }
                catch (ReflectiveOperationException ignored) { /* Title still carries current progress. */ }
                return;
            }
            phase = next; show(task);
        }
        void show(Task task) {
            dismiss();
            try {
                Object builder = roles.builder.newInstance(activity);
                roles.title.invoke(builder, task == null ? "AirDrop" : task.title());
                roles.message.invoke(builder, task == null ? "正在恢复接收状态。若任务已过期，请重新发送。" : task.summary());
                roles.cancelable.invoke(builder, false);
                if (task == null) {
                    roles.positive.invoke(builder, "关闭", (android.content.DialogInterface.OnClickListener) (d, w) -> activity.finish());
                } else if (SUBMITTED.containsKey(task.session)) {
                    roles.title.invoke(builder, "AirDrop · 正在提交操作");
                    roles.positive.invoke(builder, "关闭", (android.content.DialogInterface.OnClickListener) (d, w) -> activity.finish());
                } else if (task.pending()) {
                    roles.positive.invoke(builder, "接受", (android.content.DialogInterface.OnClickListener) (d, w) -> {
                        command("accept", task); phase = ""; MAIN.post(this::render);
                    });
                    roles.negative.invoke(builder, "拒绝", (android.content.DialogInterface.OnClickListener) (d, w) -> {
                        command("deny", task); phase = ""; MAIN.post(this::render);
                    });
                } else if (!task.terminal()) {
                    roles.positive.invoke(builder, "后台接收", (android.content.DialogInterface.OnClickListener) (d, w) -> activity.finish());
                    roles.negative.invoke(builder, "取消", (android.content.DialogInterface.OnClickListener) (d, w) -> {
                        command("cancel_incoming", task); phase = ""; MAIN.post(this::render);
                    });
                } else {
                    roles.positive.invoke(builder, "完成", (android.content.DialogInterface.OnClickListener) (d, w) -> activity.finish());
                    if (task.json.optBoolean("retryable") && !task.json.optBoolean("published") && "failed".equals(task.phase())) {
                        roles.negative.invoke(builder, "重试保存", (android.content.DialogInterface.OnClickListener) (d, w) -> {
                            command("retry_publish", task); phase = ""; MAIN.post(this::render);
                        });
                    } else if (task.json.optBoolean("published")) roles.negative.invoke(builder, "打开文件",
                            (android.content.DialogInterface.OnClickListener) (d, w) -> { openFiles(activity, task); phase = ""; MAIN.post(this::render); });
                }
                dialog = (Dialog) roles.create.invoke(builder); dialog.show();
            } catch (Throwable failure) { log("COUI receive dialog failed", failure); activity.finish(); }
        }
    }

    private static final class Task {
        final JSONObject json;
        final String session;
        final int notification;
        Task(JSONObject json, int notification) { this.json = json; session = json.optString("session"); this.notification = notification; }
        long id() { return json.optLong("id"); }
        String phase() { return json.optString("phase", json.optInt("status") == 1002 ? "pending" : "receiving"); }
        boolean pending() { return "pending".equals(phase()); }
        boolean terminal() { return json.optBoolean("terminal"); }
        int progress() { return json.has("progress") ? Math.max(-1, Math.min(100, json.optInt("progress", -1))) : -1; }
        String title() {
            switch (phase()) {
                case "pending": return "AirDrop · 收到文件邀请";
                case "accepting": return "AirDrop · 正在接受";
                case "rejecting": return "AirDrop · 正在拒绝";
                case "cancelling": return "AirDrop · 正在取消";
                case "verifying": return "AirDrop · 正在校验";
                case "publishing": return "AirDrop · 正在保存文件";
                case "complete": return json.optBoolean("published") ? "AirDrop · 接收完成" : "AirDrop · 正在保存文件";
                case "failed": return "AirDrop · 接收失败";
                case "rejected": return "AirDrop · 已拒绝";
                case "cancelled": return "AirDrop · 已取消";
                case "expired": return "AirDrop · 邀请已过期";
                default: return "AirDrop · 正在接收" + (progress() < 0 ? "" : " " + progress() + "%");
            }
        }
        String summary() {
            StringBuilder text = new StringBuilder(json.optString("name", "Apple 设备"));
            JSONArray files = json.optJSONArray("files");
            if (files != null && files.length() > 0) {
                text.append("\n").append(files.length()).append(" 个文件");
                long size = 0; boolean known = true;
                for (int i = 0; i < files.length(); i++) {
                    JSONObject file = files.optJSONObject(i);
                    if (file == null) { known = false; continue; }
                    long bytes = file.optLong("size", -1);
                    if (bytes < 0 || Long.MAX_VALUE - size < bytes) known = false; else size += bytes;
                    if (i < 3) text.append("\n").append(file.optString("name", "文件"));
                }
                if (files.length() > 3) text.append("\n…");
                text.append("\n").append(known ? android.text.format.Formatter.formatShortFileSize(app, size) : "大小未知");
            } else text.append("\n文件信息未知");
            String message = json.optString("message");
            if (!message.isEmpty()) text.append("\n").append(message);
            return text.toString();
        }
    }
}
