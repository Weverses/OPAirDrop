package com.tlsu.oshareairdrop;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the installed OShare Seedling template without modifying or shipping it.
 *
 * <p>The SDK/card host continues loading the original signed UPK from OShare's
 * manifest metadata. This class installs no asset, provider or verification
 * hooks. ready() proves the expected original resource is available; the
 * presenter must additionally check the SDK card's actual version before
 * enabling any interactive affordance, since a card host can retain older UPKs.
 *
 * <p>The baseline index page has a task-scoped cancel/progress action, while
 * Accept/Decline omit identity. The presenter may use that original capsule and
 * must hide unsafe confirmation buttons, keeping its session-aware invitation
 * popup/notification. An instanceId action epoch belongs to exactly one task and
 * phase; native OShare events without that marker remain native events.
 */
public final class OShareSeedlingAssets {
    private static final String PACKAGE = "com.coloros.oshare";
    private static final String ASSET = "OShareConfirm2.0.upk";
    private static final String ACTION_URI = "com.oplus.oshare.cardwidget";
    private static final int MAX_UPK = 16 * 1024 * 1024;
    private static final int MAX_EXPANDED = 32 * 1024 * 1024;
    private static volatile boolean prepared, cancelScoped;
    private static volatile long version;
    private static volatile String defaults = "{}";
    private static volatile String status = "not prepared";

    private OShareSeedlingAssets() {}

    /** Availability only; it does not acknowledge which resource the host rendered. */
    public static boolean ready() { return prepared; }
    public static long versionCode() { return version; }
    public static boolean scopedCancel() { return prepared && cancelScoped; }
    public static String status() { return status; }

    /** Baseline confirmations are unscoped; never enable their native buttons. */
    public static boolean supportsScopedConfirm() { return false; }

    /** A new independent copy preserves native default types and resource bindings. */
    public static JSONObject data() {
        try { return new JSONObject(defaults); }
        catch (Exception unavailable) { return new JSONObject(); }
    }

    public static synchronized boolean prepare(Context context) {
        if (prepared) return true;
        try {
            Context nativeContext = PACKAGE.equals(context.getPackageName()) ? context
                    : context.createPackageContext(PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            byte[] bytes;
            try (InputStream input = nativeContext.getAssets().open(ASSET, AssetManager.ACCESS_BUFFER)) {
                bytes = read(input, MAX_UPK);
            }
            JSONObject config = null, index = null, index2 = null;
            Set<String> names = new HashSet<>();
            int expanded = 0;
            try (ZipInputStream input = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
                for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                    String name = entry.getName();
                    if (!names.add(name) || name.startsWith("/") || name.contains("../"))
                        throw new IllegalArgumentException("unexpected UPK entry");
                    byte[] contents = read(input, MAX_EXPANDED - expanded);
                    expanded = Math.addExact(expanded, contents.length);
                    switch (name) {
                        case "config.json": config = json(contents); break;
                        case "pages/index.json": index = json(contents); break;
                        case "pages/index2.json": index2 = json(contents); break;
                        default: break;
                    }
                }
            }
            if (config == null || index == null || index2 == null)
                throw new IllegalArgumentException("incomplete native receive UPK");
            JSONObject identifier = config.getJSONObject("identifier");
            if (!"OShareConfirm2.0".equals(identifier.getString("name"))
                    || !PACKAGE.equals(identifier.getString("url"))
                    || !"268451904".equals(identifier.getString("id")))
                throw new IllegalArgumentException("unrecognized native UPK identity");
            long originalVersion = identifier.getLong("versionCode");
            if (originalVersion <= 0 || index.getJSONArray("template").length() == 0
                    || index2.getJSONArray("template").length() == 0)
                throw new IllegalArgumentException("invalid native UPK version or layout");
            JSONObject nativeDefaults = index.getJSONObject("data");
            // Parse both native pages, but only index is requested by our
            // presenter. Its complete interactive tree must satisfy our exact
            // hiding controls. Unused index2 may gate buttons on wrappers and
            // must not determine index's capability from its own shown fields.
            index.getJSONObject("actions");
            index2.getJSONObject("data");
            index2.getJSONObject("actions");
            int[] indexActions = validateTemplate(index);
            cancelScoped = indexActions[2] == 1 && scopedAction(index, "btnOnClick", "cancel");
            defaults = nativeDefaults.toString();
            version = originalVersion;
            prepared = true;
            status = cancelScoped ? "original capsule available; confirmation uses invitation UI"
                    : "original template available; interactive capsule unavailable";
            Log.i(Entry.TAG, "Seedling native asset version=" + version + " scopedCancel=" + cancelScoped
                    + "; original resources and signatures unchanged");
            return true;
        } catch (Throwable failure) {
            prepared = false; cancelScoped = false; version = 0; defaults = "{}";
            status = "native Seedling resource unavailable: " + failure.getClass().getSimpleName();
            Log.w(Entry.TAG, status, failure);
            return false;
        }
    }

