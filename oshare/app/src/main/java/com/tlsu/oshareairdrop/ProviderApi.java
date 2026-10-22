package com.tlsu.oshareairdrop;

import android.util.Log;

import org.json.JSONObject;
import org.json.JSONArray;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import java.io.InputStream;
import java.util.UUID;
import android.provider.OpenableColumns;
import android.database.Cursor;
import java.util.ArrayList;
import java.util.List;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reflective client for the current Mosey sharing controller (apl, implemented by cad).
 *
 * Current Mosey 1.0.980159239 uses:
 *   apl.g(aps discoveryCallback, apk options)  -> startDiscovery
 *   apl.i()                                    -> stopDiscovery
 *   apl.f(apj options, aqa transferCallback)   -> startAdvertising/listening
 *   apl.h()                                    -> stopAdvertising
 *   apl.d(String localDeviceName, ShareTarget with attachments, aqa) -> send
 *   apl.a/b/c(ShareTarget)                     -> accept/cancel/deny
 *   apl.m(String path)                         -> setDownloadDirectory
 */
public final class ProviderApi {
    private static final String TAG = "OShareAirDrop";

    private static final Map<Long, Object> TARGETS = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> TARGET_TYPES = new ConcurrentHashMap<>();

    public static void rememberTargetType(long id, int type) {
        if (type == 1 || type == 2 || type == 3 || type == 4 || type == 6) TARGET_TYPES.put(id, type);
    }

    private static final OutgoingTransfers<File> OUTGOING = new OutgoingTransfers<>();
    private static final Map<OutgoingTransfers.Lease<File>, Object> OUTGOING_TARGETS = new ConcurrentHashMap<>();
    private static Object discoveryCallback;
    private static Object transferCallback;

    private static boolean discovering;
    private static volatile boolean panelDiscovery, receiverDiscovery;
    private static boolean advertising;

    private ProviderApi() {}

    public interface EventSink {
        void event(JSONObject json);
    }

    private static final CopyOnWriteArrayList<EventSink> SINKS = new CopyOnWriteArrayList<>();

    public static void setSink(EventSink value) {
        SINKS.addIfAbsent(value);
    }

    static void emit(String type, JSONObject payload) {
        try {
            JSONObject json = payload == null ? new JSONObject() : payload;
            json.put("ev", type);
            for (EventSink s : SINKS) {
                try { s.event(json); } catch (Throwable t) { Log.w(TAG, "sink failed", t); }
            }
            Log.i(TAG, "event " + json);
            if (Entry.instance != null) Entry.instance.log(Log.INFO, TAG,
                    "event=" + type + " id=" + json.optLong("id")
                            + " status=" + json.optInt("status") + " files=" + (json.optJSONArray("files") == null
                            ? json.optInt("files") : json.optJSONArray("files").length())
                            + " progress=" + json.optDouble("progress")
                            + ("error".equals(type) ? " " + json.optString("message") : ""));
        } catch (Throwable t) {
            Log.w(TAG, "emit failed", t);
        }
    }

    private static ClassLoader cl() {
        Object p = ProviderHolder.get();
        ClassLoader loader = p != null ? p.getClass().getClassLoader() : ProviderHolder.loader();
        if (loader == null) throw new IllegalStateException("host classloader unavailable");
        return loader;
    }

    private static Object provider() {
        Object p = ProviderHolder.get();
        if (p == null) throw new IllegalStateException("Mosey sharing controller not captured yet");
        return p;
    }

    private static Class<?> cls(String name) {
        try {
            return Class.forName(name, false, cl());
        } catch (Throwable t) {
            throw new IllegalStateException("class not found: " + name, t);
        }
    }

    private static Object newInstance(String name, Class<?>[] signature, Object... args) {
        try {
            Class<?> c = cls(name);
            Constructor<?> ctor = c.getDeclaredConstructor(signature);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot construct " + name, t);
        }
    }

