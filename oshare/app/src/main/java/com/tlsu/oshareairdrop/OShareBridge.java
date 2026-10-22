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
    private static final Map<String, Long> STARTED = new ConcurrentHashMap<>();
    private static final java.util.Set<String> ACTIVE = ConcurrentHashMap.newKeySet();
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
                MAIN.post(() -> connect(current));
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
                    connectionGeneration++;
                    connecting = false;
                    if (server != null) command("discover_stop", null);
                    DEVICES.clear();
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
            MAIN.post(new Runnable() {
                private ShareMode previous = ShareModeRuntime.current();
                @Override public void run() {
                    ShareMode current = ShareModeRuntime.current();
                    if (current != previous) {
                        previous = current;
                        DEVICES.clear();
                        connectionGeneration++;
                        connecting = false;
                        try { refresh(); } catch (Exception failure) { Log.w(TAG, "mode refresh failed", failure); }
                    }
                    Activity panel = activity.get();
                    if (current.usesOShare() && !ShareModeRuntime.switching() && server == null && !connecting
                            && isCurrentActivity(panel)) connect(panel);
                    MAIN.postDelayed(this, 1000);
                }
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

    private static void connect(Activity current) {
        if (!isCurrentActivity(current) || !ShareModeRuntime.current().usesOShare() || ShareModeRuntime.switching()) return;
        if (server != null && server.getBinder().isBinderAlive()) {
            StartupTrace.mark("SHARE_WARM_DISCOVERY_REQUEST");
            command("discover_start", null);
            return;
        }
        if (connecting) return;
        server = null;
        connecting = true;
        bridgeAttached = false;
        int generation = ++connectionGeneration;
        // A resident provider accepts broadcasts without opening another
        // Activity. Keep the original boot path as a bounded cold fallback.
        try { BridgeMessenger.connect(current, CLIENT); }
        catch (Throwable failure) { Log.w(TAG, "warm bridge admission failed", failure); }
        MAIN.postDelayed(() -> boot(current, generation, 0), 300);
    }

    private static boolean pending(Activity current, int generation) {
        return ShareModeRuntime.current().usesOShare() && !ShareModeRuntime.switching()
                && isCurrentActivity(current) && connecting && generation == connectionGeneration;
    }

    private static void boot(Activity current, int generation, int attempt) {
        if (!pending(current, generation)) return;
        if (bridgeAttached) {
            MAIN.postDelayed(() -> checkConnection(current, generation, 0), 2000);
            return;
        }
        try {
            BridgeMessenger.boot(current, CLIENT);
            Log.i(TAG, "bridge boot requested generation=" + generation);
            MAIN.postDelayed(() -> checkConnection(current, generation, 0), 2000);
        } catch (android.content.ActivityNotFoundException e) {
            // The resident module enables Mosey after observing the sharing screen.
            if (attempt < 4) MAIN.postDelayed(() -> boot(current, generation, attempt + 1), 1500);
            else { connecting = false; toast("AirDrop 服务未启用，请启动底层模块"); }
        } catch (SecurityException t) {
            connecting = false;
            Log.e(TAG, "bridge boot denied", t);
            toast("AirDrop 服务启动被系统拒绝，请查看桥接日志");
        } catch (Throwable t) {
            connecting = false;
            Log.e(TAG, "OShare boot failed", t);
            toast("AirDrop 连接失败: " + t.getClass().getSimpleName());
        }
    }

    private static void checkConnection(Activity current, int generation, int check) {
        if (!pending(current, generation)) return;
        if (check == 4) {
            connecting = false;
            Log.w(TAG, "bridge timeout generation=" + generation + " attached=" + bridgeAttached);
            toast(bridgeAttached ? "AirDrop 桥接已接入，但传输服务尚未就绪，请查看模块日志"
                    : "AirDrop 桥接未响应，请检查 LSPosed 中 Mosey 的加载日志");
            return;
        }
        // Retry admission without reopening an Activity or restarting the wireless stack.
        try { BridgeMessenger.connect(current, CLIENT); }
        catch (Throwable failure) { Log.w(TAG, "bridge admission retry failed", failure); }
        MAIN.postDelayed(() -> checkConnection(current, generation, check + 1), 2000);
    }

    private static void disconnected(Messenger expected) {
        if (server != expected) return;
        server = null;
        serverDeath = null;
        connectionGeneration++;
        connecting = false;
        bridgeAttached = false;
        boolean interrupted = !ACTIVE.isEmpty();
        DEVICES.clear(); ACTIVE.clear(); PHASES.clear(); STARTED.clear();
        try { refresh(); } catch (Throwable failure) { Log.w(TAG, "disconnect refresh failed", failure); }
        if (interrupted) toast("AirDrop 服务连接中断，请重新发送文件");
        Activity current = activity.get();
        if (isCurrentActivity(current)) MAIN.post(() -> connect(current));
    }

    private static boolean receive(Message message) {
        if (message.what == BridgeMessenger.MSG_ATTACHED) {
            if (connecting && isCurrentActivity(activity.get())) bridgeAttached = true;
            return true;
        }
        if (message.what == BridgeMessenger.MSG_HELLO) {
            if (message.replyTo == null || !isCurrentActivity(activity.get())) return true;
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
            command("discover_start", null);
            Entry.instance.log(Log.INFO, TAG, "OShare bridge ready");
            return true;
        }
        if (message.what != BridgeMessenger.MSG_EVENT) return false;
        try {
            JSONObject event = new JSONObject(message.getData().getString(BridgeMessenger.EXTRA_JSON, "{}"));
            String type = event.optString("ev");
            if (event.optBoolean("incoming") || "incoming_transfer".equals(type)
                    || "command_result".equals(type)) return true;
            String key = PREFIX + event.optLong("id");
            if ("target".equals(type) || "target_updated".equals(type)) {
                Object device = DEVICES.get(key);
                if (device == null) device = symbols.deviceConstructor.newInstance();
                Context host = HostContext.get();
                OShareAppearance.apply(host, device, event, symbols);
                Entry.instance.log(Log.INFO, TAG, "discovered icon peer=" + key + " shareTargetType=" + event.optInt("type"));
                symbols.invoke("device.setName", device, event.optString("name", "Apple device"));
                symbols.invoke("device.setKey", device, key);
                symbols.invoke("device.setWifiMac", device, key);
                symbols.invoke("device.setVirtual", device, 0);
                symbols.invoke("device.setFoundTime", device, System.currentTimeMillis());
                if (!ACTIVE.contains(key)) {
                    PHASES.remove(key);
                    state(device, AirDropPresentation.DISCOVERED_STATE);
                }
                DEVICES.put(key, device);
                if (isCurrentActivity(activity.get())) StartupTrace.mark("SHARE_TARGET_UPDATED");
                refresh();
            } else if ("target_lost".equals(type)) {
                if (!ACTIVE.contains(key)) DEVICES.remove(key);
                refresh();
            } else if ("send_requested".equals(type)) {
                // Submission is not confirmation that the receiver is displaying an invitation.
                PHASES.put(key, "正在连接对方");
                refresh();
            } else if ("transfer".equals(type)) {
                boolean terminal = OutgoingTransfers.isTerminal(event.optInt("status"), event.optBoolean("terminal"));
                if (terminal) { ACTIVE.remove(key); STARTED.remove(key); } else ACTIVE.add(key);
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

    private static void send(Intent intent, String key) {
        if (!ShareModeRuntime.current().usesOShare() || ShareModeRuntime.switching()) {
            toast("分享入口正在切换，请稍后重试");return;
        }
        if (ACTIVE.contains(key)) {
            Long started = STARTED.get(key);
            if (started != null && android.os.SystemClock.elapsedRealtime() - started
                    < AirDropPresentation.CLICK_DEBOUNCE_MS) return;
            PHASES.remove(key);
            command("cancel", key);
            try {
                Object device = DEVICES.get(key);
                if (device != null) { state(device, "CANCEL_WAIT"); refresh(); }
            } catch (Exception e) { Log.w(TAG, "cancel UI update failed", e); }
            toast("正在取消当前 AirDrop 任务");
            return;
        }
        try {
            Entry.instance.log(Log.INFO, TAG, "OShare send intent=" + intent.getAction() + " hasStream=" + intent.hasExtra(Intent.EXTRA_STREAM));
            Activity current = activity.get();
            Context context = current != null ? current : HostContext.get();
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
            if (uris.isEmpty() && (text == null || text.length() == 0)) {
                toast("请先选择要分享的文件或文字，再点击设备发送");
                return;
            }
            JSONArray values = new JSONArray();
            ArrayList<ParcelFileDescriptor> descriptors = new ArrayList<>();
            try {
                for (Uri uri : uris) {
                    if (!"content".equals(uri.getScheme())) throw new IllegalArgumentException("分享文件需要 content URI");
                    String name = "shared-file";
                    try (Cursor cursor = context.getContentResolver().query(uri,
                            new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                    }
                    ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r");
                    if (fd == null) throw new IllegalArgumentException("文件无法打开");
                    descriptors.add(fd);
                    values.put(new JSONObject().put("name", name));
                }
                JSONObject request = new JSONObject().put("cmd", uris.isEmpty() ? "send_content" : "send_files")
                        .put("id", Long.parseLong(key.substring(PREFIX.length())))
                        .put("uris", new JSONArray()).put("files", values)
                        .put("text", uris.isEmpty() && text != null ? text.toString() : "");
                Messenger connected = server;
                if (connected == null) throw new IllegalStateException("AirDrop 尚未连接");
                Bundle data = new Bundle();
                data.putString(BridgeMessenger.EXTRA_JSON, request.toString());
                data.putParcelableArrayList("files", descriptors);
                Message message = Message.obtain(null, BridgeMessenger.MSG_COMMAND);
                message.setData(data);
                try { connected.send(message); }
                catch (android.os.RemoteException e) { MAIN.post(() -> disconnected(connected)); throw e; }
            } finally {
                for (ParcelFileDescriptor fd : descriptors) try { fd.close(); } catch (Exception ignored) {}
            }
            ACTIVE.add(key);
            STARTED.put(key, android.os.SystemClock.elapsedRealtime());
            PHASES.put(key, "正在准备发送");
            Object device = DEVICES.get(key);
            if (device != null) { state(device, "TRANSIT_WAIT"); symbols.invoke("device.setTotal", device, Math.max(1, uris.size())); refresh(); }
        } catch (Throwable t) { ACTIVE.remove(key); PHASES.remove(key); STARTED.remove(key); Entry.instance.log(Log.ERROR, TAG, "OShare send failed", t); toast("AirDrop 发送失败: " + t.getMessage()); }
    }

    private static void command(String cmd, String key) {
        try {
            JSONObject request = new JSONObject().put("cmd", cmd);
            if (key != null) request.put("id", Long.parseLong(key.substring(PREFIX.length())));
            sendCommand(request);
        } catch (Throwable t) { Log.w(TAG, "OShare command failed: " + cmd, t); }
    }

    private static void sendCommand(JSONObject request) throws Exception {
        Messenger connected = server;
        if (connected == null) throw new IllegalStateException("AirDrop 尚未连接");
        Bundle data = new Bundle(); data.putString(BridgeMessenger.EXTRA_JSON, request.toString());
        Message message = Message.obtain(null, BridgeMessenger.MSG_COMMAND); message.setData(data);
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
        if (current != null) Toast.makeText(current, text, Toast.LENGTH_LONG).show();
    }
}
