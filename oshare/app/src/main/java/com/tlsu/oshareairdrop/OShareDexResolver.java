package com.tlsu.oshareairdrop;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Resolves roles from DEX evidence. No version table or obfuscated-name fallback. */
public final class OShareDexResolver {
    public static final int SCHEMA = 1;
    private final DexKitBridge dex;
    private final Map<String, String> symbols = new LinkedHashMap<>();

    private OShareDexResolver(DexKitBridge dex) { this.dex = dex; }

    public static Map<String, String> resolve(DexKitBridge dex) {
        return new OShareDexResolver(dex).scan();
    }

    private Map<String, String> scan() {
        ClassData adapter = type("adapter", "empty list or wrong position", "device is null");
        ClassData extended = type("extended", "OshareDeviceExt{", "mDeviceType=");
        ClassData wrapper = extended.getSuperClass();
        if (wrapper == null) throw unsupported("device wrapper superclass missing");
        symbols.put("type.wrapper", wrapper.getName());
        ClassData manager = type("manager", "removeRemoteDevice", "resetAllReceiveDevice");
        ClassData activity = type("activity", "showPanelSheetDialog", "showDeviceList");

        // Public vendor APIs are the semantic anchors for the obfuscated wrapper.
        vendor(wrapper, "key", "getBleMac", "java.lang.String");
        vendor(wrapper, "state", "getState", "java.lang.Object");
        vendor(wrapper, "setName", "setName", "void", "java.lang.String");
        vendor(wrapper, "setKey", "setBleMac", "void", "java.lang.String");
        vendor(wrapper, "setWifiMac", "setWifiMac", "void", "java.lang.String");
        vendor(wrapper, "setVirtual", "setVirtual", "void", "int");
        vendor(wrapper, "setFoundTime", "setLastFoundTime", "void", "long");
        vendor(wrapper, "setState", "setState", "void", "java.lang.Object");
        vendor(wrapper, "setProgress", "setProgress", "void", "int");
        vendor(wrapper, "setTotal", "setTotalNum", "void", "int");
        MethodData stateSetter = named("device.setState");
        List<String> enums = stateSetter.getInvokes().stream()
                .filter(m -> m.getName().equals("setState") && vendorDevice(m.getClassName()))
                .flatMap(m -> m.getParamTypeNames().stream())
                .filter(n -> n.endsWith(".OplusOshareState")).distinct().collect(Collectors.toList());
        symbols.put("type.state", unique("Oplus state enum", enums));

        MethodData bind = method("adapter.bind", MethodMatcher.create().declaredClass(adapter.getName())
                .paramCount(3).returnType("void").paramTypes(wrapper.getName(), null, "int"));
        String holder = bind.getParamTypeNames().get(1);
        method("adapter.label", MethodMatcher.create().declaredClass(adapter.getName())
                .returnType("java.lang.String").paramTypes(symbols.get("type.state"), wrapper.getName()));
        MethodData avatar = method("adapter.avatar", MethodMatcher.create().declaredClass(adapter.getName())
                .usingStrings("setAvatar").returnType("void")
                .paramTypes(holder, extended.getName(), "android.graphics.Bitmap"));
        // The foreground image is the holder getter used by setAvatar. Other ImageViews
        // (background / subscript) are deliberately not selected by return type alone.
        // Some builds call multiple holder ImageView getters from setAvatar. Narrow to
        // the getter used by the adapter's foreground fade animation (setAlpha).
        List<MethodData> imageCandidates = avatar.getInvokes().stream().filter(m ->
                m.getClassName().equals(holder) && m.getReturnTypeName().equals("android.widget.ImageView")
                && m.getParamCount() == 0).collect(Collectors.toList());
        MethodData foreground;
        if (imageCandidates.size() > 1) {
            foreground = pick("holder.image", imageCandidates, m -> m.getCallers().stream().anyMatch(c ->
                    c.getClassName().equals(adapter.getName()) && c.getParamCount() == 2
                    && c.getInvokes().stream().anyMatch(i -> i.getName().equals("setAlpha"))
                    && c.getParamTypeNames().contains("android.animation.ValueAnimator")));
        } else foreground = unique("holder.image", imageCandidates);
        put("holder.image", foreground);
        MethodData typeGetter = pick("device.type", extended.getMethods(), m ->
                m.getReturnTypeName().equals("int") && m.getParamCount() == 0
                && avatar.getInvokes().stream().anyMatch(i -> sameMember(i, m)));
        FieldData typeField = unique("device type field", typeGetter.getUsingFields().stream()
                .filter(f -> f.getUsingType().isRead() && f.getField().getClassName().equals(extended.getName()))
                .map(f -> f.getField()).collect(Collectors.toList()));
        method("device.setType", MethodMatcher.create().declaredClass(extended.getName())
                .returnType("void").paramTypes("int").addUsingField(typeField.getDescriptor(),
                        org.luckypray.dexkit.query.enums.UsingType.Write));
        method("device.icon", MethodMatcher.create().declaredClass(extended.getName())
                .returnType("android.graphics.Bitmap").paramCount(0));
        method("device.setIcon", MethodMatcher.create().declaredClass(extended.getName())
                .returnType("void").paramTypes("android.graphics.Bitmap"));
        method("manager.list", MethodMatcher.create().declaredClass(manager.getName())
                .returnType("java.util.ArrayList").paramCount(0));
        method("manager.get", MethodMatcher.create().declaredClass(manager.getName())
                .returnType(wrapper.getName()).paramTypes("java.lang.String"));
        MethodData singleton = pick("manager.instance", manager.getMethods(), m ->
                Modifier.isStatic(m.getModifiers()) && m.getParamCount() == 0
                && m.getReturnTypeName().equals(manager.getName()));
        put("manager.instance", singleton);
        MethodData remove = only(MethodMatcher.create().declaredClass(manager.getName())
                .usingStrings("removeRemoteDevice").paramTypes("java.lang.String").returnType("void"));
        put("manager.refresh", pick("manager.refresh", manager.getMethods(), m ->
                !Modifier.isStatic(m.getModifiers()) && m.getParamCount() == 0 && m.getReturnTypeName().equals("void")
                && remove.getInvokes().stream().anyMatch(i -> sameMember(i, m))));

        MethodData cancel = method("service.cancel", MethodMatcher.create()
                .usingStrings("OShareClient-OShareServiceImpl", "cancelTask empty key")
                .returnType("void").paramTypes(wrapper.getName()));
        method("service.send", MethodMatcher.create().declaredClass(cancel.getClassName())
                .returnType("void").paramTypes("android.content.Intent", wrapper.getName())
                .usingStrings("sendData remoteTarget is null", "is canceling,can not send"));
        method("activity.resume", MethodMatcher.create().declaredClass(activity.getName())
                .name("onResume").paramCount(0).returnType("void"));
        method("activity.destroy", MethodMatcher.create().declaredClass(activity.getName())
                .name("onDestroy").paramCount(0).returnType("void"));
        method("activity.update", MethodMatcher.create().declaredClass(activity.getName())
                .usingStrings("updateUi isEmptyLast true").paramCount(0).returnType("void"));
        method("activity.list", MethodMatcher.create().declaredClass(activity.getName())
                .usingStrings("showDeviceList").paramCount(0).returnType("void"));
        method("activity.panel", MethodMatcher.create().declaredClass(activity.getName())
                .usingStrings("showPanelSheetDialog").paramCount(0).returnType("void"));
        return new LinkedHashMap<>(symbols);
    }