    private static int invokeInt(String method, Class<?>[] signature, Object... args) {
        try {
            Object p = provider();
            Method m = p.getClass().getDeclaredMethod(method, signature);
            m.setAccessible(true);
            Object result = ShareMode.bridgeCall(() ->
                    (method.equals("g") || method.equals("i") || method.equals("k"))
                    ? DiscoverySession.internal(() -> m.invoke(p, args)) : m.invoke(p, args));
            int code = result instanceof Number ? ((Number) result).intValue() : 0;
            Log.i(TAG, "apl." + method + " -> " + code);
            return code;
        } catch (Throwable t) {
            throw new IllegalStateException("apl." + method + " failed", t);
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            throw new IllegalStateException("field " + name + " failed on " + target.getClass(), t);
        }
    }

    private static long longField(Object target, String name) {
        Object value = field(target, name);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static int intField(Object target, String name) {
        Object value = field(target, name);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static String stringField(Object target, String name) {
        Object value = field(target, name);
        return value == null ? "" : String.valueOf(value);
    }

    private static synchronized Object ensureDiscoveryCallback() {
        if (discoveryCallback != null) return discoveryCallback;
        try {
            discoveryCallback = CallbackFactory.create(cl(), "aps", ProviderApi::handleDiscoveryCallback);
        } catch (Throwable t) {
            Log.e(TAG, "cannot create discovery callback", t);
            throw new IllegalStateException("cannot create discovery callback: " + t, t);
        }
        return discoveryCallback;
    }

    private static synchronized Object ensureTransferCallback() {
        if (transferCallback != null) return transferCallback;
        try {
            transferCallback = CallbackFactory.create(cl(), "aqa", ProviderApi::handleTransferCallback);
        } catch (Throwable t) {
            Log.e(TAG, "cannot create transfer callback", t);
            throw new IllegalStateException("cannot create transfer callback: " + t, t);
        }
        return transferCallback;
    }

    private static Object handleDiscoveryCallback(String method, Object[] args) {
        Object target = args != null && args.length > 0 ? args[0] : null;
        if (target == null) return null;
        switch (method) {
            case "a":
                onTargetFound(target);
                break;
            case "c":
                onTargetLost(target);
                break;
            case "d":
                try {
                    TARGETS.put(longField(target, "a"), target);
                    JSONObject json = targetJson(target);
                    json.put("id", longField(target, "a"));
                    emit("target_updated", json);
                } catch (Throwable ignored) {
                }
                break;
            case "b":
            default:
                break;
        }
        return null;
    }

    private static Object handleTransferCallback(String method, Object[] args) {
        return handleTransferCallback(method, args, null);
    }

    private static Object handleTransferCallback(String method, Object[] args,
            OutgoingTransfers.Lease<File> lease) {
        if (args == null || args.length < 2 || args[0] == null || args[1] == null) return null;
        if (lease == null) {
            try {
                if (Boolean.TRUE.equals(field(args[0], "f"))) {
                    IncomingReceiver.onTransfer(args[0], args[1]);
                    return null;
                }
            } catch (Throwable failure) { Log.w(TAG, "incoming direction unavailable", failure); }
        }
        // Ignore delayed callbacks belonging to an earlier transfer to this peer.
        if (lease != null && !OUTGOING.isCurrent(lease)) return null;
        Object target = args[0];
        Object metadata = args[1];
        boolean terminal = false;
        File completedCache = null;
        try {
            int status = intField(metadata, "a");
            terminal = OutgoingTransfers.isTerminal(status, false);
            if (!terminal) terminal = Boolean.TRUE.equals(field(metadata, "e"));
            long id = lease == null ? longField(target, "a") : lease.targetId;
            if (terminal && lease != null && OUTGOING.finish(lease)) {
                // Release ownership before notifying clients; rendering must not retain the lease.
                OUTGOING_TARGETS.remove(lease);
                completedCache = lease.resource;
            }
            TARGETS.put(id, target);
            JSONObject json = targetJson(target);
            json.put("incoming", Boolean.TRUE.equals(field(target, "f")));
            json.put("status", status);
            json.put("terminal", terminal);
            if (lease != null) json.put("session", lease.session);
            json.put("progress", OutgoingTransfers.progress(((Number) field(metadata, "b")).doubleValue()));
            json.put("timestamp", longField(metadata, "h"));
            json.put("remainingBytes", longField(metadata, "s"));
            emit("transfer", json);
        } catch (Throwable t) {
            Log.w(TAG, "transfer callback parse failed", t);
            if (Entry.instance != null) Entry.instance.log(Log.WARN, TAG, "transfer callback parse failed", t);
        } finally {
            // Cleanup also runs when optional metadata, JSON or a client callback fails.
            if (terminal && lease != null) {
                if (OUTGOING.finish(lease)) {
                    OUTGOING_TARGETS.remove(lease);
                    completedCache = lease.resource;
                }
                deleteTree(completedCache);
            }
        }
        return null;
    }

    private static void onTargetFound(Object target) {
        try {
            long id = longField(target, "a");
            TARGETS.put(id, target);
            JSONObject json = targetJson(target);
            json.put("id", id);
            emit("target", json);
        } catch (Throwable t) {
            Log.w(TAG, "target parse failed", t);
        }
    }

    private static void onTargetLost(Object target) {
        try {
            long id = longField(target, "a");
            if (!OUTGOING.isActive(id) && !IncomingReceiver.active(id)) TARGETS.remove(id);
            JSONObject json = targetJson(target);
            json.put("id", id);
            emit("target_lost", json);
        } catch (Throwable t) {
            Log.w(TAG, "target lost parse failed", t);
        }
    }

    static JSONObject targetJson(Object target) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", longField(target, "a"));
        json.put("name", stringField(target, "b"));
        json.put("fullName", stringField(target, "g"));
        json.put("model", stringField(target, "p"));
        json.put("deviceId", stringField(target, "l"));
        long id = longField(target, "a");
        int type = intField(target, "d");
        rememberTargetType(id, type);
        json.put("type", type == 0 ? TARGET_TYPES.getOrDefault(id, 0) : type);
        json.put("desc", String.valueOf(target));
        return json;
    }

    public static synchronized void startDiscovery() {
        boolean previous = panelDiscovery;
        panelDiscovery = true;
        try { ensureDiscovery(); }
        catch (RuntimeException failure) { panelDiscovery = previous; throw failure; }
    }

    static boolean discoveryWanted(Object controller) {
        return controller != null && controller == ProviderHolder.get() && (panelDiscovery || receiverDiscovery);
    }

    static synchronized void startReceiverDiscovery() {
        receiverDiscovery = true;
        try { ensureDiscovery(); } catch (RuntimeException failure) { receiverDiscovery = false; throw failure; }
    }

    static synchronized void stopReceiverDiscovery() {
        receiverDiscovery = false;
        if (!panelDiscovery) haltDiscovery();
    }

    static boolean hasOutgoing() { return OUTGOING.hasActive() || !OUTGOING_TARGETS.isEmpty() || BridgeMessenger.preparingOutgoing(); }

    private static void ensureDiscovery() {
        if (discovering) {
            for (Object target : TARGETS.values()) onTargetFound(target);
            return;
        }
        Object options = newInstance("apk",
                new Class<?>[]{int.class, boolean.class, boolean.class, String.class},
                1, false, false, "OPAirShare");
        int code = invokeInt("g",
                new Class<?>[]{cls("aps"), cls("apk")}, ensureDiscoveryCallback(), options);
        if (code != 0) throw new IllegalStateException("startDiscovery returned " + code);
        discovering = true;
        try {
            JSONObject json = new JSONObject();
            json.put("code", code);
            emit("discovery_started", json);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void stopDiscovery() {
        panelDiscovery = false;
        if (!receiverDiscovery) haltDiscovery();
    }

    private static void haltDiscovery() {
        if (!discovering) return;
        int code = invokeInt("i", new Class<?>[]{});
        if (code != 0) throw new IllegalStateException("stopDiscovery returned " + code);
        discovering = false;
        try {
            JSONObject json = new JSONObject();
            json.put("code", code);
            emit("discovery_stopped", json);
        } catch (Throwable ignored) {
        }
    }

    static String localDeviceName() {
        String stored = null, market = null;
        try { stored = android.provider.Settings.Global.getString(HostContext.get().getContentResolver(), AirDropName.SETTING); }
        catch (Exception failure) { Log.w(TAG, "custom AirDrop name unavailable", failure); }
        try { market = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class)
                .invoke(null, "ro.vendor.oplus.market.enname", ""); }
        catch (Exception failure) { Log.w(TAG, "market device name unavailable", failure); }
        return AirDropName.resolve(stored, market, android.os.Build.MODEL);
    }

    static synchronized void updateOwnedAdvertising(String name) {
        Object options = newInstance("apj", new Class<?>[]{int.class, String.class, boolean.class, boolean.class},
                0, name, true, false);
        int code = invokeInt("j", new Class<?>[]{cls("apj")}, options);
        if (code != 0) throw new IllegalStateException("updateAdvertising returned " + code);
        emit("advertising_updated", new JSONObject());
    }

    static synchronized void startOwnedAdvertising(String name) {
        // A previous caller may have installed a different callback on this controller.
        advertising = false;
        startAdvertising(name);
    }

    public static synchronized void startAdvertising(String name) {
        if (advertising) return;
        String deviceName = name == null || name.isEmpty() ? "OPAirShare" : name;
        Object options = newInstance("apj",
                new Class<?>[]{int.class, String.class, boolean.class, boolean.class},
                0, deviceName, true, false);
        int code = invokeInt("f",
                new Class<?>[]{cls("apj"), cls("aqa")}, options, ensureTransferCallback());
        if (code != 0) throw new IllegalStateException("startAdvertising returned " + code);
        advertising = true;
        try {
            JSONObject json = new JSONObject();
            json.put("code", code);
            emit("advertising_started", json);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void stopAdvertising() {
        if (!advertising) return;
        int code = invokeInt("h", new Class<?>[]{});
        if (code != 0) throw new IllegalStateException("stopAdvertising returned " + code);
        advertising = false;
        try {
            JSONObject json = new JSONObject();
            json.put("code", code);
            emit("advertising_stopped", json);
        } catch (Throwable ignored) {
        }
    }

    private static Object copyTarget(long targetId) throws Exception {
        Object original = TARGETS.get(targetId);
        if (original == null) throw new IllegalStateException("unknown target id " + targetId);
        // clone() is shallow: round-trip through Parcel to avoid sharing attachment lists.
        Parcel parcel = Parcel.obtain();
        try {
            Class<?> type = original.getClass();
            type.getMethod("writeToParcel", Parcel.class, int.class).invoke(original, parcel, 0);
            parcel.setDataPosition(0);
            Parcelable.Creator<?> creator = (Parcelable.Creator<?>) type.getField("CREATOR").get(null);
            Object target = creator.createFromParcel(parcel);
            type.getMethod("l").invoke(target);
            return target;
        } finally { parcel.recycle(); }
    }

    public static synchronized void sendContent(JSONArray uris, String text, long targetId) {
        sendContent(uris, text, targetId, OUTGOING.begin(targetId, null));
    }

    public static synchronized void sendContent(JSONArray uris, String text, long targetId, String session) {
        sendContent(uris, text, targetId, OUTGOING.begin(targetId, null, session));
    }

    private static synchronized void sendContent(JSONArray uris, String text, long targetId,
            OutgoingTransfers.Lease<File> lease) {
        boolean submitted = false;
        try {
            Object target = copyTarget(targetId);
            for (int index = 0; index < uris.length(); index++) {
                Uri uri = Uri.parse(uris.getString(index));
                String name = uri.getLastPathSegment();
                long size = -1;
                String mime = "application/octet-stream";
                if ("file".equals(uri.getScheme())) {
                    File file = new File(uri.getPath());
                    if (!file.isFile() || !file.canRead()) throw new IllegalArgumentException("unreadable file");
                    name = file.getName(); size = file.length();
                    String extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString());
                    String resolvedMime = android.webkit.MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(extension.toLowerCase(java.util.Locale.ROOT));
                    if (resolvedMime != null) mime = resolvedMime;
                } else if ("content".equals(uri.getScheme())) {
                    android.content.ContentResolver resolver = HostContext.get().getContentResolver();
                    String resolvedMime = resolver.getType(uri);
                    if (resolvedMime != null) mime = resolvedMime;
                    try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME,
                            OpenableColumns.SIZE}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            name = cursor.getString(0);
                            if (!cursor.isNull(1)) size = cursor.getLong(1);
                        }
                    }
                } else throw new IllegalArgumentException("unsupported URI scheme");
                if (size < 0) throw new IllegalArgumentException("file size unavailable: " + name);
                int type = mime.startsWith("image/") ? 1 : mime.startsWith("video/") ? 2
                        : mime.startsWith("audio/") ? 4 : 5;
                Object attachment = newInstance("com.google.android.gms.nearby.sharing.FileAttachment",
                        new Class<?>[]{String.class, int.class, long.class, Uri.class,
                                android.os.Bundle.class, String.class, long.class, String.class,
                                long.class, long.class, boolean.class},
                        name, type, size, uri, null, mime, System.nanoTime(), null, 0L, 0L, false);
                target.getClass().getMethod("i", attachment.getClass()).invoke(target, attachment);
            }
            if (text != null && !text.isEmpty()) {
                Object attachment = newInstance("com.google.android.gms.nearby.sharing.TextAttachment",
                        new Class<?>[]{String.class, int.class, long.class, android.os.Bundle.class,
                                String.class, long.class, boolean.class},
                        text, 0, (long) text.getBytes(StandardCharsets.UTF_8).length,
                        null, "Shared text", System.nanoTime(), false);
                target.getClass().getMethod("k", attachment.getClass()).invoke(target, attachment);
            }
            if (uris.length() == 0 && (text == null || text.isEmpty()))
                throw new IllegalArgumentException("no attachments");
            Object callback = CallbackFactory.create(cl(), "aqa",
                    (method, args) -> handleTransferCallback(method, args, lease));
            OUTGOING_TARGETS.put(lease, target);
            int code = invokeInt("d", new Class<?>[]{String.class,
                    cls("com.google.android.gms.nearby.sharing.ShareTarget"), cls("aqa")},
                    localDeviceName(), target, callback);
            if (code != 0) throw new IllegalStateException("send returned " + code);
            submitted = true;
            emit("send_requested", new JSONObject().put("id", targetId).put("code", code)
                    .put("session", lease.session)
                    .put("files", uris.length()).put("hasText", text != null && !text.isEmpty()));
        } catch (Throwable t) { throw new IllegalStateException("cannot send attachments", t); }
        finally {
            if (!submitted && OUTGOING.finish(lease)) {
                OUTGOING_TARGETS.remove(lease);
            }
        }
    }

