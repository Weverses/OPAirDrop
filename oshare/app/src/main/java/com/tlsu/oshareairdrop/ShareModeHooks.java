package com.tlsu.oshareairdrop;

import java.lang.reflect.Method;
import java.util.ArrayList;
import io.github.libxposed.api.XposedInterface.HookHandle;

/** Limit only the Mosey AirDrop provider; never disable the GMS package. */
final class ShareModeHooks {
    static void install(Entry module, ClassLoader loader, ShareMode mode) throws Exception {
        Class<?> controller = loader.loadClass("cad");
        Class<?> discoveryOptions = loader.loadClass("apk"), discoveryCallback = loader.loadClass("aps");
        Class<?> advertisingOptions = loader.loadClass("apj"), transferCallback = loader.loadClass("aqa");
        Class<?> target = loader.loadClass("com.google.android.gms.nearby.sharing.ShareTarget");
        // Resolve the complete contract before changing any hook.
        Method[] methods = {
            controller.getDeclaredMethod("g", discoveryCallback, discoveryOptions),
            controller.getDeclaredMethod("k", discoveryOptions), controller.getDeclaredMethod("i"),
            controller.getDeclaredMethod("f", advertisingOptions, transferCallback),
            controller.getDeclaredMethod("j", advertisingOptions), controller.getDeclaredMethod("h"),
            controller.getDeclaredMethod("d", String.class, target, transferCallback),
            controller.getDeclaredMethod("a", target), controller.getDeclaredMethod("b", target),
            controller.getDeclaredMethod("c", target), controller.getDeclaredMethod("m", String.class)
        };
        for (Method method : methods) {
            // Mosey's directory setter is void; the other operations return a status code.
            Class<?> expected = method.getName().equals("m") ? void.class : int.class;
            if (method.getReturnType() != expected) {
                throw new IllegalStateException("Unsupported Mosey method: " + method);
            }
        }
        ArrayList<HookHandle> hooks = new ArrayList<>();
        try {
            for (Method method : methods) {
                hooks.add(module.hook(method).intercept(chain -> {
                    Integer result = ShareModeRuntime.result(method.getName());
                    if (result == null) return chain.proceed();
                    return method.getReturnType() == void.class ? null : result;
                }));
            }
        } catch (Exception failure) {
            for (HookHandle hook : hooks) hook.unhook();
            throw failure;
        }
    }
}
