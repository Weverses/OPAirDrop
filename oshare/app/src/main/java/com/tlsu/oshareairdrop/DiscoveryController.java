package com.tlsu.oshareairdrop;

import android.util.Log;
import io.github.libxposed.api.XposedInterface.HookHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Mediate native start/update/stop without replacing either client's callback. */
final class DiscoveryController {
    private static final Map<Object, DiscoverySession> SESSIONS = new WeakHashMap<>();
    private static boolean installed;
    private DiscoveryController() {}

    static synchronized void install(Entry module, ClassLoader loader) {
        if (installed) return;
        ArrayList<HookHandle> hooks = new ArrayList<>();
        try {
            Class<?> controller = loader.loadClass("cad");
            Class<?> options = loader.loadClass("apk"), callback = loader.loadClass("aps");
            Field mode = options.getDeclaredField("a"); mode.setAccessible(true);
            Field id = loader.loadClass("com.google.android.gms.nearby.sharing.ShareTarget").getDeclaredField("a");
            id.setAccessible(true);
            Method update = controller.getDeclaredMethod("k", options); update.setAccessible(true);
            hooks.add(module.hook(controller.getDeclaredMethod("g", callback, options)).intercept(chain -> {
                Object host = chain.getThisObject();
                DiscoverySession session = session(host, callback, id, module);
                DiscoverySession.Change change = session.start(DiscoverySession.internal(), chain.getArg(0),
                        chain.getArg(1), mode.getInt(chain.getArg(1)));
                module.log(Log.INFO, Entry.TAG, "DISCOVERY_SHARED start internal=" + DiscoverySession.internal()
                        + " " + session.status());
                try {
                    Object result = chain.proceed(new Object[]{session.callback(), change.options});
                    if (((Number) result).intValue() != 0) change.rollback();
                    else if (change.replay) BridgeMessenger.execute(session::replayNative);
                    return result;
                } catch (Throwable failure) { change.rollback(); throw failure; }
            }));
            hooks.add(module.hook(update).intercept(chain -> {
                if (DiscoverySession.internal()) return chain.proceed();
                DiscoverySession session = session(chain.getThisObject(), callback, id, module);
                DiscoverySession.Change change = session.update(chain.getArg(0), mode.getInt(chain.getArg(0)));
                module.log(Log.INFO, Entry.TAG, "DISCOVERY_SHARED update requested=" + mode.getInt(chain.getArg(0))
                        + " effective=" + mode.getInt(change.options) + " " + session.status());
                try {
                    Object result = chain.proceed(new Object[]{change.options});
                    if (((Number) result).intValue() != 0) change.rollback();
                    else {
                        if (change.options != chain.getArg(0))
                            module.log(Log.INFO, Entry.TAG, "DISCOVERY_SHARED native idle; bridge discovery retained");
                        if (change.replay) BridgeMessenger.execute(session::replayNative);
                    }
                    return result;
                } catch (Throwable failure) { change.rollback(); throw failure; }
            }));
            hooks.add(module.hook(controller.getDeclaredMethod("i")).intercept(chain -> {
                Object host = chain.getThisObject();
                DiscoverySession session = session(host, callback, id, module);
                DiscoverySession.Change change = session.stop(DiscoverySession.internal());
                try {
                    if (change.skip) {
                        module.log(Log.INFO, Entry.TAG, "DISCOVERY_SHARED native stop; bridge discovery retained");
                        return 0;
                    }
                    Object result;
                    if (change.options != null) {
                        result = DiscoverySession.internal(() -> update.invoke(host, change.options));
                    } else result = chain.proceed();
                    if (((Number) result).intValue() != 0) change.rollback();
                    else if (change.options == null) session.stopped();
                    else BridgeMessenger.execute(session::replayNative);
                    return result;
                } catch (Throwable failure) { change.rollback(); throw failure; }
            }));
            installed = true;
            module.log(Log.INFO, Entry.TAG, "Shared native/bridge discovery hooks installed");
        } catch (Throwable failure) {
            for (HookHandle hook : hooks) try { hook.unhook(); } catch (Throwable ignored) {}
            module.log(Log.ERROR, Entry.TAG, "Shared discovery contract unavailable", failure);
        }
    }

    static String status(Object host) {
        synchronized (SESSIONS) {
            DiscoverySession found = SESSIONS.get(host);
            return found == null ? "not_registered" : found.status();
        }
    }

    private static DiscoverySession session(Object host, Class<?> callback, Field id, Entry module) throws Exception {
        synchronized (SESSIONS) {
            DiscoverySession found = SESSIONS.get(host);
            if (found != null) return found;
            // The provider is captured after construction. Resolve ownership at
            // invocation time so the initial native callback is retained too.
            java.lang.ref.WeakReference<Object> owner = new java.lang.ref.WeakReference<>(host);
            DiscoverySession created = new DiscoverySession(callback,
                    () -> ProviderApi.discoveryWanted(owner.get()), target -> {
                        try { return id.getLong(target); }
                        catch (Exception failure) { throw new IllegalStateException(failure); }
                    }, (message, failure) -> module.log(Log.WARN, Entry.TAG, message, failure));
            SESSIONS.put(host, created);
            return created;
        }
    }
}