    public static void sendFiles(JSONArray names, List<ParcelFileDescriptor> files, long targetId) {
        sendFiles(names, files, targetId, UUID.randomUUID().toString());
    }

    public static void sendFiles(JSONArray names, List<ParcelFileDescriptor> files, long targetId, String session) {
        if (files == null || files.isEmpty() || names.length() != files.size())
            throw new IllegalArgumentException("file descriptor count differs");
        File directory = new File(HostContext.get().getCacheDir(), "oshare-outgoing/" + UUID.randomUUID());
        if (!directory.mkdirs()) throw new IllegalStateException("cannot create outgoing cache");
        boolean submitted = false;
        OutgoingTransfers.Lease<File> lease = null;
        try {
            lease = OUTGOING.begin(targetId, directory, session);
            JSONArray uris = new JSONArray();
            for (int i = 0; i < files.size(); i++) {
                String name = names.getJSONObject(i).optString("name", "shared-file");
                name = new File(name).getName();
                if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "shared-file";
                File file = new File(directory, name);
                int duplicate = 1;
                while (file.exists()) file = new File(directory, duplicate++ + "-" + name);
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(files.get(i));
                     FileOutputStream output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                uris.put(Uri.fromFile(file).toString());
            }
            sendContent(uris, null, targetId, lease);
            submitted = true;
        } catch (Exception e) {
            throw new IllegalStateException("cannot stage shared files", e);
        } finally {
            if (!submitted) {
                if (lease != null) OUTGOING.finish(lease);
                deleteTree(directory);
            }
        }
    }

