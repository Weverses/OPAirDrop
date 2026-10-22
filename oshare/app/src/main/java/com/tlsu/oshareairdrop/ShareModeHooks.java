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
        ArrayList<HookHandle> hooks = new ArrayList<>();
        try {
            for (Method method : methods) {
                if (method.getReturnType() != int.class) throw new IllegalStateException("Unsupported Mosey method: " + method);
                hooks.add(module.hook(method).intercept(chain -> {
                    Integer result = ShareModeRuntime.result(method.getName());
                    return result == null ? chain.proceed() : result;
                }));
            }
        } catch (Exception failure) {
            for (HookHandle hook : hooks) hook.unhook();
            throw failure;
        }
    }
}
