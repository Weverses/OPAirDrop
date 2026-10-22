package com.tlsu.oshareairdrop;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface.HookHandle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** OShare adapter bound to semantic roles discovered by DexKit. */
public final class OShareBridge {
    private static final String TAG = "OShareAirDrop";
    private static final String PREFIX = "airdrop:";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Object> DEVICES = new ConcurrentHashMap<>();
    private static final Map<String, String> PHASES = new ConcurrentHashMap<>();
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
    private static final Map<String, Long> STARTED = new ConcurrentHashMap<>();
    private static final java.util.Set<String> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> ONLINE = ConcurrentHashMap.newKeySet();
    private static final PendingShares<SendRequest> PENDING = new PendingShares<>();
    private static final java.util.concurrent.ExecutorService FILES = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(4));
    private static final Map<Object, Boolean> ADAPTERS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static boolean refreshScheduled;
    private static final Messenger CLIENT = new Messenger(new Handler(Looper.getMainLooper(),
            OShareBridge::receive));
    private static ClassLoader loader;
    private static volatile Messenger server;
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static boolean connecting;
    private static boolean bridgeAttached;
    private static int connectionGeneration;
    private static android.os.IBinder.DeathRecipient serverDeath;
    private static OShareSymbols symbols;
    private static volatile OShareChooserBridge chooser;
    private static boolean discoveryRequested;
    private static final ArrayList<HookHandle> HOOKS = new ArrayList<>();

    private OShareBridge() {}

    public static void install(Entry module, Context context, ClassLoader cl) throws Exception {
        OShareSymbols resolved = OShareResolver.resolve(module, context, cl);
        loader = cl;
        symbols = resolved;
        Class<?> adapter = symbols.type("adapter");
        try {
            for (java.lang.reflect.Constructor<?> ctor : adapter.getDeclaredConstructors()) {
                HOOKS.add(module.hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    ADAPTERS.put(chain.getThisObject(), true);
                    return result;
                }));
            }
            HOOKS.add(module.hook(symbols.method("adapter.bind")).intercept(chain -> {
                Object device = chain.getArg(0);
                String key = device == null ? "" : symbols.key(device);
                if (key.startsWith(PREFIX)) {
                    try {
                        android.widget.ImageView avatar = (android.widget.ImageView) symbols.invoke("holder.image", chain.getArg(1));
                        OShareAppearance.restore(avatar.getContext(), device, symbols);
                    } catch (Throwable t) { module.log(Log.WARN, TAG, "device icon restore failed", t); }
                }
                Object result = chain.proceed();
                if (key.startsWith(PREFIX)) {
                    android.widget.ImageView avatar = (android.widget.ImageView) symbols.invoke("holder.image", chain.getArg(1));
                    android.graphics.Bitmap icon = (android.graphics.Bitmap) symbols.invoke("device.icon", device);
                    if (icon != null && !icon.isRecycled()) avatar.setImageBitmap(icon);
                }
                return result;
            }));
            HOOKS.add(module.hook(symbols.method("adapter.label")).intercept(chain -> {
                Object device = chain.getArg(1);
                String key = device == null ? "" : symbols.key(device);
                if (!key.startsWith(PREFIX)) return chain.proceed();
                if ("IDLE".equals(String.valueOf(symbols.invoke("device.state", device)))) return "AirDrop";
                String phase = PHASES.get(key);
                return phase != null ? phase : chain.proceed();
            }));
            // The share panel also reads the manager directly after its list callback.
            HOOKS.add(module.hook(symbols.method("manager.list")).intercept(chain -> {
                ArrayList<Object> result = new ArrayList<>();
                // Native refreshes may retain a previous merged list. Replace
                // our namespace with the current snapshot instead of appending
                // it again (or retaining peers already reported lost).
                for (Object device : (java.util.List<?>) chain.proceed())
                    if (device == null || !symbols.key(device).startsWith(PREFIX)) result.add(device);
                if (ShareModeRuntime.current().usesOShare()) result.addAll(DEVICES.values());
                return result;
            }));
            HOOKS.add(module.hook(symbols.method("manager.get")).intercept(chain -> {
                Object device = ShareModeRuntime.current().usesOShare() ? DEVICES.get((String) chain.getArg(0)) : null;
                return device != null ? device : chain.proceed();
            }));
            HOOKS.add(module.hook(symbols.method("activity.resume")).intercept(chain -> {
                Object result = chain.proceed();
                Activity current = (Activity) chain.getThisObject();
                if (activity.get() != current) {
                    connectionGeneration++;
                    connecting = false;
                }
                activity = new WeakReference<>(current);
                OShareAppearance.renameSection(current);
                StartupTrace.mark("SHARE_PAGE_RESUMED");
                MAIN.post(OShareBridge::syncSession);
                return result;
            }));
            for (String role : new String[]{"activity.update", "activity.list", "activity.panel"}) {
                HOOKS.add(module.hook(symbols.method(role)).intercept(chain -> {
                    Object result = chain.proceed();
                    OShareAppearance.renameSection((Activity) chain.getThisObject());
                    return result;
                }));
            }
            HOOKS.add(module.hook(symbols.method("activity.destroy")).intercept(chain -> {
                if (activity.get() == chain.getThisObject()) {
                    activity.clear();
                    MAIN.post(OShareBridge::syncSession);
                }
                return chain.proceed();
            }));
            // Hook the common internal service so regular OShare traffic keeps its original path.
            HOOKS.add(module.hook(symbols.method("service.send")).intercept(chain -> {
                Object device = chain.getArg(1);
                String key = device == null ? "" : symbols.key(device);
                if (!key.startsWith(PREFIX)) return chain.proceed();
                Intent request = new Intent((Intent) chain.getArg(0));
                MAIN.post(() -> send(request, key));
                return null;
            }));
            HOOKS.add(module.hook(symbols.method("service.cancel")).intercept(chain -> {
                Object device = chain.getArg(0);
                String key = device == null ? "" : symbols.key(device);
                if (!key.startsWith(PREFIX)) return chain.proceed();
                MAIN.post(() -> command("cancel", key));
                return null;
            }));
            module.log(Log.INFO, TAG, "OShare DexKit discovery, device list, send and cancel hooks installed");
            // Optional capability failure must not roll back the independent OShare page.
            try { chooser = OShareChooserBridge.install(module, context, cl, symbols); }
            catch (Throwable failure) { module.log(Log.WARN, TAG, "System Chooser AirDrop capability unavailable", failure); }
            try { BridgeMessenger.observeReadiness(context, () -> MAIN.post(OShareBridge::syncSession)); }
            catch (Throwable failure) { module.log(Log.WARN, TAG, "Ready hint unavailable; bounded admission retries retained", failure); }
            MAIN.post(new Runnable() {
                private ShareMode previous = ShareModeRuntime.current();
                @Override public void run() {
                    ShareMode current = ShareModeRuntime.current();
                    if (current != previous) {
                        previous = current;
                        DEVICES.clear();
                        ONLINE.clear();
                        NAMES.clear();
                        connectionGeneration++;
                        connecting = false;
                        try { refresh(); } catch (Exception failure) { Log.w(TAG, "mode refresh failed", failure); }
                    }
                    syncSession();
                    MAIN.postDelayed(this, 1000);
                }
            });
            MAIN.post(() -> {
                try { OShareSendPresentation.install(module, context, cl); }
                catch (Throwable failure) { module.log(Log.WARN, TAG, "Sender presentation unavailable", failure); }
            });
        } catch (Throwable failure) {
            for (HookHandle handle : HOOKS) try { handle.unhook(); }
            catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            HOOKS.clear();
            throw new IllegalStateException("OShare hook installation rolled back", failure);
        }
    }

    private static boolean isCurrentActivity(Activity current) {
        return current != null && activity.get() == current && !current.isFinishing() && !current.isDestroyed();
    }

    private static boolean uiWanted() {
        return isCurrentActivity(activity.get()) || chooser != null && chooser.active();
    }

    private static boolean connectionWanted() { return uiWanted() || !PENDING.isEmpty() || !ACTIVE.isEmpty(); }

    static Map<String, Object> chooserDevices() {
        if (chooser == null || !chooser.active() || !ShareModeRuntime.current().usesOShare()
                || ShareModeRuntime.switching()) return java.util.Collections.emptyMap();
        return new java.util.LinkedHashMap<>(DEVICES);
    }

    static void chooserChanged() {
        syncSession();
        try { refresh(); } catch (Exception failure) { Log.w(TAG, "Chooser session refresh failed", failure); }
    }

    private static void syncSession() {
        boolean enabled = ShareModeRuntime.current().usesOShare() && !ShareModeRuntime.switching();
        if (!enabled) failPending("分享入口正在切换，请重新发送");
        if (server != null && server.getBinder().isBinderAlive()) {
            boolean wanted = enabled && (uiWanted() || !PENDING.isEmpty());
            if (discoveryRequested != wanted) try {
                if (wanted) StartupTrace.mark("SHARE_WARM_DISCOVERY_REQUEST");
                sendCommand(new JSONObject().put("cmd", wanted ? "discover_start" : "discover_stop"));
                discoveryRequested = wanted;
            } catch (Exception failure) { Log.w(TAG, "Discovery session update failed", failure); }
        }
        if (enabled && connectionWanted()) {
            connect();
            preparePending();
        } else if (connecting) {
            connectionGeneration++;
            connecting = false;
        }
        if (!uiWanted()) {
            boolean removed = DEVICES.keySet().removeIf(key -> !ACTIVE.contains(key) && !pendingKey(key));
            ONLINE.retainAll(DEVICES.keySet());
            NAMES.keySet().retainAll(DEVICES.keySet());
            if (removed) try { refresh(); } catch (Exception failure) { Log.w(TAG, "Idle device cleanup failed", failure); }
        }
    }

    private static boolean pendingKey(String key) {
        return PENDING.snapshot().stream().anyMatch(entry -> entry.key.equals(key));
    }

    private static void connect() {
        if (!connectionWanted() || !ShareModeRuntime.current().usesOShare() || ShareModeRuntime.switching()) return;
        if (server != null && server.getBinder().isBinderAlive()) return;
        if (server != null) { disconnected(server); return; }
        if (connecting) return;
        server = null;
        discoveryRequested = false;
        connecting = true;
        bridgeAttached = false;
        int generation = ++connectionGeneration;
        try { BridgeMessenger.connect(HostContext.get(), CLIENT); }
        catch (Throwable failure) { Log.w(TAG, "Warm bridge admission failed", failure); }
        MAIN.postDelayed(() -> {
            if (!pending(generation)) return;
            Activity current = activity.get();
            if (isCurrentActivity(current) && !bridgeAttached) boot(current, generation, 0);
            else checkConnection(generation, 0);
        }, 300);
    }

    private static boolean pending(int generation) {
        return ShareModeRuntime.current().usesOShare() && !ShareModeRuntime.switching()
                && connectionWanted() && connecting && generation == connectionGeneration;
    }

    private static void boot(Activity current, int generation, int attempt) {
        if (!pending(generation)) return;
        // Chooser sessions use the resident provider; never launch an Activity from a service.
        if (!isCurrentActivity(current) || bridgeAttached) { checkConnection(generation, 0); return; }
        try {
            BridgeMessenger.boot(current, CLIENT);
            Log.i(TAG, "Bridge boot requested generation=" + generation);
            MAIN.postDelayed(() -> checkConnection(generation, 0), 2000);
        } catch (android.content.ActivityNotFoundException unavailable) {
            if (attempt < 4) MAIN.postDelayed(() -> boot(current, generation, attempt + 1), 1500);
            else { connecting = false; failPending("AirDrop 服务未启用"); toast("AirDrop 服务未启用，请启动底层模块"); }
        } catch (Throwable failure) {
            connecting = false;
            failPending("AirDrop 服务启动失败");
            Log.e(TAG, "OShare boot failed", failure);
            toast("AirDrop 服务启动失败，请查看桥接日志");
        }
    }

    private static void checkConnection(int generation, int check) {
        if (!pending(generation)) return;
        if (check == 4) {
            connecting = false;
            Log.w(TAG, "Bridge timeout generation=" + generation + " attached=" + bridgeAttached);
            failPending("AirDrop 服务尚未就绪，请重新发送");
            return;
        }
        try { BridgeMessenger.connect(HostContext.get(), CLIENT); }
        catch (Throwable failure) { Log.w(TAG, "Bridge admission retry failed", failure); }
        MAIN.postDelayed(() -> checkConnection(generation, check + 1), 2000);
    }

    private static void disconnected(Messenger expected) {
        if (server != expected) return;
        server = null;
        serverDeath = null;
        connectionGeneration++;
        connecting = false;
        bridgeAttached = false;
        boolean interrupted = !ACTIVE.isEmpty();
        failPending("AirDrop 服务连接中断，请重新发送");
        discoveryRequested = false;
        OShareSendPresentation.disconnected();
        DEVICES.clear(); ONLINE.clear(); ACTIVE.clear(); PHASES.clear(); STARTED.clear();
        NAMES.clear();
        try { refresh(); } catch (Throwable failure) { Log.w(TAG, "disconnect refresh failed", failure); }
        if (interrupted) toast("AirDrop 服务连接中断，请重新发送文件");
        MAIN.post(OShareBridge::syncSession);
    }

    private static boolean receive(Message message) {
        if (!BridgeMessenger.trustedReadyUid(HostContext.get(), message.sendingUid)) {
            if (message.what == BridgeMessenger.MSG_HELLO || message.what == BridgeMessenger.MSG_ATTACHED)
                StartupTrace.mark("SHARE_HANDSHAKE_REJECT_UID_" + message.sendingUid);
            return true;
        }
        if (message.what == BridgeMessenger.MSG_ATTACHED) {
            if (connecting && connectionWanted()) bridgeAttached = true;
            return true;
        }
        if (message.what == BridgeMessenger.MSG_HELLO) {
            if (message.replyTo == null || !connectionWanted()) {
                StartupTrace.mark(message.replyTo == null ? "SHARE_HELLO_NO_CHANNEL" : "SHARE_HELLO_NOT_WANTED");
                return true;
            }
            boolean reconnected = server == null || !server.getBinder().equals(message.replyTo.getBinder());
            if (!reconnected && !connecting) return true;
            if (reconnected) {
                if (server != null && serverDeath != null) server.getBinder().unlinkToDeath(serverDeath, 0);
                Messenger connected = message.replyTo;
                server = connected;
                serverDeath = () -> MAIN.post(() -> disconnected(connected));
                try { connected.getBinder().linkToDeath(serverDeath, 0); }
                catch (android.os.RemoteException failure) { disconnected(connected); return true; }
            }
            if (reconnected) { ACTIVE.clear(); PHASES.clear(); STARTED.clear(); }
            connecting = false;
            connectionGeneration++;
            discoveryRequested = false;
            syncSession();
            StartupTrace.mark("SHARE_BRIDGE_CONNECTED");
            Entry.instance.log(Log.INFO, TAG, "OShare bridge ready");
            return true;
        }
        if (message.what != BridgeMessenger.MSG_EVENT) return false;
        // Drop queued events from a dead provider before they can resurrect shortcut IDs.
        if (server == null || message.replyTo == null
                || !server.getBinder().equals(message.replyTo.getBinder())) return true;
        try {
            JSONObject event = new JSONObject(message.getData().getString(BridgeMessenger.EXTRA_JSON, "{}"));
            String type = event.optString("ev");
            if ("command_result".equals(type) && "prepare_send".equals(event.optString("cmd"))) {
                for (PendingShares.Entry<SendRequest> pending : PENDING.snapshot()) {
                    if (!pending.value.session.equals(event.optString("session")) || pending.value.leaseServer != server) continue;
                    if (event.optBoolean("ok")) { pending.value.leaseReady = true; preparePending(); }
                    else cancelPending(pending.key, "AirDrop 服务暂时无法准备发送，请重试");
                    break;
                }
                return true;
            }
            if ("error".equals(type) && ("prepare_send".equals(event.optString("cmd"))
                    || "finish_prepare".equals(event.optString("cmd")))) return true;
            if (event.optBoolean("incoming") || "incoming_transfer".equals(type)
                    || "command_result".equals(type)) return true;
            if (("transfer".equals(type) || "send_requested".equals(type)
                    || ("error".equals(type) && !event.optString("session").isEmpty()))
                    && !OShareSendPresentation.event(event)) return true;
            String key = PREFIX + event.optLong("id");
            if ("target".equals(type) || "target_updated".equals(type)) {
                if (!uiWanted() && !ACTIVE.contains(key) && !pendingKey(key)) return true;
                ONLINE.add(key);
                Object device = DEVICES.get(key);
                if (device == null) device = symbols.deviceConstructor.newInstance();
                Context host = HostContext.get();
                NAMES.put(key, event.optString("name", "Apple 设备"));
                OShareAppearance.apply(host, device, event, symbols);
                Entry.instance.log(Log.INFO, TAG, "discovered icon peer=" + key + " shareTargetType=" + event.optInt("type"));
                symbols.invoke("device.setName", device, event.optString("name", "Apple device"));
                if (chooser != null) try { chooser.decorate(device); }
                catch (Exception failure) { Log.w(TAG, "Chooser label unavailable; original page retained", failure); }
                symbols.invoke("device.setKey", device, key);
                symbols.invoke("device.setWifiMac", device, key);
                symbols.invoke("device.setVirtual", device, 0);
                symbols.invoke("device.setFoundTime", device, System.currentTimeMillis());
                if (!ACTIVE.contains(key)) {
                    PHASES.remove(key);
                    state(device, AirDropPresentation.DISCOVERED_STATE);
                } else {
                    SendUiState sending = OShareSendPresentation.activeState(event.optLong("id"));
                    if (sending != null) {
                        state(device, sending.cancelling ? "CANCEL_WAIT" : AirDropPresentation.state(sending.status, false));
                        symbols.invoke("device.setProgress", device, sending.progress);
                    }
                }
                DEVICES.put(key, device);
                if (isCurrentActivity(activity.get())) StartupTrace.mark("SHARE_TARGET_UPDATED");
                refresh();
            } else if ("target_lost".equals(type)) {
                ONLINE.remove(key);
                cancelPending(key, "AirDrop 设备已离线");
                if (!ACTIVE.contains(key)) { DEVICES.remove(key); NAMES.remove(key); }
                refresh();
            } else if ("send_requested".equals(type)) {
                // Submission is not confirmation that the receiver is displaying an invitation.
                PHASES.put(key, "正在连接对方");
                refresh();
            } else if ("transfer".equals(type)) {
                boolean terminal = OutgoingTransfers.isTerminal(event.optInt("status"), event.optBoolean("terminal"));
                if (terminal) {
                    ACTIVE.remove(key); STARTED.remove(key);
                    MAIN.postDelayed(() -> {
                        if (!ONLINE.contains(key) && !ACTIVE.contains(key) && !pendingKey(key)) {
                            DEVICES.remove(key); NAMES.remove(key);
                            try { refresh(); } catch (Exception failure) { Log.w(TAG, "Lost peer cleanup failed", failure); }
                        }
                    }, 5000);
                } else ACTIVE.add(key);
                String phase = AirDropPresentation.pendingLabel(event.optInt("status"));
                if (phase == null) PHASES.remove(key); else PHASES.put(key, phase);
                Object device = DEVICES.get(key);
                if (device != null) {
                    int status = event.optInt("status");
                    state(device, AirDropPresentation.state(status, terminal));
                    symbols.invoke("device.setProgress", device, Math.max(0, Math.min(100, (int) event.optDouble("progress"))));
                    refresh();
                }
            } else if ("error".equals(type)) {
                Entry.instance.log(Log.ERROR, TAG, "AirDrop command error: " + event.optString("message"));
                toast("AirDrop: " + event.optString("message"));
                Object device = DEVICES.get(key);
                if ("busy".equals(event.optString("code"))) {
                    ACTIVE.add(key);
                    if (device != null) state(device, "TRANSIT_WAIT");
                } else if (!"cancel".equals(event.optString("cmd"))) {
                    ACTIVE.remove(key);
                    PHASES.remove(key);
                    STARTED.remove(key);
                    if (device != null) state(device, "TRANSIT_FAILED");
                }
                refresh();
            }
            if (Entry.instance != null) Entry.instance.log(Log.INFO, TAG, "OShare event=" + type + " status=" + event.optInt("status"));
        } catch (Throwable t) { Entry.instance.log(Log.ERROR, TAG, "OShare event failed", t); }
        return true;
    }

    private static final class SendRequest {
        final Intent intent, panel;
        final String session = java.util.UUID.randomUUID().toString();
        final String name;
        final long started = android.os.SystemClock.elapsedRealtime();
        final android.os.CancellationSignal cancellation = new android.os.CancellationSignal();
        Messenger leaseServer;
        boolean leaseRequested, leaseReady;
        SendRequest(Intent intent, Intent panel, String name) { this.intent = intent; this.panel = panel; this.name = name; }
    }

    private static final class PreparedShare implements AutoCloseable {
        final ArrayList<ParcelFileDescriptor> files = new ArrayList<>();
        JSONObject command;
        @Override public void close() {
            for (ParcelFileDescriptor file : files) try { file.close(); } catch (Exception ignored) {}
        }
    }

    private static long now() { return android.os.SystemClock.elapsedRealtime(); }

    private static void send(Intent intent, String key) {
        if (!ShareModeRuntime.current().usesOShare() || ShareModeRuntime.switching()) {
            toast("分享入口正在切换，请稍后重试"); return;
        }
        for (PendingShares.Entry<SendRequest> pending : PENDING.snapshot()) if (pending.key.equals(key)) {
            if (now() - pending.value.started < AirDropPresentation.CLICK_DEBOUNCE_MS) return;
            cancelPending(key, null);
            toast("已取消准备发送"); return;
        }
        if (ACTIVE.contains(key)) {
            Long started = STARTED.get(key);
            if (started != null && now() - started < AirDropPresentation.CLICK_DEBOUNCE_MS) return;
            PHASES.remove(key);
            command("cancel", key);
            try {
                Object device = DEVICES.get(key);
                if (device != null) { state(device, "CANCEL_WAIT"); refresh(); }
            } catch (Exception failure) { Log.w(TAG, "Cancel UI update failed", failure); }
            toast("正在取消当前 AirDrop 任务"); return;
        }
        // A shortcut is not proof of current discovery. Never send a stale provider ID.
        if (!ONLINE.contains(key) || !DEVICES.containsKey(key)) { toast("AirDrop 设备已离线，请等待重新发现"); return; }
        Activity current = activity.get();
        Intent panel = isCurrentActivity(current) ? new Intent(current.getIntent())
                .setClass(current, current.getClass())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP) : null;
        SendRequest request = new SendRequest(new Intent(intent), panel, NAMES.getOrDefault(key, "Apple 设备"));
        PendingShares.Entry<SendRequest> pending = PENDING.offer(key, request, now());
        if (pending == null) { toast("准备发送的任务较多，请稍后重试"); return; }
        PHASES.put(key, "正在准备发送");
        try { state(DEVICES.get(key), "TRANSIT_WAIT"); refresh(); }
        catch (Exception failure) { Log.w(TAG, "Prepare UI update failed", failure); }
        MAIN.postDelayed(() -> {
            if (!PENDING.current(pending, now()) && PENDING.remove(pending)) {
                request.cancellation.cancel();
                releasePreparation(request);
                preparationFailed(key, "AirDrop 准备超时，请重新发送");
            }
        }, PendingShares.TIMEOUT_MS);
        syncSession();
    }

    private static void preparePending() {
        if (!ShareModeRuntime.current().usesOShare() || ShareModeRuntime.switching()) {
            failPending("分享入口正在切换，请重新发送"); return;
        }
        Messenger connected = server;
        if (connected == null || !connected.getBinder().isBinderAlive()) return;
        for (PendingShares.Entry<SendRequest> pending : PENDING.snapshot()) {
            if (pending.preparing || !PENDING.current(pending, now())) continue;
            if (!pending.value.leaseReady) {
                if (!pending.value.leaseRequested) try {
                    pending.value.leaseServer = connected;
                    pending.value.leaseRequested = true;
                    sendCommand(new JSONObject().put("cmd", "prepare_send").put("session", pending.value.session));
                } catch (Exception failure) { cancelPending(pending.key, "AirDrop 准备请求失败，请重试"); }
                continue;
            }
            pending.preparing = true;
            try {
                FILES.execute(() -> {
                    try {
                        PreparedShare prepared = prepareShare(pending.key, pending.value);
                        if (!MAIN.post(() -> submit(pending, connected, prepared))) prepared.close();
                    } catch (Throwable failure) {
                        MAIN.post(() -> {
                            if (PENDING.remove(pending)) {
                                releasePreparation(pending.value);
                                Log.w(TAG, "Share file preparation failed", failure);
                                preparationFailed(pending.key, "AirDrop 文件准备失败: " + failure.getMessage());
                            }
                        });
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                cancelPending(pending.key, "文件准备繁忙，请稍后重试");
            }
        }
    }

    /** Runs outside the UI/Binder thread, preserving the system's original grant path. */
    private static PreparedShare prepareShare(String key, SendRequest request) throws Exception {
        request.cancellation.throwIfCanceled();
        Intent intent = request.intent;
        Context context = HostContext.get();
        LinkedHashSet<Uri> uris = new LinkedHashSet<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            if (streams != null) uris.addAll(streams);
        } else {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            if (uri != null) uris.add(uri);
        }
        if (intent.getClipData() != null) for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
            Uri uri = intent.getClipData().getItemAt(i).getUri();
            if (uri != null) uris.add(uri);
        }
        if ("com.oplus.oshare.action.DEVICE_LIST".equals(intent.getAction())) {
            intent.setExtrasClassLoader(loader);
            Object list = intent.getSerializableExtra("data_list");
            if (list instanceof Iterable<?>) for (Object data : (Iterable<?>) list) {
                Object uri = contentUri(data);
                if (uri instanceof Uri) uris.add((Uri) uri);
            }
        }
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (uris.isEmpty() && (text == null || text.length() == 0))
            throw new IllegalArgumentException("未选择文件或文字");
        PreparedShare prepared = new PreparedShare();
        try {
            JSONArray values = new JSONArray();
            for (Uri uri : uris) {
                request.cancellation.throwIfCanceled();
                if (!"content".equals(uri.getScheme())) throw new IllegalArgumentException("分享文件需要 content URI");
                String name = "shared-file";
                try (Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null, request.cancellation)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
                }
                ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r", request.cancellation);
                if (fd == null) throw new IllegalArgumentException("文件无法打开");
                prepared.files.add(fd);
                values.put(new JSONObject().put("name", name));
            }
            request.cancellation.throwIfCanceled();
            prepared.command = new JSONObject().put("cmd", uris.isEmpty() ? "send_content" : "send_files")
                    .put("session", request.session).put("id", Long.parseLong(key.substring(PREFIX.length())))
                    .put("uris", new JSONArray()).put("files", values)
                    .put("text", uris.isEmpty() && text != null ? text.toString() : "");
            return prepared;
        } catch (Throwable failure) { prepared.close(); throw failure; }
    }

    private static void submit(PendingShares.Entry<SendRequest> pending, Messenger expected, PreparedShare prepared) {
        try {
            if (!PENDING.current(pending, now())) return;
            if (server != expected || !ONLINE.contains(pending.key) || !DEVICES.containsKey(pending.key)
                    || !ShareModeRuntime.current().usesOShare() || ShareModeRuntime.switching()) {
                cancelPending(pending.key, "AirDrop 会话已改变，请重新发送"); return;
            }
            PENDING.remove(pending);
            SendRequest request = pending.value;
            // Register presentation ownership before the remote provider can emit its first event.
            OShareSendPresentation.begin(Long.parseLong(pending.key.substring(PREFIX.length())), request.session,
                    request.name, prepared.files.size(), request.panel);
            ACTIVE.add(pending.key);
            STARTED.put(pending.key, now());
            Object device = DEVICES.get(pending.key);
            state(device, "TRANSIT_WAIT");
            symbols.invoke("device.setTotal", device, Math.max(1, prepared.files.size()));
            Bundle data = new Bundle();
            data.putString(BridgeMessenger.EXTRA_JSON, prepared.command.toString());
            data.putParcelableArrayList("files", prepared.files);
            Message message = Message.obtain(null, BridgeMessenger.MSG_COMMAND);
            message.setData(data);
            expected.send(message);
            try { refresh(); } catch (Exception failure) { Log.w(TAG, "Submitted share refresh failed", failure); }
        } catch (Throwable failure) {
            Log.e(TAG, "OShare send failed", failure);
            releasePreparation(pending.value);
            if (failure instanceof android.os.RemoteException) disconnected(expected);
            else {
                ACTIVE.remove(pending.key); STARTED.remove(pending.key);
                try { OShareSendPresentation.event(new JSONObject().put("ev", "error").put("session", pending.value.session)
                        .put("id", Long.parseLong(pending.key.substring(PREFIX.length())))
                        .put("message", "发送失败")); } catch (Exception ignored) {}
            }
            preparationFailed(pending.key, "AirDrop 发送失败，请重新发送");
        } finally { prepared.close(); }
    }

    private static void preparationFailed(String key, String message) {
        PHASES.remove(key);
        Object device = DEVICES.get(key);
        try { if (device != null) { state(device, "TRANSIT_FAILED"); refresh(); } }
        catch (Exception failure) { Log.w(TAG, "Failed share UI update", failure); }
        if (message != null) toast(message);
    }

    private static void cancelPending(String key, String reason) {
        PendingShares.Entry<SendRequest> removed = PENDING.remove(key);
        if (removed == null) return;
        removed.value.cancellation.cancel();
        releasePreparation(removed.value);
        if (reason != null) preparationFailed(key, reason);
        else try {
            PHASES.remove(key);
            Object device = DEVICES.get(key);
            if (device != null) { state(device, "IDLE"); refresh(); }
        } catch (Exception failure) { Log.w(TAG, "Pending cancel UI update failed", failure); }
    }

    private static void failPending(String reason) {
        for (PendingShares.Entry<SendRequest> pending : PENDING.snapshot()) cancelPending(pending.key, reason);
    }

    private static void releasePreparation(SendRequest request) {
        if (!request.leaseRequested || request.leaseServer != server || server == null) return;
        try { sendCommand(new JSONObject().put("cmd", "finish_prepare").put("session", request.session)); }
        catch (Exception failure) { Log.w(TAG, "Preparation release failed; bounded provider lease will expire", failure); }
    }

    private static void command(String cmd, String key) {
        try {
            if ("cancel".equals(cmd) && key != null
                    && OShareSendPresentation.cancelTarget(Long.parseLong(key.substring(PREFIX.length())))) return;
            JSONObject request = new JSONObject().put("cmd", cmd);
            if (key != null) request.put("id", Long.parseLong(key.substring(PREFIX.length())));
            sendCommand(request);
        } catch (Throwable t) { Log.w(TAG, "OShare command failed: " + cmd, t); }
    }

    static boolean cancelOutgoing(long id, String session) {
        try {
            sendCommand(new JSONObject().put("cmd", "cancel").put("id", id).put("session", session));
            return true;
        } catch (Throwable failure) { Log.w(TAG, "outgoing cancel failed", failure); return false; }
    }

    private static void sendCommand(JSONObject request) throws Exception {
        Messenger connected = server;
        if (connected == null) throw new IllegalStateException("AirDrop 尚未连接");
        Bundle data = new Bundle(); data.putString(BridgeMessenger.EXTRA_JSON, request.toString());
        Message message = Message.obtain(null, BridgeMessenger.MSG_COMMAND); message.setData(data);
        message.replyTo = CLIENT;
        try { connected.send(message); }
        catch (android.os.RemoteException e) { MAIN.post(() -> disconnected(connected)); throw e; }
    }

    private static void state(Object device, String name) throws Exception {
        symbols.invoke("device.setState", device, symbols.state(name));
    }

    private static void refresh() throws Exception {
        Object instance = symbols.invoke("manager.instance", null);
        symbols.invoke("manager.refresh", instance);
        // Coalesce progress updates so the native ring redraws at a bounded rate.
        if (!refreshScheduled) {
            refreshScheduled = true;
            MAIN.postDelayed(() -> {
                refreshScheduled = false;
                Object[] adapters;
                synchronized (ADAPTERS) { adapters = ADAPTERS.keySet().toArray(); }
                for (Object adapter : adapters) try { symbols.notifyAdapters.invoke(adapter); }
                catch (Exception e) { Log.w(TAG, "adapter refresh failed", e); }
            }, 100);
        }
    }

    private static Uri contentUri(Object data) throws Exception {
        ArrayList<Method> candidates = new ArrayList<>();
        for (Method method : data.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == Uri.class
                    && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) candidates.add(method);
        }
        return (Uri) OShareDexResolver.unique("share data URI getter", candidates).invoke(data);
    }

    private static void toast(String text) {
        Activity current = activity.get();
        Context context = current != null ? current : HostContext.get();
        if (context != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