    private static void deleteTree(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) Log.w(TAG, "outgoing cache cleanup deferred");
    }

    public static synchronized void sendPath(String path, long targetId) {
        sendContent(new JSONArray().put(Uri.fromFile(new File(path)).toString()), null, targetId);
    }

    public static synchronized void sendTestFile(long targetId) {
        try {
            File file = new File(HostContext.get().getCacheDir(), "oshare-airdrop-test.txt");
            String content = "OShare AirDrop Phase0 test\n" + System.currentTimeMillis() + "\n";
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            sendPath(file.getAbsolutePath(), targetId);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot create test file", t);
        }
    }

    static boolean nativeReceiveBusy() throws Exception {
        Object receiver = field(provider(), "b");
        Object state = field(receiver, "k");
        Method get = state.getClass().getMethod("b"); get.setAccessible(true);
        for (Object transfer : ((Map<?, ?>) get.invoke(state)).values()) {
            Object metadata = field(transfer, "f");
            if (!Boolean.TRUE.equals(field(metadata, "e"))) return true;
        }
        return false;
    }

    static boolean receiveExists(long id) throws Exception {
        Object receiver = field(provider(), "b");
        Object state = field(receiver, "k");
        Method get = state.getClass().getMethod("b");
        get.setAccessible(true);
        return ((Map<?, ?>) get.invoke(state)).containsKey(id);
    }

    static void receiveAction(String action, Object target) {
        int code = invokeInt(action,
                new Class<?>[]{cls("com.google.android.gms.nearby.sharing.ShareTarget")}, target);
        if (code != 0) throw new IllegalStateException("receive action " + action + " returned " + code);
    }

    public static synchronized void accept(long targetId) {
        Object target = TARGETS.get(targetId);
        if (target == null) throw new IllegalStateException("unknown target id " + targetId);
        int code = invokeInt("a",
                new Class<?>[]{cls("com.google.android.gms.nearby.sharing.ShareTarget")}, target);
        if (code != 0) throw new IllegalStateException("accept returned " + code);
        emitCode("accepted", code);
    }

    public static synchronized void deny(long targetId) {
        Object target = TARGETS.get(targetId);
        if (target == null) throw new IllegalStateException("unknown target id " + targetId);
        int code = invokeInt("c",
                new Class<?>[]{cls("com.google.android.gms.nearby.sharing.ShareTarget")}, target);
        if (code != 0) throw new IllegalStateException("deny returned " + code);
        emitCode("denied", code);
    }

    public static synchronized void cancel(long targetId) {
        OutgoingTransfers.Lease<File> lease = OUTGOING.current(targetId);
        Object target = lease == null ? null : OUTGOING_TARGETS.get(lease);
        if (target == null) target = TARGETS.get(targetId);
        if (target == null) throw new IllegalStateException("unknown target id " + targetId);
        int code = invokeInt("b",
                new Class<?>[]{cls("com.google.android.gms.nearby.sharing.ShareTarget")}, target);
        if (code != 0) throw new IllegalStateException("cancel returned " + code);
        emitCode("cancelled", code);
    }

    /** Delayed UI actions must never cancel a newer lease or an incoming task. */
    public static synchronized void cancel(long targetId, String session) {
        if (!OUTGOING.matches(targetId, session)) throw new IllegalStateException("outgoing session expired");
        cancel(targetId);
    }

    public static synchronized void setDownloadDirectory(String path) {
        int code = invokeInt("m", new Class<?>[]{String.class}, path);
        emitCode("download_dir_set", code);
    }

    private static void emitCode(String type, int code) {
        try {
            JSONObject json = new JSONObject();
            json.put("code", code);
            emit(type, json);
        } catch (Throwable ignored) {
        }
    }
}
