package com.tlsu.oshareairdrop;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-APK cache in the host's private code cache. APK content changes invalidate it. */
final class OShareResolver {
    private OShareResolver() {}

    static OShareSymbols resolve(Entry module, Context context, ClassLoader loader) throws Exception {
        ApplicationInfo app = context.getApplicationInfo();
        List<String> paths = new ArrayList<>();
        paths.add(app.sourceDir);
        if (app.splitSourceDirs != null) java.util.Collections.addAll(paths, app.splitSourceDirs);
        String identity = OShareApkFiles.identity(paths, OShareDexResolver.SCHEMA, BuildConfig.VERSION_CODE);
        AtomicFile cache = new AtomicFile(new File(context.getCodeCacheDir(), "opairshare-oshare-symbols.json"));
        try {
            JSONObject stored = new JSONObject(new String(cache.readFully(), StandardCharsets.UTF_8));
            if (identity.equals(stored.getString("identity"))) {
                Map<String, String> symbols = new LinkedHashMap<>();
                JSONObject entries = stored.getJSONObject("symbols");
                for (java.util.Iterator<String> keys = entries.keys(); keys.hasNext();) {
                    String key = keys.next();
                    symbols.put(key, entries.getString(key));
                }
                OShareSymbols bound = new OShareSymbols(symbols, loader);
                module.log(Log.INFO, Entry.TAG, "OSHARE_DEX_CACHE_HIT roles=" + symbols.size());
                return bound;
            }
        } catch (Exception ignored) { /* A stale/missing cache is rebuilt before hooks. */ }
        loadNative(module.getModuleApplicationInfo());
        long start = android.os.SystemClock.elapsedRealtime();
        Map<String, String> descriptors;
        // Base and code splits form one search space; resource splits contain no DEX.
        try (DexKitBridge dex = DexKitBridge.create(OShareApkFiles.readDexFiles(paths))) {
            descriptors = OShareDexResolver.resolve(dex);
        }
        OShareSymbols bound = new OShareSymbols(descriptors, loader);
        JSONObject record = new JSONObject().put("identity", identity).put("symbols", new JSONObject(descriptors));
        FileOutputStream output = null;
        try {
            output = cache.startWrite();
            output.write(record.toString().getBytes(StandardCharsets.UTF_8));
            cache.finishWrite(output);
        } catch (Exception failure) {
            if (output != null) cache.failWrite(output);
            module.log(Log.WARN, Entry.TAG, "OShare symbol cache write failed", failure);
        }
        module.log(Log.INFO, Entry.TAG, "OSHARE_DEX_RESOLVED roles=" + descriptors.size()
                + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - start) + " " + descriptors);
        return bound;
    }

    private static void loadNative(ApplicationInfo module) {
        try { System.loadLibrary("dexkit"); return; }
        catch (UnsatisfiedLinkError ignored) { /* LSPosed loaders may omit module native paths. */ }
        // Load from the module APK, never a writable host cache. AGP stores these
        // libraries uncompressed and aligned; dependencies reside in the same APK.
        System.load(module.sourceDir + "!/lib/" + Build.SUPPORTED_ABIS[0] + "/libdexkit.so");
    }
}
