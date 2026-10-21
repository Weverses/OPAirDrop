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
    private static Messenger server;
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static boolean connecting;
    private static long lastBoot;
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
                ArrayList<Object> result = new ArrayList<>((java.util.List<?>) chain.proceed());
                result.addAll(DEVICES.values());
                return result;
            }));
            HOOKS.add(module.hook(symbols.method("manager.get")).intercept(chain -> {
                Object device = DEVICES.get((String) chain.getArg(0));
                return device != null ? device : chain.proceed();
            }));
            HOOKS.add(module.hook(symbols.method("activity.resume")).intercept(chain -> {
                Object result = chain.proceed();
                Activity current = (Activity) chain.getThisObject();
                activity = new WeakReference<>(current);
                OShareAppearance.renameSection(current);
                MAIN.postDelayed(() -> connect(current, 0), 1200);
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
                    command("discover_stop", null);
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
        } catch (Throwable failure) {
            for (HookHandle handle : HOOKS) try { handle.unhook(); }
            catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            HOOKS.clear();
            throw new IllegalStateException("OShare hook installation rolled back", failure);
        }
    }

    private static void connect(Activity current, int attempt) {
        if (current.isFinishing() || current.isDestroyed()) return;
        if (server != null && server.getBinder().isBinderAlive()) {
            command("discover_start", null);
            return;
        }
        if (connecting && attempt == 0) return;
        server = null;
        long wait = 8000 - (android.os.SystemClock.elapsedRealtime() - lastBoot);
        if (attempt == 0 && wait > 0) {
            MAIN.postDelayed(() -> connect(current, 0), wait);
            return;
        }
        connecting = true;
        try {
            Intent boot = new Intent().setClassName(Entry.HOST, Entry.HOST + ".MoseyPermissionActivity");
            boot.putExtra(Entry.BOOT_EXTRA, true);
            boot.putExtra(BridgeMessenger.EXTRA_CLIENT, CLIENT);
            current.startActivity(boot);
            lastBoot = android.os.SystemClock.elapsedRealtime();
            MAIN.postDelayed(() -> {
                connecting = false;
                if (server == null) toast("AirDrop 未连接，请检查 LSPosed 的 Mosey 作用域和底层服务");
            }, 7000);
        } catch (android.content.ActivityNotFoundException e) {
            // The resident module enables Mosey after observing the sharing screen.
            if (attempt < 4) MAIN.postDelayed(() -> connect(current, attempt + 1), 1500);
            else { connecting = false; toast("AirDrop 服务未启用，请启动底层模块"); }
        } catch (Throwable t) {
            connecting = false;
            Log.e(TAG, "OShare boot failed", t);
            toast("AirDrop 连接失败: " + t.getClass().getSimpleName());
        }
    }

    private static boolean receive(Message message) {
        if (message.what == BridgeMessenger.MSG_HELLO) {
            boolean reconnected = server == null || !server.getBinder().equals(message.replyTo.getBinder());
            server = message.replyTo;
            if (reconnected) { ACTIVE.clear(); PHASES.clear(); STARTED.clear(); }
            connecting = false;
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
                if (server == null) throw new IllegalStateException("AirDrop 尚未连接");
                Bundle data = new Bundle();
                data.putString(BridgeMessenger.EXTRA_JSON, request.toString());
                data.putParcelableArrayList("files", descriptors);
                Message message = Message.obtain(null, BridgeMessenger.MSG_COMMAND);
                message.setData(data);
                try { server.send(message); }
                catch (android.os.RemoteException e) { server = null; throw e; }
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
        if (server == null) throw new IllegalStateException("AirDrop 尚未连接");
        Bundle data = new Bundle(); data.putString(BridgeMessenger.EXTRA_JSON, request.toString());
        Message message = Message.obtain(null, BridgeMessenger.MSG_COMMAND); message.setData(data);
        try { server.send(message); }
        catch (android.os.RemoteException e) { server = null; throw e; }
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
