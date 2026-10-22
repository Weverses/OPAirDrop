package com.tlsu.oshareairdrop;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedInterface.HookHandle;

/** Owns background advertising while root radio readiness and the UI host agree. */
final class ReceiveController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadLocal<Boolean> INTERNAL = ThreadLocal.withInitial(() -> false);
    private static final AtomicBoolean TICK_QUEUED = new AtomicBoolean();
    private static volatile boolean installed, owned;
    private static Object previousOptions, previousCallback;
    private static boolean previousListening;
    private static String oldDownloadDirectory;
    private static Method advertise;
    private static long nextRetry;
    private static String advertisedName = "";
    private ReceiveController() {}

    static void install(Entry module, Context context, ClassLoader loader) {
        if (installed) return;
        ArrayList<HookHandle> handles = new ArrayList<>();
        try {
            ShareMode mode = ShareModeRuntime.initialize(context);
            Class<?> controller = loader.loadClass("cad");
            Class<?> options = loader.loadClass("apj");
            advertise = controller.getDeclaredMethod("f", options, loader.loadClass("aqa"));
            advertise.setAccessible(true);
            handles.add(module.hook(advertise).intercept(chain -> {
                Integer disabled = ShareModeRuntime.result("f");
                if (disabled != null) return disabled;
                if (INTERNAL.get()) return chain.proceed();
                synchronized (ReceiveController.class) {
                    previousOptions = chain.getArg(0); previousCallback = chain.getArg(1); previousListening = true;
                    if (owned) return 0;
                }
                return chain.proceed();
            }));
            handles.add(module.hook(controller.getDeclaredMethod("j", options)).intercept(chain -> {
                Integer disabled = ShareModeRuntime.result("j");
                if (disabled != null) return disabled;
                if (INTERNAL.get()) return chain.proceed();
                synchronized (ReceiveController.class) {
                    previousOptions = chain.getArg(0);
                    if (owned) return 0;
                }
                return chain.proceed();
            }));
            handles.add(module.hook(controller.getDeclaredMethod("h")).intercept(chain -> {
                Integer disabled = ShareModeRuntime.result("h");
                if (disabled != null) return disabled;
                if (INTERNAL.get()) return chain.proceed();
                synchronized (ReceiveController.class) {
                    previousListening = false;
                    if (owned) return 0;
                }
                return chain.proceed();
            }));
            // Keep incoming text files byte-for-byte as files. Stock Mosey converts
            // text/* files to TextAttachment and deletes the original after COMPLETE.
            // Attachment IDs limit this change to the task owned by this receiver.
            handles.add(module.hook(loader.loadClass("sb").getDeclaredMethod("q", loader.loadClass("buq"))).intercept(chain -> {
                if (owned) {
                    Object wrapper = chain.getArg(0);
                    java.lang.reflect.Field attachment = wrapper.getClass().getDeclaredField("b");
                    attachment.setAccessible(true);
                    Object file = attachment.get(wrapper);
                    java.lang.reflect.Field id = file.getClass().getDeclaredField("g"); id.setAccessible(true);
                    if (IncomingReceiver.ownsFile(id.getLong(file))) return false;
                }
                return chain.proceed();
            }));
            // Include waiting invitations and publication, absent from stock transfer dumps.
            Method dump = loader.loadClass("com.google.android.mosey.ExternalSharingService")
                    .getDeclaredMethod("dump", java.io.FileDescriptor.class, java.io.PrintWriter.class, String[].class);
            handles.add(module.hook(dump).intercept(chain -> {
                Object result = chain.proceed();
                ((java.io.PrintWriter) chain.getArg(1)).println("OPAIRSHARE_RECEIVE busy=" + (IncomingReceiver.busy() || ProviderApi.hasOutgoing() ? 1 : 0));
                ((java.io.PrintWriter) chain.getArg(1)).println("OPAIRSHARE_HISTORY state=" + TransferHistoryHooks.state());
                ((java.io.PrintWriter) chain.getArg(1)).println("OPAIRSHARE_DISCOVERY " + DiscoveryController.status(ProviderHolder.get()));
                ((java.io.PrintWriter) chain.getArg(1)).println("OPAIRSHARE_MODE switch_ready=" + (switchReady() ? 1 : 0));
                return result;
            }));
            installed = true;
            BridgeMessenger.onReceiveClientReady(ReceiveController::queueTick);
            MAIN.post(new Runnable() {
                @Override public void run() { queueTick(); MAIN.postDelayed(this, 1000); }
            });
            module.log(Log.INFO, Entry.TAG, "AirDrop receive ownership hooks installed");
        } catch (Throwable failure) {
            for (HookHandle handle : handles) try { handle.unhook(); } catch (Throwable ignored) {}
            module.log(Log.ERROR, Entry.TAG, "AirDrop receive disabled: provider contract mismatch", failure);
        }
    }

    private static void queueTick() {
        if (!TICK_QUEUED.compareAndSet(false, true)) return;
        BridgeMessenger.execute(() -> {
            try { tick(); }
            finally { TICK_QUEUED.set(false); }
        });
    }

    static boolean admitting() { return owned && wanted() && BridgeMessenger.hasReceiveClient(); }

    static boolean acceptingExisting() {
        return owned && BridgeMessenger.hasReceiveClient() && (wanted() || ShareModeRuntime.switching());
    }

    private static boolean wanted() {
        if (!ShareModeRuntime.current().usesOShare() || ShareModeRuntime.switching()) return false;
        try {
            String ready = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class).invoke(null, "debug.opairshare.receive.ready", "");
            if (ready.isEmpty()) ready = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class).invoke(null, "debug." + "op" + "13.receive.ready", "");
            return "1".equals(ready);
        } catch (Throwable failure) { return false; }
    }

    private static void internal(Runnable action) {
        INTERNAL.set(true);
        try { ShareMode.bridgeCall(() -> { action.run(); return null; }); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        finally { INTERNAL.remove(); }
    }

    private static void tick() {
        boolean want = wanted() && BridgeMessenger.hasReceiveClient();
        IncomingReceiver.tick(want || (ShareModeRuntime.switching() && owned && BridgeMessenger.hasReceiveClient()));
        if (!ProviderHolder.ready()) return;
        if (ShareModeRuntime.switching() && !IncomingReceiver.busy() && !ProviderApi.hasOutgoing()) {
            try { ProviderApi.stopDiscovery(); }
            catch (RuntimeException failure) { Log.w(Entry.TAG, "mode switch discovery release deferred", failure); }
        }
        String desiredName = ProviderApi.localDeviceName();
        if (want && !owned && !ProviderApi.hasOutgoing() && android.os.SystemClock.elapsedRealtime() >= nextRetry) {
            try {
                if (ProviderApi.nativeReceiveBusy()) return;
                // Remember the provider's existing destination; restore after releasing ownership.
                java.lang.reflect.Field directory = ProviderHolder.get().getClass().getDeclaredField("k");
                directory.setAccessible(true);
                Object flow = directory.get(ProviderHolder.get());
                Method value = flow.getClass().getMethod("b");
                value.setAccessible(true);
                oldDownloadDirectory = (String) value.invoke(flow);
                owned = true;
                internal(() -> {
                    try { ReceiveFiles.staging(HostContext.get()); ProviderApi.setDownloadDirectory(ReceiveFiles.DIRECTORY); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                    ProviderApi.startReceiverDiscovery();
                    ProviderApi.startOwnedAdvertising(desiredName);
                });
                advertisedName = desiredName;
                StartupTrace.mark("RECEIVE_ADVERTISING");
                Log.i(Entry.TAG, "OSHARE_RECEIVE_ADVERTISING");
            } catch (Throwable failure) {
                Log.e(Entry.TAG, "receive start failed", failure);
                nextRetry = android.os.SystemClock.elapsedRealtime() + 15_000;
                release();
            }
        } else if (!want && owned && !IncomingReceiver.busy()) release();
        else if (want && owned && !advertisedName.equals(desiredName) && !IncomingReceiver.busy()
                && !ProviderApi.hasOutgoing() && android.os.SystemClock.elapsedRealtime() >= nextRetry) {
            try {
                if (ProviderApi.nativeReceiveBusy()) return;
                internal(() -> ProviderApi.updateOwnedAdvertising(desiredName));
                advertisedName = desiredName;
            } catch (Throwable failure) {
                nextRetry = android.os.SystemClock.elapsedRealtime() + 15_000;
                Log.w(Entry.TAG, "AirDrop name update deferred", failure);
            }
        }
    }

    private static void release() {
        if (!owned) return;
        try {
            internal(() -> {
                ProviderApi.stopAdvertising();
                ProviderApi.stopReceiverDiscovery();
                if (oldDownloadDirectory != null) ProviderApi.setDownloadDirectory(oldDownloadDirectory);
            });
        } catch (Throwable failure) { Log.w(Entry.TAG, "receive release failed", failure); }
        synchronized (ReceiveController.class) {
            owned = false;
            if (previousListening && previousOptions != null && previousCallback != null) {
                try {
                    internal(() -> {
                        try { advertise.invoke(ProviderHolder.get(), previousOptions, previousCallback); }
                        catch (Exception failure) { throw new IllegalStateException(failure); }
                    });
                } catch (Throwable failure) { Log.w(Entry.TAG, "previous receive callback restore failed", failure); }
            }
        }
    }

    private static boolean switchReady() {
        return !owned && !IncomingReceiver.busy() && !ProviderApi.hasOutgoing()
                && !ProviderApi.discoveryWanted(ProviderHolder.get());
    }
}
