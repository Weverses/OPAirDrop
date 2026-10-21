package com.tlsu.oshareairdrop;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;

/** One physical discovery callback, shared by the native client and the bridge. */
final class DiscoverySession {
    private static final ThreadLocal<Boolean> INTERNAL = ThreadLocal.withInitial(() -> false);
    private final BooleanSupplier bridgeWanted;
    private final ToLongFunction<Object> targetId;
    private final BiConsumer<String, Throwable> errors;
    private final Object callback;
    private final Method found;
    private final LinkedHashMap<Long, Object> targets = new LinkedHashMap<>();
    private Object nativeCallback, nativeOptions, bridgeCallback, bridgeOptions;
    private int nativeMode;
    private long revision;

    static boolean internal() { return INTERNAL.get(); }

    static <T> T internal(Callable<T> action) throws Exception {
        boolean previous = INTERNAL.get();
        INTERNAL.set(true);
        try { return action.call(); }
        finally { INTERNAL.set(previous); }
    }

    DiscoverySession(Class<?> callbackType, BooleanSupplier wanted,
            ToLongFunction<Object> id, BiConsumer<String, Throwable> onError) throws Exception {
        bridgeWanted = wanted; targetId = id; errors = onError;
        Method match = null;
        for (Method method : callbackType.getMethods()) {
            if (!method.getName().equals("a") || method.getParameterCount() != 1) continue;
            if (match != null) throw new IllegalStateException("Ambiguous discovery found callback");
            match = method;
        }
        if (match == null) throw new IllegalStateException("Missing discovery found callback");
        found = match;
        callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "toString": return "OPAirDropSharedDiscovery";
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return args != null && proxy == args[0];
                        default: dispatch(method, args); return null;
                    }
                });
    }

    final class Change {
        final Object oldNativeCallback = nativeCallback, oldNativeOptions = nativeOptions;
        final Object oldBridgeCallback = bridgeCallback, oldBridgeOptions = bridgeOptions;
        final int oldMode = nativeMode;
        final long token = ++revision;
        Object options;
        boolean skip, replay;
        void rollback() {
            synchronized (DiscoverySession.this) {
                if (revision != token) return;
                nativeCallback = oldNativeCallback; nativeOptions = oldNativeOptions;
                bridgeCallback = oldBridgeCallback; bridgeOptions = oldBridgeOptions;
                nativeMode = oldMode; revision++;
            }
        }
    }

    Object callback() { return callback; }

    synchronized Change start(boolean internal, Object listener, Object options, int mode) {
        Change change = new Change();
        if (internal) { bridgeCallback = listener; bridgeOptions = options; }
        else {
            nativeCallback = listener; nativeOptions = options; nativeMode = mode;
            change.replay = mode != 0;
        }
        change.options = effectiveOptions();
        return change;
    }

    synchronized Change update(Object options, int mode) {
        Change change = new Change();
        change.replay = mode != 0;
        nativeOptions = options; nativeMode = mode;
        change.options = effectiveOptions();
        return change;
    }

    synchronized Change stop(boolean internal) {
        Change change = new Change();
        if (internal) {
            // Native mode=0 is still a registered callback: future native
            // update(mode=1) must work without another start() handshake.
            if (nativeCallback != null) change.options = nativeOptions;
        } else {
            nativeCallback = null; nativeOptions = null; nativeMode = 0;
            change.skip = bridgeWanted.getAsBoolean();
        }
        return change;
    }

    private Object effectiveOptions() {
        if (bridgeOptions != null && bridgeWanted.getAsBoolean() && (nativeCallback == null || nativeMode == 0))
            return bridgeOptions;
        return nativeOptions;
    }

    private void dispatch(Method method, Object[] args) {
        Object nativeTarget, bridgeTarget;
        synchronized (this) {
            if (args != null && args.length == 1 && args[0] != null) {
                String name = method.getName();
                if (name.equals("a") || name.equals("c") || name.equals("d")) try {
                    long id = targetId.applyAsLong(args[0]);
                    if (name.equals("c")) targets.remove(id); else targets.put(id, args[0]);
                } catch (Throwable failure) { errors.accept("discovery target identity failed", failure); }
            }
            nativeTarget = nativeMode == 0 ? null : nativeCallback;
            bridgeTarget = bridgeWanted.getAsBoolean() ? bridgeCallback : null;
        }
        deliver(method, args, nativeTarget);
        if (bridgeTarget != nativeTarget) deliver(method, args, bridgeTarget);
    }

    private void deliver(Method method, Object[] args, Object listener) {
        if (listener == null) return;
        try { method.setAccessible(true); method.invoke(listener, args); }
        catch (Throwable failure) { errors.accept("discovery callback failed", failure); }
    }

    void replayNative() {
        Object listener;
        ArrayList<Object> snapshot;
        long generation;
        synchronized (this) {
            if (nativeMode == 0 || nativeCallback == null) return;
            listener = nativeCallback; generation = revision;
            snapshot = new ArrayList<>(targets.values());
        }
        for (Object target : snapshot) {
            synchronized (this) {
                if (revision != generation || nativeCallback != listener || nativeMode == 0) return;
                if (!targets.containsKey(targetId.applyAsLong(target))) continue;
            }
            deliver(found, new Object[]{target}, listener);
        }
    }

    synchronized void stopped() { targets.clear(); }

    synchronized String status() {
        return "nativeRegistered=" + (nativeCallback == null ? 0 : 1) + " nativeMode=" + nativeMode
                + " bridgeWanted=" + (bridgeWanted.getAsBoolean() ? 1 : 0) + " targets=" + targets.size();
    }
}
