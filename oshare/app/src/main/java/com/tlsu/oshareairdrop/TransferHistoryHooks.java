package com.tlsu.oshareairdrop;

import android.util.Log;
import java.util.ArrayList;
import io.github.libxposed.api.XposedInterface.HookHandle;

/** Observe native Quick Share and OShare callbacks without changing protocol decisions. */
final class TransferHistoryHooks {
    private static final TransferHistory HISTORY = new TransferHistory();
    private static volatile boolean installed;
    private static volatile boolean reliable = true;

    private static void unavailable(Entry module, Throwable failure) {
        reliable = false;
        module.log(Log.WARN, Entry.TAG, "history phase observation unavailable", failure);
    }

    static void install(Entry module, ClassLoader loader) {
        ArrayList<HookHandle> handles = new ArrayList<>();
        try {
            Class<?> controller = loader.loadClass("cad"), callback = loader.loadClass("aqa");
            Class<?> target = loader.loadClass("com.google.android.gms.nearby.sharing.ShareTarget");
            Class<?> metadata = loader.loadClass("com.google.android.gms.nearby.sharing.TransferMetadata");
            if (target.getDeclaredField("a").getType() != long.class ||
                    metadata.getDeclaredField("a").getType() != int.class ||
                    metadata.getDeclaredField("e").getType() != boolean.class) {
                throw new IllegalStateException("Unsupported transfer history metadata");
            }
            handles.add(module.hook(controller.getDeclaredMethod("d", String.class, target, callback)).intercept(chain -> {
                if (chain.getArg(2) == null) return chain.proceed();
                long id;
                TransferHistory.Source source;
                try {
                    id = ((Number) TransferHistory.field(chain.getArg(1), "a")).longValue();
                    source = HISTORY.wrap(callback, chain.getArg(2), false,
                            failure -> unavailable(module, failure));
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    unavailable(module, failure);
                    return chain.proceed();
                }
                source.begin(id);
                boolean submitted = false;
                try {
                    Object result = chain.proceed(new Object[]{chain.getArg(0), chain.getArg(1), source.callback});
                    submitted = Integer.valueOf(0).equals(result);
                    return result;
                } finally { if (!submitted) source.finish(id); }
            }));
            handles.add(module.hook(controller.getDeclaredMethod("f", loader.loadClass("apj"), callback)).intercept(chain -> {
                if (chain.getArg(1) == null) return chain.proceed();
                TransferHistory.Source source;
                try {
                    source = HISTORY.wrap(callback, chain.getArg(1), true,
                            failure -> unavailable(module, failure));
                } catch (RuntimeException failure) {
                    unavailable(module, failure);
                    return chain.proceed();
                }
                return chain.proceed(new Object[]{chain.getArg(0), source.callback});
            }));
            handles.add(module.hook(controller.getDeclaredMethod("a", target)).intercept(chain -> {
                Object result = chain.proceed();
                if (Integer.valueOf(0).equals(result)) {
                    try { HISTORY.accepted(((Number) TransferHistory.field(chain.getArg(0), "a")).longValue()); }
                    catch (ReflectiveOperationException | RuntimeException failure) {
                        unavailable(module, failure);
                    }
                }
                return result;
            }));
            installed = true;
            module.log(Log.INFO, Entry.TAG, "Transfer history phase hooks installed");
        } catch (Exception failure) {
            for (HookHandle handle : handles) handle.unhook();
            module.log(Log.WARN, Entry.TAG, "Transfer history phase hooks unavailable", failure);
        }
    }

    static String state() {
        return !installed || !reliable ? "unknown" : IncomingReceiver.historyActive() ? "active" : HISTORY.state();
    }
}