    private static boolean vendorDevice(String name) {
        return name.equals("com.oplus.oshare.OplusOshareDevice")
                || name.equals("com.color.oshare.ColorOshareDevice");
    }

    private void vendor(ClassData wrapper, String role, String api, String result, String... params) {
        method("device." + role, MethodMatcher.create().declaredClass(wrapper.getName())
                .returnType(result).paramTypes(params).addInvoke(MethodMatcher.create()
                        .declaredClass("com.oplus.oshare.OplusOshareDevice").name(api)));
    }

    private ClassData type(String role, String... strings) {
        ClassData found = unique("type." + role, dex.findClass(FindClass.create()
                .matcher(ClassMatcher.create().usingStrings(strings))));
        symbols.put("type." + role, found.getName());
        return found;
    }

    private MethodData only(MethodMatcher matcher) {
        return unique("method query", dex.findMethod(FindMethod.create().matcher(matcher)));
    }

    private MethodData method(String role, MethodMatcher matcher) {
        MethodData found = unique(role, dex.findMethod(FindMethod.create().matcher(matcher)));
        put(role, found);
        return found;
    }

    private MethodData named(String role) { return dex.getMethodData(symbols.get(role)); }
    private void put(String role, MethodData method) { symbols.put(role, method.getDescriptor()); }

    private static MethodData pick(String role, List<MethodData> methods, Predicate<MethodData> predicate) {
        return unique(role, methods.stream().filter(predicate).collect(Collectors.toList()));
    }

    private static boolean sameMember(MethodData a, MethodData b) {
        // Invoke references can name a subclass for inherited methods.
        return a.getName().equals(b.getName()) && a.getParamTypeNames().equals(b.getParamTypeNames())
                && a.getReturnTypeName().equals(b.getReturnTypeName());
    }

    static <T> T unique(String role, List<T> candidates) {
        // DEX references may repeat; never use firstOrNull for an ambiguous role.
        List<T> distinct = new ArrayList<>(new java.util.LinkedHashSet<>(candidates));
        if (distinct.size() != 1) throw unsupported(role + ": expected 1 candidate, got " + distinct.size()
                + " " + distinct);
        return distinct.get(0);
    }

    private static IllegalStateException unsupported(String message) {
        return new IllegalStateException("OSHARE_DEX_UNSUPPORTED schema=" + SCHEMA + " " + message);
    }
}
