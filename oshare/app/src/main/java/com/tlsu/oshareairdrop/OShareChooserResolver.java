package com.tlsu.oshareairdrop;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.IInterface;
import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.UsingType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.wrap.DexMethod;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional capability with its own fingerprint/cache; never guesses obfuscated symbols. */
public final class OShareChooserResolver {
    public static final int SCHEMA = 1;
    private OShareChooserResolver() {}

    public static Map<String, String> scan(DexKitBridge dex, Map<String, String> base) {
        Map<String, String> values = new LinkedHashMap<>();
        MethodData route = one("direct route", dex.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().usingStrings("handleDirectShareDeviceData key:")
                        .returnType("void").paramCount(3))));
        MethodData filter = one("direct candidates", route.getInvokes().stream().filter(method ->
                method.getClassName().equals(route.getClassName())
                        && method.getReturnTypeName().equals("java.util.List")
                        && method.getParamTypeNames().equals(List.of("java.util.List"))).toList());
        values.put("filter", filter.getDescriptor());
        MethodData builder = one("shortcut builder", dex.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().declaredClass(route.getClassName()).usingStrings("buildShortcutItem ")
                        .paramCount(3))));
        String extended = base.get("type.extended");
        // Intersect declared methods: DEX may reference an inherited name getter through
        // the subclass too. Only the actual extended-device getter owns this field.
        MethodData secondary = one("secondary name", dex.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().declaredClass(extended).paramCount(0).returnType("java.lang.String")))
                .stream().filter(method -> builder.getInvokes().stream()
                        .anyMatch(call -> call.getDescriptor().equals(method.getDescriptor()))).toList());
        String field = one("secondary name field", secondary.getUsingFields().stream()
                .filter(use -> use.getUsingType().isRead() && use.getField().getClassName().equals(extended))
                .map(use -> use.getField().getDescriptor()).toList());
        values.put("secondary", one("secondary setter", dex.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().declaredClass(extended).returnType("void")
                        .paramTypes("java.lang.String").addUsingField(field, UsingType.Write)))).getDescriptor());
        MethodData send = dex.getMethodData(base.get("service.send"));
        String service = send.getClassName();
        for (String name : List.of("registerCallback", "registerMultiCallback", "unregisterCallback", "unregisterMultiCallback")) {
            values.put(name, one(name, dex.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().declaredClass(service).name(name).returnType("void")
                            .usingStrings(name + ": ")))).getDescriptor());
        }
        return values;
    }

    static Roles resolve(Entry module, Context context, ClassLoader loader, OShareSymbols base) throws Exception {
        ApplicationInfo app = context.getApplicationInfo();
        List<String> paths = new ArrayList<>();
        paths.add(app.sourceDir);
        if (app.splitSourceDirs != null) java.util.Collections.addAll(paths, app.splitSourceDirs);
        String identity = OShareApkFiles.identity(paths, SCHEMA, BuildConfig.VERSION_CODE);
        AtomicFile cache = new AtomicFile(new File(context.getCodeCacheDir(), "opairshare-chooser-symbols.json"));
        try {
            JSONObject saved = new JSONObject(new String(cache.readFully(), StandardCharsets.UTF_8));
            if (identity.equals(saved.getString("identity"))) {
                Map<String, String> values = new LinkedHashMap<>();
                JSONObject entries = saved.getJSONObject("symbols");
                for (java.util.Iterator<String> keys = entries.keys(); keys.hasNext();) {
                    String key = keys.next(); values.put(key, entries.getString(key));
                }
                return new Roles(values, loader, base);
            }
        } catch (Exception ignored) { /* Rebuild stale or incompatible optional capability. */ }
        OShareResolver.loadNative(module.getModuleApplicationInfo());
        Map<String, String> values;
        try (DexKitBridge dex = DexKitBridge.create(OShareApkFiles.readDexFiles(paths))) {
            values = scan(dex, base.descriptors);
        }
        Roles roles = new Roles(values, loader, base);
        FileOutputStream output = null;
        try {
            output = cache.startWrite();
            output.write(new JSONObject().put("identity", identity).put("symbols", new JSONObject(values))
                    .toString().getBytes(StandardCharsets.UTF_8));
            cache.finishWrite(output);
        } catch (Exception failure) {
            if (output != null) cache.failWrite(output);
            module.log(Log.WARN, Entry.TAG, "Chooser cache write failed", failure);
        }
        module.log(Log.INFO, Entry.TAG, "OSHARE_CHOOSER_RESOLVED " + values);
        return roles;
    }

    private static <T> T one(String role, List<T> values) { return OShareDexResolver.unique("chooser " + role, values); }

    static final class Roles {
        final Method filter, secondary, register, registerMulti, unregister, unregisterMulti;
        final Field callback;
        Roles(Map<String, String> values, ClassLoader loader, OShareSymbols base) throws Exception {
            filter = bind(values, "filter", loader);
            secondary = bind(values, "secondary", loader);
            register = bind(values, "registerCallback", loader);
            registerMulti = bind(values, "registerMultiCallback", loader);
            unregister = bind(values, "unregisterCallback", loader);
            unregisterMulti = bind(values, "unregisterMultiCallback", loader);
            Class<?> wrapper = register.getParameterTypes()[0];
            if (!List.class.isAssignableFrom(filter.getReturnType())
                    || !java.util.Arrays.equals(filter.getParameterTypes(), new Class<?>[]{List.class})
                    || secondary.getDeclaringClass() != base.type("extended")
                    || !java.util.Arrays.equals(secondary.getParameterTypes(), new Class<?>[]{String.class})
                    || secondary.getReturnType() != void.class
                    || !java.util.Arrays.equals(registerMulti.getParameterTypes(), new Class<?>[]{int.class, wrapper})
                    || !java.util.Arrays.equals(unregister.getParameterTypes(), new Class<?>[]{wrapper})
                    || !java.util.Arrays.equals(unregisterMulti.getParameterTypes(), new Class<?>[]{int.class}))
                throw new IllegalStateException("OSHARE_CHOOSER_UNSUPPORTED: role signatures");
            callback = one("callback Binder", java.util.Arrays.stream(wrapper.getDeclaredFields())
                    .filter(field -> field.getType().getName().equals("com.oplus.oshare.IOplusOshareCallback")
                            && IInterface.class.isAssignableFrom(field.getType())).toList());
            callback.setAccessible(true);
        }
        private static Method bind(Map<String, String> values, String role, ClassLoader loader) throws Exception {
            String descriptor = values.get(role);
            if (descriptor == null) throw new IllegalStateException("Missing chooser role " + role);
            Method method = new DexMethod(descriptor).getMethodInstance(loader);
            method.setAccessible(true);
            return method;
        }
    }
}
