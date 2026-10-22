package com.tlsu.oshareairdrop;

import android.app.BroadcastOptions;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.Process;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ParcelFileDescriptor;
import java.util.ArrayList;
import android.util.Log;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import org.json.JSONObject;

/**
 * Binder bridge that lives in the Mosey process. The client (test app / OShare hook) passes its
 * Messenger through MoseyPermissionActivity's intent; Mosey replies with its own Messenger.
 * Commands and events are JSON strings in Message bundles.
 */
public final class BridgeMessenger {
    public static final int MSG_HELLO = 1;
    public static final int MSG_COMMAND = 2;
    public static final int MSG_EVENT = 3;
    /** Admission succeeded; MSG_HELLO still waits for the provider. */
    public static final int MSG_ATTACHED = 4;
    public static final String EXTRA_CLIENT = "oshare_bridge_client";
    public static final String EXTRA_JSON = "json";

    private static final String TAG = "OShareAirDrop";

    private static final CopyOnWriteArrayList<Messenger> CLIENTS = new CopyOnWriteArrayList<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static boolean sinkInstalled;
    private static boolean receiverInstalled;
    private static final String CONNECT = "com.tlsu.oshareairdrop.BRIDGE_CONNECT";
    static final String READY = "com.tlsu.oshareairdrop.BRIDGE_READY";
    private static Context hostContext;
    private static volatile Runnable receiveClientReady;
    private static final java.util.Map<IBinder, Integer> CLIENT_UIDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<IBinder> RECEIVE_CLIENTS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile Messenger server;
    private static final OutgoingPreparations PREPARATIONS = new OutgoingPreparations();

    static boolean preparingOutgoing() { return PREPARATIONS.busy(android.os.SystemClock.elapsedRealtime()); }

    private static final Handler COMMAND_HANDLER = new Handler(Looper.getMainLooper(), message -> {
        if (message.what == MSG_COMMAND) {
            String json = message.getData().getString(EXTRA_JSON, "");
            ArrayList<ParcelFileDescriptor> files = message.getData()
                    .getParcelableArrayList("files", ParcelFileDescriptor.class);
            int uid = message.sendingUid;
            Messenger reply = message.replyTo;
            if (!allowedUid(uid)) {
                closeFiles(files);
                Log.w(TAG, "Rejected bridge command uid=" + uid);
                return true;
            }
            if (!json.isEmpty()) WORKER.execute(() -> handleCommand(json, files, uid, reply));
            else closeFiles(files);
        }
        return true;
    });

    private BridgeMessenger() {}

