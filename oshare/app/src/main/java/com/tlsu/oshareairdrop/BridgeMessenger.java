package com.tlsu.oshareairdrop;

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
    public static final String EXTRA_CLIENT = "oshare_bridge_client";
    public static final String EXTRA_JSON = "json";

    private static final String TAG = "OShareAirDrop";

    private static final CopyOnWriteArrayList<Messenger> CLIENTS = new CopyOnWriteArrayList<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static boolean sinkInstalled;
    private static volatile Messenger server;

    private static final Handler COMMAND_HANDLER = new Handler(Looper.getMainLooper(), message -> {
        if (message.what == MSG_COMMAND) {
            String json = message.getData().getString(EXTRA_JSON, "");
            ArrayList<ParcelFileDescriptor> files = message.getData()
                    .getParcelableArrayList("files", ParcelFileDescriptor.class);
            if (!json.isEmpty()) WORKER.execute(() -> handleCommand(json, files));
        }
        return true;
    });

    private BridgeMessenger() {}

    public static synchronized void attach(Messenger clientMessenger) {
        CLIENTS.addIfAbsent(clientMessenger);
        server = new Messenger(COMMAND_HANDLER);
        if (!sinkInstalled) {
            sinkInstalled = true;
            ProviderApi.setSink(BridgeMessenger::sendEvent);
        }
        if (ProviderHolder.ready()) sendHello();
    }

    public static void providerReady() {
        sendHello();
    }

    private static void sendHello() {
        for (Messenger target : CLIENTS) {
        try {
            Message message = Message.obtain(null, MSG_HELLO);
            message.replyTo = server;
            target.send(message);
            Log.i(TAG, "bridge hello sent");
        } catch (Throwable t) {
            Log.w(TAG, "bridge hello failed", t);
            CLIENTS.remove(target);
        }
        }
    }

    private static void sendEvent(JSONObject event) {
        for (Messenger target : CLIENTS) {
        try {
            Message message = Message.obtain(null, MSG_EVENT);
            Bundle bundle = new Bundle();
            bundle.putString(EXTRA_JSON, event.toString());
            message.setData(bundle);
            target.send(message);
        } catch (Throwable t) {
            Log.w(TAG, "event send failed", t);
            CLIENTS.remove(target);
        }
        }
    }

    private static void handleCommand(String json, ArrayList<ParcelFileDescriptor> files) {
        try {
            JSONObject command = new JSONObject(json);
            String cmd = command.optString("cmd", "");
            Log.i(TAG, "bridge command: " + cmd);
            switch (cmd) {
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
                    ProviderApi.sendFiles(command.getJSONArray("files"), files, command.getLong("id"));
                    break;
                case "send_content":
                    ProviderApi.sendContent(command.optJSONArray("uris") == null
                            ? new org.json.JSONArray() : command.getJSONArray("uris"),
                            command.optString("text", ""), command.getLong("id"));
                    break;
                case "send_test":
                    ProviderApi.sendTestFile(command.getLong("id"));
                    break;
                case "accept":
                    ProviderApi.accept(command.getLong("id"));
                    break;
                case "deny":
                    ProviderApi.deny(command.getLong("id"));
                    break;
                case "cancel":
                    ProviderApi.cancel(command.getLong("id"));
                    break;
                case "set_download_dir":
                    ProviderApi.setDownloadDirectory(command.getString("path"));
                    break;
                default:
                    sendEvent(new JSONObject().put("ev", "error")
                            .put("message", "unknown cmd: " + cmd));
            }
        } catch (Throwable t) {
            Log.e(TAG, "bridge command failed", t);
            try {
                JSONObject request;
                try { request = new JSONObject(json); } catch (Throwable ignored) { request = new JSONObject(); }
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                sendEvent(new JSONObject().put("ev", "error").put("id", request.optLong("id"))
                        .put("cmd", request.optString("cmd"))
                        .put("code", cause instanceof OutgoingTransfers.BusyException ? "busy" : "failed")
                        .put("message", cause instanceof OutgoingTransfers.BusyException
                                ? cause.getMessage() : String.valueOf(cause)));
            } catch (Throwable ignored) {
            }
        } finally {
            if (files != null) for (ParcelFileDescriptor fd : files) {
                try { fd.close(); } catch (Exception ignored) {}
            }
        }
    }
}