    private static boolean scopedAction(JSONObject page, String name, String event) {
        JSONObject actions = page.optJSONObject("actions");
        JSONObject action = actions == null ? null : actions.optJSONObject(name);
        JSONObject params = action == null ? null : action.optJSONObject("params");
        return params != null && "message".equals(action.optString("type"))
                && ACTION_URI.equals(action.optString("uri"))
                && event.equals(params.optString("event"))
                && "{{instanceId}}".equals(params.optString("instanceId"));
    }

    private static int[] validateTemplate(JSONObject page) throws Exception {
        int[] counts = new int[3];
        validateElements(page.getJSONArray("template"), page, counts);
        if (counts[0] == 0 || counts[1] == 0)
            throw new IllegalArgumentException("unrecognized native confirmation layout");
        return counts;
    }

    /** Every interactive element must be hidden by our exact boolean controls,
     * or be the known task-scoped progress ring. Unknown events fail closed. */
    private static void validateElements(Object value, JSONObject page, int[] counts) throws Exception {
        if (value instanceof JSONObject object) {
            if (object.has("events")) {
                JSONObject events = object.getJSONObject("events");
                if (events.length() != 1 || !(events.opt("click") instanceof String))
                    throw new IllegalArgumentException("unexpected native interactive event");
                String name = events.getString("click");
                JSONObject action = page.getJSONObject("actions").getJSONObject(name);
                JSONObject params = action.getJSONObject("params");
                if (!"message".equals(action.optString("type"))
                        || !ACTION_URI.equals(action.optString("uri")))
                    throw new IllegalArgumentException("unexpected native interactive target");
                String event = params.optString("event");
                Object shown = object.opt("shown");
                if ("accept".equals(event)) {
                    if (!"{{showConfirm}}".equals(shown) && !"{{isE4Show}}".equals(shown))
                        throw new IllegalArgumentException("native Accept cannot be safely hidden");
                    counts[0]++;
                } else if ("decline".equals(event)) {
                    if (!"{{showCancel}}".equals(shown))
                        throw new IllegalArgumentException("native Decline cannot be safely hidden");
                    counts[1]++;
                } else if ("cancel".equals(event)) {
                    JSONObject attr = object.getJSONObject("attr");
                    if (!"btnOnClick".equals(name) || !"widget".equals(object.optString("type"))
                            || !"progress".equals(attr.optString("type"))
                            || !"{{isShowProcess}}".equals(shown)
                            || !scopedAction(page, name, "cancel"))
                        throw new IllegalArgumentException("native progress action is not scoped");
                    counts[2]++;
                } else {
                    throw new IllegalArgumentException("unrecognized native interactive action");
                }
            }
            for (Iterator<String> keys = object.keys(); keys.hasNext();)
                validateElements(object.get(keys.next()), page, counts);
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) validateElements(array.get(i), page, counts);
        }
    }

    private static JSONObject json(byte[] bytes) throws Exception {
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static byte[] read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (count > limit - output.size()) throw new IllegalArgumentException("UPK exceeds size limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
