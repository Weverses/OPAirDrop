package com.tlsu.oshareairdrop;

import android.content.Context;
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

/** Read-only contract for the installed, signed native sender template. */
final class OShareSendingAssets {
    static final String ACTION = "pantanal.intent.business.app.system.OSHARE_SENDER_PROCESS";
    private static volatile boolean ready;
    private static long version;
    private static String defaults = "{}";
    static boolean ready() { return ready; }
    static long versionCode() { return version; }
    static JSONObject data() throws Exception { return new JSONObject(defaults); }

    static synchronized boolean prepare(Context context) {
        if (ready) return true;
        try {
            Context nativeContext = "com.coloros.oshare".equals(context.getPackageName()) ? context
                    : context.createPackageContext("com.coloros.oshare", Context.CONTEXT_IGNORE_SECURITY);
            byte[] upk;
            try (InputStream input = nativeContext.getAssets().open("OShareSending2.0.upk")) {
                upk = read(input, 16 * 1024 * 1024);
            }
            JSONObject config = null, page = null;
            Set<String> entries = new HashSet<>();
            int expanded = 0;
            try (ZipInputStream input = new ZipInputStream(new java.io.ByteArrayInputStream(upk))) {
                for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                    String name = entry.getName();
                    if (!entries.add(name) || name.startsWith("/") || name.contains("../"))
                        throw new IllegalArgumentException("unexpected sender UPK entry");
                    byte[] bytes = read(input, 32 * 1024 * 1024 - expanded);
                    expanded = Math.addExact(expanded, bytes.length);
                    if (name.equals("config.json")) config = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    if (name.equals("pages/index.json")) page = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                }
            }
            if (config == null || page == null) throw new IllegalArgumentException("incomplete sender UPK");
            JSONObject id = config.getJSONObject("identifier");
            if (!"OShareSending2.0".equals(id.getString("name"))
                    || !"268451905".equals(id.getString("id"))
                    || !"com.coloros.oshare".equals(id.getString("url")) || id.getLong("versionCode") <= 0)
                throw new IllegalArgumentException("unrecognized sender UPK identity");
            JSONArray actions = config.getJSONObject("intent").getJSONArray("action");
            boolean sending = false;
            for (int i = 0; i < actions.length(); i++) sending |= ACTION.equals(actions.optString(i));
            if (!sending) throw new IllegalArgumentException("sender intent unavailable");
            validatePage(page);
            defaults = page.getJSONObject("data").toString();
            version = id.getLong("versionCode");
            ready = true;
            return true;
        } catch (Throwable failure) {
            Log.w(Entry.TAG, "Native sender template unavailable; using notification", failure);
            return false;
        }
    }

    static void validatePage(JSONObject page) throws Exception {
        JSONObject action = page.getJSONObject("actions").getJSONObject("btnOnClick");
        JSONObject params = action.getJSONObject("params");
        if (!"message".equals(action.optString("type"))
                || !"com.oplus.oshare.cardwidget".equals(action.optString("uri"))
                || !"cancelTask".equals(params.optString("event"))
                || !"{{instanceId}}".equals(params.optString("instanceId")))
            throw new IllegalArgumentException("sender cancel is not scoped");
        JSONArray template = page.getJSONArray("template");
        if (template.length() == 0 || validateElements(template) == 0)
            throw new IllegalArgumentException("sender layout unavailable");
    }

    private static int validateElements(Object value) throws Exception {
        int controls = 0;
        if (value instanceof JSONObject object) {
            if (object.has("events")) {
                JSONObject events = object.getJSONObject("events");
                String shown = object.optString("shown");
                if (events.length() != 1 || !"btnOnClick".equals(events.optString("click"))
                        || !("{{isShowBtn}}".equals(shown) || "{{isE4Show}}".equals(shown)
                        || "{{isShowProcess}}".equals(shown)))
                    throw new IllegalArgumentException("unknown sender interaction");
                controls++;
            }
            for (Iterator<String> keys = object.keys(); keys.hasNext();) controls += validateElements(object.get(keys.next()));
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) controls += validateElements(array.get(i));
        }
        return controls;
    }

    private static byte[] read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (count > limit - output.size()) throw new IllegalArgumentException("sender UPK exceeds size limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
