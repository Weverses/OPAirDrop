package com.tlsu.oshareairdrop;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.wrap.DexMethod;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Receive capability has its own schema/cache and cannot invalidate sending roles. */
public final class OShareReceiveResolver {
    public static final int SCHEMA = 2;
    private OShareReceiveResolver() {}

    public static Map<String, String> scan(DexKitBridge dex) {
        Map<String, String> roles = new LinkedHashMap<>();
        // The nearby ReceiveCommonDialogActivity repeats the generic display/ACK strings.
        // Resolve the render method itself using its BLE/P2P acknowledgement branch and full
        // signature; that branch is absent from the common MOV/Mac receive Activity.
        MethodData render = unique("render", dex.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().usingStrings("displayDialog: mDisplayMode = ", "showReceiveAckDialog, not show p2p ack")
                        .returnType("void").paramTypes("android.content.Intent"))));
        String activity = render.getClassName();
        roles.put("type.activity", activity);
        roles.put("render", render.getDescriptor());
        MethodData ack = unique("ack", dex.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().declaredClass(activity).usingStrings("showReceiveAckDialog")
                        .returnType("android.app.Dialog").paramTypes("android.content.Context", "java.lang.String", "int",
                                "java.lang.String", "java.lang.String", "java.lang.String"))));
        List<String> builders = ack.getInvokes().stream().filter(m -> m.getName().equals("<init>")
                && m.getParamTypeNames().equals(List.of("android.content.Context", "int")))
                .map(MethodData::getClassName).collect(Collectors.toList());
        roles.put("type.builder", unique("COUI builder", builders));
        // Lifecycle names are Android contracts; every full signature is bound before installing hooks.
        for (String name : List.of("onCreate", "onDestroy", "onNewIntent", "onSaveInstanceState", "onConfigurationChanged")) {
            String[] params = name.equals("onDestroy") ? new String[0] : new String[]{
                    name.equals("onNewIntent") ? "android.content.Intent" : name.equals("onConfigurationChanged")
                            ? "android.content.res.Configuration" : "android.os.Bundle"};
            MethodData method = unique(name, dex.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().declaredClass(activity).name(name).returnType("void").paramTypes(params))));
            roles.put(name, method.getDescriptor());
        }
        return roles;
    }

    static Roles resolve(Entry module, Context context, ClassLoader loader) throws Exception {
        ApplicationInfo app = context.getApplicationInfo();
        List<String> paths = new ArrayList<>();
        paths.add(app.sourceDir);
        if (app.splitSourceDirs != null) java.util.Collections.addAll(paths, app.splitSourceDirs);
        String identity = OShareApkFiles.identity(paths, SCHEMA, BuildConfig.VERSION_CODE);
        AtomicFile cache = new AtomicFile(new File(context.getCodeCacheDir(), "opairshare-receive-symbols.json"));
        try {
            JSONObject stored = new JSONObject(new String(cache.readFully(), StandardCharsets.UTF_8));
            if (identity.equals(stored.getString("identity"))) {
                JSONObject json = stored.getJSONObject("symbols");
                Map<String, String> values = new LinkedHashMap<>();
                for (java.util.Iterator<String> it = json.keys(); it.hasNext();) {
                    String key = it.next(); values.put(key, json.getString(key));
                }
                return new Roles(values, loader);
            }
        } catch (Exception ignored) { /* Re-scan a missing, stale or unbindable cache. */ }
        try { System.loadLibrary("dexkit"); }
        catch (UnsatisfiedLinkError ignored) {
            System.load(module.getModuleApplicationInfo().sourceDir + "!/lib/" + Build.SUPPORTED_ABIS[0] + "/libdexkit.so");
        }
        Map<String, String> values;
        try (DexKitBridge dex = DexKitBridge.create(OShareApkFiles.readDexFiles(paths))) { values = scan(dex); }
        Roles roles = new Roles(values, loader);
        FileOutputStream out = null;
        try {
            out = cache.startWrite();
            out.write(new JSONObject().put("identity", identity).put("symbols", new JSONObject(values))
                    .toString().getBytes(StandardCharsets.UTF_8));
            cache.finishWrite(out);
        } catch (Exception failure) {
            if (out != null) cache.failWrite(out);
            module.log(Log.WARN, Entry.TAG, "receive symbol cache write failed", failure);
        }
        module.log(Log.INFO, Entry.TAG, "OSHARE_RECEIVE_DEX_RESOLVED schema=" + SCHEMA + " " + values);
        return roles;
    }

    private static <T> T unique(String role, List<T> values) {
        List<T> distinct = new ArrayList<>(new java.util.LinkedHashSet<>(values));
        if (distinct.size() != 1) throw new IllegalStateException("OSHARE_RECEIVE_UNSUPPORTED schema=" + SCHEMA
                + " " + role + ": expected 1 candidate, got " + distinct.size());
        return distinct.get(0);
    }

    static final class Roles {
        final Class<?> activity;
        final Constructor<?> builder;
        final Map<String, Method> methods = new LinkedHashMap<>();
        final Map<String, Method> supers = new LinkedHashMap<>();
        final Method title, message, positive, negative, cancelable, create;
        Roles(Map<String, String> values, ClassLoader loader) throws Exception {
            activity = Class.forName(values.get("type.activity"), false, loader);
            if (!Activity.class.isAssignableFrom(activity)) throw new IllegalStateException("receive role is not Activity");
            Class<?> builderClass = Class.forName(values.get("type.builder"), false, loader);
            builder = builderClass.getDeclaredConstructor(Context.class);
            builder.setAccessible(true);
            title = builderClass.getMethod("setTitle", CharSequence.class);
            message = builderClass.getMethod("setMessage", CharSequence.class);
            positive = builderClass.getMethod("setPositiveButton", CharSequence.class, android.content.DialogInterface.OnClickListener.class);
            negative = builderClass.getMethod("setNegativeButton", CharSequence.class, android.content.DialogInterface.OnClickListener.class);
            cancelable = builderClass.getMethod("setCancelable", boolean.class);
            create = builderClass.getMethod("create");
            if (!Dialog.class.isAssignableFrom(create.getReturnType())) throw new IllegalStateException("receive builder create is not Dialog");
            for (Map.Entry<String, String> value : values.entrySet()) {
                if (value.getKey().startsWith("type.")) continue;
                Method method = new DexMethod(value.getValue()).getMethodInstance(loader);
                method.setAccessible(true); methods.put(value.getKey(), method);
                if (value.getKey().equals("render")) continue;
                Class<?> base = activity.getSuperclass();
                while (base != null) {
                    try {
                        Method inherited = base.getDeclaredMethod(method.getName(), method.getParameterTypes());
                        inherited.setAccessible(true); supers.put(value.getKey(), inherited); break;
                    } catch (NoSuchMethodException ignored) { base = base.getSuperclass(); }
                }
                if (!supers.containsKey(value.getKey())) throw new IllegalStateException("receive super lifecycle missing: " + value.getKey());
            }
            for (String role : List.of("render", "onCreate", "onDestroy", "onNewIntent", "onSaveInstanceState", "onConfigurationChanged"))
                if (!methods.containsKey(role)) throw new IllegalStateException("missing receive role: " + role);
        }
    }
}
