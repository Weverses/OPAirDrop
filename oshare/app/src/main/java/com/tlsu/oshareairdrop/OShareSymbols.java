package com.tlsu.oshareairdrop;

import org.luckypray.dexkit.wrap.DexMethod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fully bound before any hook is installed; no name/arity reflection at runtime. */
public final class OShareSymbols implements OShareAppearance.DeviceAccess {
    final Map<String, String> descriptors;
    private final Map<String, Method> methods = new LinkedHashMap<>();
    private final Map<String, Class<?>> types = new LinkedHashMap<>();
    final Constructor<?> deviceConstructor;
    final Method notifyAdapters;

    OShareSymbols(Map<String, String> descriptors, ClassLoader loader) throws Exception {
        this.descriptors = Collections.unmodifiableMap(new LinkedHashMap<>(descriptors));
        for (Map.Entry<String, String> entry : descriptors.entrySet()) {
            if (entry.getKey().startsWith("type.")) {
                types.put(entry.getKey().substring(5), Class.forName(entry.getValue(), false, loader));
            } else {
                Method method = new DexMethod(entry.getValue()).getMethodInstance(loader);
                method.setAccessible(true);
                methods.put(entry.getKey(), method);
            }
        }
        deviceConstructor = type("extended").getDeclaredConstructor();
        deviceConstructor.setAccessible(true);
        if (!type("wrapper").isAssignableFrom(type("extended")) || !type("state").isEnum()
                || !android.app.Activity.class.isAssignableFrom(type("activity")))
            throw new IllegalStateException("OSHARE_DEX_UNSUPPORTED: invalid role hierarchy");
        // Stable RecyclerView API; its declaring class can itself be obfuscated.
        notifyAdapters = type("adapter").getMethod("notifyDataSetChanged");
        for (String role : new String[]{"key", "state", "setName", "setKey", "setWifiMac", "setVirtual",
                "setFoundTime", "setState", "setProgress", "setTotal", "setType", "icon", "setIcon"})
            method("device." + role);
        for (String role : new String[]{"adapter.bind", "adapter.label", "holder.image", "manager.list",
                "manager.get", "manager.instance", "manager.refresh", "service.send", "service.cancel",
                "activity.resume", "activity.destroy", "activity.update", "activity.list", "activity.panel"})
            method(role);
        for (String state : new String[]{"IDLE", "TRANSIT_WAIT", "TRANSITING", "TRANSIT_SUCCESS",
                "TRANSIT_FAILED", "TRANSIT_REJECT", "CANCEL_WAIT", "CANCEL"}) state(state);
    }

    Class<?> type(String role) {
        Class<?> value = types.get(role);
        if (value == null) throw new IllegalStateException("missing type role: " + role);
        return value;
    }

    Method method(String role) {
        Method value = methods.get(role);
        if (value == null) throw new IllegalStateException("missing method role: " + role);
        return value;
    }

    Object invoke(String role, Object receiver, Object... args) throws Exception {
        return method(role).invoke(receiver, args);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Object state(String name) { return Enum.valueOf((Class) type("state"), name); }
    @Override public String key(Object device) throws Exception {
        return String.valueOf(invoke("device.key", device));
    }
    @Override public void setType(Object device, int value) throws Exception {
        invoke("device.setType", device, value);
    }
    @Override public void setIcon(Object device, android.graphics.Bitmap bitmap) throws Exception {
        invoke("device.setIcon", device, bitmap);
    }
}
