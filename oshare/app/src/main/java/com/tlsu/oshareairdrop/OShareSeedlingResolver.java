package com.tlsu.oshareairdrop;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.wrap.DexMethod;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Independent presentation capability: native Seedling SDK, without native transport singletons. */
public final class OShareSeedlingResolver {
    public static final int SCHEMA = 1;
    private static final String SDK = "com.oplus.pantanal.seedling.";
    private static final String PROVIDER_BASE = SDK + "SeedlingCardWidgetProvider";
    private OShareSeedlingResolver() {}

    /** Only OShare-owned roles are semantically scanned; public SDK APIs are fully bound below. */
    public static Map<String, String> scan(DexKitBridge dex) {
        ClassData provider = unique("widget provider", dex.findClass(FindClass.create().matcher(
                ClassMatcher.create().usingStrings("check is mac receive cancel", "event = "))).stream()
                .filter(candidate -> candidate.getSuperClass() != null
                        && PROVIDER_BASE.equals(candidate.getSuperClass().getName()))
                .collect(Collectors.toList()));
        MethodData support = unique("ROM fluid cloud support", dex.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().usingStrings("is support fluid cloud = ")
                        .returnType("boolean").paramTypes("android.content.Context"))).stream()
                .filter(method -> Modifier.isStatic(method.getModifiers())).collect(Collectors.toList()));
        Map<String, String> descriptors = new LinkedHashMap<>();
        descriptors.put("type.widgetProvider", provider.getName());
        descriptors.put("romSupport", support.getDescriptor());
        return descriptors;
    }

    public static Roles resolve(Entry module, Context context, ClassLoader loader) throws Exception {
        ApplicationInfo app = context.getApplicationInfo();
        List<String> paths = new ArrayList<>();
        paths.add(app.sourceDir);
        if (app.splitSourceDirs != null) Collections.addAll(paths, app.splitSourceDirs);
        String identity = OShareApkFiles.identity(paths, SCHEMA, BuildConfig.VERSION_CODE);
        AtomicFile cache = new AtomicFile(new File(context.getCodeCacheDir(), "opairshare-seedling-symbols.json"));
        try {
            JSONObject stored = new JSONObject(new String(cache.readFully(), StandardCharsets.UTF_8));
            if (identity.equals(stored.getString("identity"))) {
                Map<String, String> values = new LinkedHashMap<>();
                JSONObject symbols = stored.getJSONObject("symbols");
                for (java.util.Iterator<String> keys = symbols.keys(); keys.hasNext();) {
                    String key = keys.next(); values.put(key, symbols.getString(key));
                }
                Roles roles = new Roles(values, loader);
                module.log(Log.INFO, Entry.TAG, "OSHARE_SEEDLING_DEX_CACHE_HIT schema=" + SCHEMA);
                return roles;
            }
        } catch (Exception ignored) { /* Missing, stale or incompatible capability cache: scan again. */ }
        try { System.loadLibrary("dexkit"); }
        catch (UnsatisfiedLinkError ignored) {
            System.load(module.getModuleApplicationInfo().sourceDir + "!/lib/" + Build.SUPPORTED_ABIS[0] + "/libdexkit.so");
        }
        Map<String, String> values;
        try (DexKitBridge dex = DexKitBridge.create(OShareApkFiles.readDexFiles(paths))) { values = scan(dex); }
        Roles roles = new Roles(values, loader);
        FileOutputStream output = null;
        try {
            output = cache.startWrite();
            output.write(new JSONObject().put("identity", identity).put("symbols", new JSONObject(values))
                    .toString().getBytes(StandardCharsets.UTF_8));
            cache.finishWrite(output);
        } catch (Exception failure) {
            if (output != null) cache.failWrite(output);
            module.log(Log.WARN, Entry.TAG, "Seedling symbol cache write failed", failure);
        }
        module.log(Log.INFO, Entry.TAG, "OSHARE_SEEDLING_DEX_RESOLVED schema=" + SCHEMA + " " + values);
        return roles;
    }

    private static <T> T unique(String role, List<T> values) {
        List<T> distinct = new ArrayList<>(new java.util.LinkedHashSet<>(values));
        if (distinct.size() != 1) throw unsupported(role + ": expected 1 candidate, got " + distinct.size());
        return distinct.get(0);
    }

    private static IllegalStateException unsupported(String reason) {
        return new IllegalStateException("OSHARE_SEEDLING_UNSUPPORTED schema=" + SCHEMA + " " + reason);
    }

    /** Fully bound before presentation hooks are installed. Null lifecycle methods are optional. */
    public static final class Roles {
        public final Map<String, String> descriptors;
        public final Object sdk, flagStart, flagEnd, hostStatusBar, hostNotification, hostLockScreen;
        public final Class<?> sdkType, intentType, cardType, optionsType, intentCallbackType,
                negativeFeedbackType, widgetProviderType, flagType, hostType;
        public final Constructor<?> intentConstructor, optionsConstructor;
        public final Method romSupport, sdkSupport, sendSeedling, updateAllCardData, updateData,
                setSupportMultiInstance, setServiceInstanceId, cardInstanceId, cardVersion, cardWidgetCode,
                intentResult, intentResultCode, negativeFeedback,
                providerCall, providerUpdate, providerShow, providerHide, providerDestroy,
                setPageId, setGrade, setMilestone, setNotificationIdList, setRequestShowPanel,
                setRequestHideStatusBar, setLockScreenShowHostMap, setShowHostMap, setRemindType;

        public Roles(Map<String, String> values, ClassLoader loader) throws Exception {
            descriptors = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            widgetProviderType = type(values.get("type.widgetProvider"), loader);
            if (!ContentProvider.class.isAssignableFrom(widgetProviderType)
                    || widgetProviderType.getSuperclass() == null
                    || !PROVIDER_BASE.equals(widgetProviderType.getSuperclass().getName()))
                throw unsupported("invalid widget provider hierarchy");
            romSupport = new DexMethod(required(values, "romSupport")).getMethodInstance(loader);
            romSupport.setAccessible(true);
            requireSignature(romSupport, boolean.class, true, Context.class);

            sdkType = type(SDK + "util.SeedlingTool", loader);
            intentType = type(SDK + "bean.SeedlingIntent", loader);
            cardType = type(SDK + "bean.SeedlingCard", loader);
            optionsType = type(SDK + "update.SeedlingCardOptions", loader);
            intentCallbackType = type(SDK + "intent.IIntentResultCallBack", loader);
            negativeFeedbackType = type(SDK + "update.INegativeFeedbackCallback", loader);
            flagType = type(SDK + "bean.SeedlingIntentFlagEnum", loader);
            hostType = type(SDK + "bean.SeedlingHostEnum", loader);
            if (!intentCallbackType.isInterface() || !negativeFeedbackType.isInterface())
                throw unsupported("SDK callback role is not an interface");
            sdk = staticValue(sdkType, "INSTANCE", sdkType);
            flagStart = staticValue(flagType, "START", flagType);
            flagEnd = staticValue(flagType, "END", flagType);
            hostStatusBar = staticValue(hostType, "StatusBar", hostType);
            hostNotification = staticValue(hostType, "Notification", hostType);
            hostLockScreen = staticValue(hostType, "LockScreen", hostType);

            intentConstructor = intentType.getConstructor(long.class, String.class, flagType,
                    JSONObject.class, JSONObject.class, optionsType);
            optionsConstructor = optionsType.getConstructor();
            sdkSupport = publicMethod(sdkType, "isSupportFluidCloud", boolean.class, true, Context.class);
            sendSeedling = publicMethod(sdkType, "sendSeedling", int.class, false,
                    Context.class, intentType, intentCallbackType);
            updateAllCardData = publicMethod(sdkType, "updateAllCardData", void.class, false,
                    cardType, String.class, JSONObject.class, optionsType, int.class, negativeFeedbackType);
            // Host onUpdateData requests must reply to that exact card/host rather than
            // fan out through updateAllCardData and potentially cause another request cycle.
            updateData = publicMethod(sdkType, "updateData", void.class, false,
                    cardType, JSONObject.class, optionsType);
            setSupportMultiInstance = publicMethod(intentType, "setSupportMultiInstance", void.class, false, boolean.class);
            setServiceInstanceId = publicMethod(intentType, "setServiceInstanceId", void.class, false, String.class);
            cardInstanceId = publicMethod(cardType, "getServiceInstanceId", String.class, false);
            cardVersion = publicMethod(cardType, "getUpkVersionCode", long.class, false);
            cardWidgetCode = publicMethod(cardType, "getWidgetCode", String.class, false);
            intentResult = publicMethod(intentCallbackType, "onIntentResult", void.class, false,
                    String.class, int.class, boolean.class);
            intentResultCode = publicMethod(intentCallbackType, "onIntentResultCodeCallBack", void.class, false,
                    String.class, int.class, int.class);
            negativeFeedback = publicMethod(negativeFeedbackType, "requestDataOnDisableEntry",
                    type("kotlin.Pair", loader), false, String.class, Map.class);

            providerCall = declaredMethod(widgetProviderType, "call", Bundle.class,
                    String.class, String.class, Bundle.class);
            providerUpdate = declaredMethod(widgetProviderType, "onUpdateData", void.class,
                    Context.class, cardType, Bundle.class);
            // Do not hook inherited SDK lifecycle bodies shared by unrelated providers.
            providerShow = optionalDeclared(widgetProviderType, "onShow", Context.class, cardType);
            providerHide = optionalDeclared(widgetProviderType, "onHide", Context.class, cardType);
            providerDestroy = optionalDeclared(widgetProviderType, "onDestroy", Context.class, cardType);

            setPageId = publicMethod(optionsType, "setPageId", void.class, false, String.class);
            setGrade = publicMethod(optionsType, "setGrade", void.class, false, Integer.class);
            setMilestone = publicMethod(optionsType, "setMilestone", void.class, false, boolean.class);
            setNotificationIdList = publicMethod(optionsType, "setNotificationIdList", void.class, false, List.class);
            setRequestShowPanel = publicMethod(optionsType, "setRequestShowPanel", void.class, false, Boolean.class);
            setRequestHideStatusBar = publicMethod(optionsType, "setRequestHideStatusBar", void.class, false, boolean.class);
            setLockScreenShowHostMap = publicMethod(optionsType, "setLockScreenShowHostMap", void.class, false, Map.class);
            setShowHostMap = publicMethod(optionsType, "setShowHostMap", void.class, false, Map.class);
            setRemindType = publicMethod(optionsType, "setRemindType", void.class, false, int.class);
        }

        public Object newOptions() throws ReflectiveOperationException { return optionsConstructor.newInstance(); }

        /** The original ROM policy includes SDK/user support and OEM-specific exclusions. */
        public boolean supported(Context context) throws ReflectiveOperationException {
            return Boolean.TRUE.equals(romSupport.invoke(null, context));
        }

        public String cardId(Object card) throws ReflectiveOperationException {
            if (!cardType.isInstance(card)) throw new IllegalArgumentException("not a SeedlingCard");
            return (String) cardInstanceId.invoke(card);
        }

        /** Identifies one materialized host; a service instance can have several widgets. */
        public String widgetCode(Object card) throws ReflectiveOperationException {
            if (!cardType.isInstance(card)) throw new IllegalArgumentException("not a SeedlingCard");
            return (String) cardWidgetCode.invoke(card);
        }

        public long cardUpkVersion(Object card) throws ReflectiveOperationException {
            if (!cardType.isInstance(card)) throw new IllegalArgumentException("not a SeedlingCard");
            return ((Number) cardVersion.invoke(card)).longValue();
        }

        /** Does not register native business callbacks or modify any OShare transport singleton. */
        public Object newIntent(long timestamp, String action, boolean start, JSONObject data,
                JSONObject options, Object cardOptions, String instanceId) throws ReflectiveOperationException {
            if (instanceId == null || instanceId.isEmpty()) throw new IllegalArgumentException("empty Seedling instanceId");
            if (action == null || action.isEmpty()) throw new IllegalArgumentException("empty Seedling action");
            if (cardOptions != null && !optionsType.isInstance(cardOptions))
                throw new IllegalArgumentException("not SeedlingCardOptions");
            Object intent = intentConstructor.newInstance(timestamp, action, start ? flagStart : flagEnd,
                    data, options, cardOptions);
            setSupportMultiInstance.invoke(intent, true);
            setServiceInstanceId.invoke(intent, instanceId);
            return intent;
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isEmpty()) throw unsupported("missing role " + key);
        return value;
    }

    private static Class<?> type(String name, ClassLoader loader) throws ClassNotFoundException {
        if (name == null || name.isEmpty()) throw unsupported("missing type role");
        return Class.forName(name, false, loader);
    }

    private static Object staticValue(Class<?> owner, String name, Class<?> expected) throws ReflectiveOperationException {
        java.lang.reflect.Field field = owner.getField(name);
        if (!Modifier.isStatic(field.getModifiers()) || field.getType() != expected)
            throw unsupported("invalid SDK constant " + owner.getName() + "." + name);
        Object value = field.get(null);
        if (!expected.isInstance(value)) throw unsupported("null or invalid SDK constant " + name);
        return value;
    }

    private static Method publicMethod(Class<?> owner, String name, Class<?> result,
            boolean isStatic, Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        requireSignature(method, result, isStatic, parameters);
        return method;
    }

    private static Method declaredMethod(Class<?> owner, String name, Class<?> result,
            Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        requireSignature(method, result, false, parameters);
        return method;
    }

    private static Method optionalDeclared(Class<?> owner, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        try { return declaredMethod(owner, name, void.class, parameters); }
        catch (NoSuchMethodException absent) { return null; }
    }

    private static void requireSignature(Method method, Class<?> result, boolean isStatic,
            Class<?>... parameters) {
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) != isStatic
                || !java.util.Arrays.equals(method.getParameterTypes(), parameters))
            throw unsupported("invalid full signature: " + method);
    }
}