    public static synchronized void install(Context context) {
        if (receiverInstalled) return;
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context host, Intent intent) {
                int uid = getSentFromUid();
                if (!allowedUid(uid)) { Log.w(TAG, "Rejected bridge connect uid=" + uid); return; }
                Messenger client = intent.getParcelableExtra(EXTRA_CLIENT, Messenger.class);
                if (client != null) attach(client, uid);
            }
        }, new IntentFilter(CONNECT), Context.RECEIVER_EXPORTED);
        receiverInstalled = true;
        hostContext = context;
        StartupTrace.mark("BRIDGE_RECEIVER_READY");
        announceReady();
    }

    /** A hint only: the command Binder still comes through the UID-checked handshake. */
    static void observeReadiness(Context context, Runnable onReady) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context host, Intent intent) {
                if (!trustedReadyUid(context, getSentFromUid())) return;
                onReady.run();
            }
        }, new IntentFilter(READY), Context.RECEIVER_EXPORTED);
    }

    static boolean trustedReadyUid(Context context, int uid) {
        if (uid < 0 || uid / 100000 != Process.myUid() / 100000) return false;
        try { return uid == context.getPackageManager().getPackageUid(Entry.HOST, 0); }
        catch (Exception failure) {
            StartupTrace.mark("BRIDGE_HOST_UID_UNAVAILABLE");
            Log.w(TAG, "Cannot resolve Mosey UID from host=" + context.getPackageName(), failure);
            return false;
        }
    }

    private static void announceReady() {
        Context context = hostContext;
        if (context == null) return;
        try {
            context.sendBroadcast(new Intent(READY).setPackage("com.coloros.oshare"), null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        } catch (RuntimeException failure) { Log.w(TAG, "bridge ready hint failed", failure); }
    }

    static void onReceiveClientReady(Runnable listener) { receiveClientReady = listener; }

    public static void connect(Context context, Messenger client) {
        Intent intent = new Intent(CONNECT).setPackage(Entry.HOST).putExtra(EXTRA_CLIENT, client);
        context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    public static void boot(Activity activity, Messenger client) {
        Intent intent = new Intent().setClassName(Entry.HOST, Entry.HOST + ".MoseyPermissionActivity")
                .putExtra(Entry.BOOT_EXTRA, true).putExtra(EXTRA_CLIENT, client);
        // Without explicit sharing, getLaunchedFromUid() returns INVALID_UID across UIDs.
        activity.startActivity(intent, ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    public static void send(Messenger server, JSONObject command) throws RemoteException {
        send(server, command, null);
    }

    public static void send(Messenger server, JSONObject command, Messenger client) throws RemoteException {
        Message message = Message.obtain(null, MSG_COMMAND);
        message.replyTo = client;
        Bundle data = new Bundle(); data.putString(EXTRA_JSON, command.toString()); message.setData(data);
        server.send(message);
    }

    static void execute(Runnable action) { WORKER.execute(action); }

    static boolean allowedUid(int uid) {
        Context context = HostContext.get();
        if (context == null || uid < 0 || uid / 100000 != Process.myUid() / 100000) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String name : packages)
            if (name.equals("com.coloros.oshare") || name.equals("com.tlsu.oshareairdrop") || uid == Process.myUid()) return true;
        return false;
    }

    private static boolean oshareUid(int uid) {
        try { return HostContext.get().getPackageManager().getPackageUid("com.coloros.oshare", 0) == uid; }
        catch (Exception failure) { return false; }
    }

    public static synchronized void attach(Messenger clientMessenger, int uid) {
        if (!allowedUid(uid)) throw new SecurityException("Untrusted bridge client");
        IBinder binder = clientMessenger.getBinder();
        CLIENT_UIDS.put(binder, uid);
        if (CLIENTS.addIfAbsent(clientMessenger)) try {
            binder.linkToDeath(() -> remove(clientMessenger), 0);
        } catch (RemoteException failure) { remove(clientMessenger); return; }
        server = new Messenger(COMMAND_HANDLER);
        if (!sinkInstalled) {
            sinkInstalled = true;
            ProviderApi.setSink(BridgeMessenger::sendEvent);
        }
        try {
            clientMessenger.send(Message.obtain(null, MSG_ATTACHED));
        } catch (RemoteException failure) { remove(clientMessenger); return; }
        Log.i(TAG, "bridge client admitted uid=" + uid + " providerReady=" + ProviderHolder.ready());
        StartupTrace.mark("CLIENT_ATTACHED");
        if (ProviderHolder.ready()) sendHello(clientMessenger);
    }

    private static void remove(Messenger client) {
        CLIENTS.remove(client); CLIENT_UIDS.remove(client.getBinder()); RECEIVE_CLIENTS.remove(client.getBinder());
    }

    static boolean hasReceiveClient() {
        for (Messenger client : CLIENTS)
            if (client.getBinder().isBinderAlive() && RECEIVE_CLIENTS.contains(client.getBinder())) return true;
        return false;
    }

    public static void providerReady() {
        StartupTrace.mark("PROVIDER_READY");
        sendHello();
        announceReady();
    }

    private static void sendHello() {
        for (Messenger target : CLIENTS) sendHello(target);
    }

    private static void sendHello(Messenger target) {
        try {
            Message message = Message.obtain(null, MSG_HELLO);
            message.replyTo = server;
            target.send(message);
            StartupTrace.mark("CLIENT_HELLO");
            Log.i(TAG, "bridge hello sent");
        } catch (Throwable t) {
            Log.w(TAG, "bridge hello failed", t);
            remove(target);
        }
    }

    private static void sendEvent(JSONObject event) {
        for (Messenger target : CLIENTS) {
        try {
            Message message = Message.obtain(null, MSG_EVENT);
            message.replyTo = server;
            Bundle bundle = new Bundle();
            bundle.putString(EXTRA_JSON, event.toString());
            message.setData(bundle);
            target.send(message);
        } catch (Throwable t) {
            Log.w(TAG, "event send failed", t);
            remove(target);
        }
        }
    }

    private static void handleCommand(String json, ArrayList<ParcelFileDescriptor> files, int uid, Messenger reply) {
        JSONObject command = new JSONObject();
        try {
            command = new JSONObject(json);
            String cmd = command.optString("cmd", "");
            if ((!ShareModeRuntime.current().usesOShare() || ShareModeRuntime.switching()) &&
                    (cmd.startsWith("send") || cmd.equals("prepare_send") || cmd.equals("discover_start") || cmd.equals("advertise_start")
                            || cmd.equals("set_download_dir") || cmd.equals("retry_publish")))
                throw new IllegalStateException("AirDrop 分享入口正在切换或已停用 OShare");
            Log.i(TAG, "bridge command: " + cmd);
            switch (cmd) {
                case "prepare_send":
                    if (!PREPARATIONS.acquire(uid, command.getString("session"), android.os.SystemClock.elapsedRealtime()))
                        throw new IllegalStateException("Too many pending shares or invalid session");
                    break;
                case "finish_prepare":
                    PREPARATIONS.release(uid, command.getString("session"));
                    break;
                case "ping":
                    sendEvent(new JSONObject().put("ev", "pong")
                            .put("ts", System.currentTimeMillis()));
                    break;
                case "discover_start":
                    ProviderApi.startDiscovery();
                    break;
                case "discover_stop":
                    ProviderApi.stopDiscovery();
                    break;
                case "advertise_start":
                    ProviderApi.startAdvertising(command.optString("name", "OnePlus"));
                    break;
                case "advertise_stop":
                    ProviderApi.stopAdvertising();
                    break;
                case "send":
                    ProviderApi.sendPath(command.getString("path"), command.getLong("id"));
                    break;
                case "send_files":
                    ProviderApi.sendFiles(command.getJSONArray("files"), files, command.getLong("id"),
                            command.optString("session", java.util.UUID.randomUUID().toString()));
                    break;
                case "send_content":
                    ProviderApi.sendContent(command.optJSONArray("uris") == null
                            ? new org.json.JSONArray() : command.getJSONArray("uris"),
                            command.optString("text", ""), command.getLong("id"),
                            command.optString("session", java.util.UUID.randomUUID().toString()));
                    break;
                case "send_test":
                    ProviderApi.sendTestFile(command.getLong("id"));
                    break;
                case "receive_ui_ready":
                    if (!oshareUid(uid)) throw new SecurityException("Receive UI must run in OShare");
                    if (reply == null || CLIENT_UIDS.getOrDefault(reply.getBinder(), -1) != uid)
                        throw new SecurityException("Receive UI must register its callback");
                    if (RECEIVE_CLIENTS.add(reply.getBinder())) {
                        StartupTrace.mark("RECEIVE_CLIENT_ACK");
                        if (receiveClientReady != null) receiveClientReady.run();
                    }
                    break;
                case "receive_snapshot":
                    IncomingReceiver.snapshot();
                    break;
                case "accept":
                case "deny":
                case "cancel_incoming":
                    IncomingReceiver.action(cmd, command.getLong("id"), command.getString("session"));
                    break;
                case "retry_publish":
                    IncomingReceiver.retryPublish(command.getLong("id"), command.getString("session"));
                    break;
                case "cancel":
                    if (command.has("session")) ProviderApi.cancel(command.getLong("id"), command.getString("session"));
                    else ProviderApi.cancel(command.getLong("id"));
                    break;
                case "set_download_dir":
                    ProviderApi.setDownloadDirectory(command.getString("path"));
                    break;
                default:
                    throw new IllegalArgumentException("unknown cmd: " + cmd);
            }
            commandResult(command, uid, reply, true, "");
        } catch (Throwable t) {
            commandResult(command, uid, reply, false, String.valueOf(t.getMessage()));
            Log.e(TAG, "bridge command failed", t);
            try {
                JSONObject request;
                try { request = new JSONObject(json); } catch (Throwable ignored) { request = new JSONObject(); }
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                sendEvent(new JSONObject().put("ev", "error").put("id", request.optLong("id"))
                        .put("cmd", request.optString("cmd")).put("session", request.optString("session"))
                        .put("requestId", request.optString("requestId"))
                        .put("code", cause instanceof OutgoingTransfers.BusyException ? "busy" : "failed")
                        .put("message", cause instanceof OutgoingTransfers.BusyException
                                ? cause.getMessage() : String.valueOf(cause)));
            } catch (Throwable ignored) {
            }
        } finally {
            if ("send_files".equals(command.optString("cmd")) || "send_content".equals(command.optString("cmd")))
                PREPARATIONS.release(uid, command.optString("session"));
            closeFiles(files);
        }
    }
    private static void closeFiles(ArrayList<ParcelFileDescriptor> files) {
        if (files != null) for (ParcelFileDescriptor file : files) try { file.close(); } catch (Exception ignored) {}
    }

    private static void commandResult(JSONObject command, int uid, Messenger reply, boolean ok, String error) {
        try {
            JSONObject result = new JSONObject().put("ev", "command_result").put("cmd", command.optString("cmd"))
                    .put("requestId", command.optString("requestId")).put("id", command.optLong("id"))
                    .put("session", command.optString("session")).put("ok", ok).put("code", ok ? 0 : -1)
                    .put("error", error).put("message", error);
            if (reply != null && CLIENT_UIDS.getOrDefault(reply.getBinder(), -1) == uid) deliver(reply, result);
            else for (Messenger client : CLIENTS)
                if (CLIENT_UIDS.getOrDefault(client.getBinder(), -1) == uid) deliver(client, result);
        } catch (Exception failure) { Log.w(TAG, "command result failed", failure); }
    }

    private static void deliver(Messenger client, JSONObject event) {
        try {
            Message message = Message.obtain(null, MSG_EVENT);
            message.replyTo = server;
            Bundle data = new Bundle(); data.putString(EXTRA_JSON, event.toString()); message.setData(data);
            client.send(message);
        } catch (RemoteException failure) { remove(client); }
    }

}
