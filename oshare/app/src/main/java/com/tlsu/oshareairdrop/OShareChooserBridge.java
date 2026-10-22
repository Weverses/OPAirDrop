package com.tlsu.oshareairdrop;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import io.github.libxposed.api.XposedInterface.HookHandle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Hooks only the OShare service; the stock Chooser owns URI grants, click dispatch and progress. */
final class OShareChooserBridge {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final ChooserSessions<IBinder> clients = new ChooserSessions<>();
    private final Map<IBinder, IBinder.DeathRecipient> deaths = new HashMap<>();
    private final ArrayList<HookHandle> hooks = new ArrayList<>();
    private final OShareChooserResolver.Roles roles;
    private final Context context;

    private OShareChooserBridge(Context context, OShareChooserResolver.Roles roles) {
        this.context = context; this.roles = roles;
    }

    static OShareChooserBridge install(Entry module, Context context, ClassLoader loader, OShareSymbols base) throws Exception {
        OShareChooserBridge bridge = new OShareChooserBridge(context,
                OShareChooserResolver.resolve(module, context, loader, base));
        ArrayList<HookHandle> hooks = bridge.hooks;
        try {
            hooks.add(module.hook(bridge.roles.filter).intercept(chain -> {
                @SuppressWarnings("unchecked") List<Object> nativeTargets = (List<Object>) chain.proceed();
                try {
                    return ChooserTargets.merge(nativeTargets, OShareBridge.chooserDevices(), device -> {
                        try { return base.key(device); }
                        catch (Exception failure) { throw new IllegalStateException(failure); }
                    });
                } catch (Throwable failure) {
                    module.log(Log.WARN, Entry.TAG, "Chooser candidates unchanged after capability failure", failure);
                    return nativeTargets;
                }
            }));
            for (boolean multi : new boolean[]{false, true}) {
                Method register = multi ? bridge.roles.registerMulti : bridge.roles.register;
                Method unregister = multi ? bridge.roles.unregisterMulti : bridge.roles.unregister;
                hooks.add(module.hook(register).intercept(chain -> {
                    // Capture real Binder identity before native code or an asynchronous hop.
                    int uid = Binder.getCallingUid();
                    IBinder callback = bridge.trusted(uid) ? bridge.binder(chain.getArg(multi ? 1 : 0)) : null;
                    Object tag = multi ? chain.getArg(0) : callback;
                    Object result = chain.proceed();
                    if (callback != null) MAIN.post(() -> bridge.add(uid, tag, callback));
                    return result;
                }));
                hooks.add(module.hook(unregister).intercept(chain -> {
                    int uid = Binder.getCallingUid();
                    Object tag = !bridge.trusted(uid) ? null : multi ? chain.getArg(0) : bridge.binder(chain.getArg(0));
                    Object result = chain.proceed();
                    if (tag != null) MAIN.post(() -> {
                        IBinder removed = bridge.clients.remove(uid, tag);
                        bridge.unlinkUnused(removed);
                        OShareBridge.chooserChanged();
                    });
                    return result;
                }));
            }
            module.log(Log.INFO, Entry.TAG, "OShare Chooser candidates and callback session hooks installed");
            return bridge;
        } catch (Throwable failure) {
            for (HookHandle hook : hooks) try { hook.unhook(); }
            catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("Chooser hooks rolled back", failure);
        }
    }

    boolean active() { return !clients.isEmpty(); }

    void decorate(Object device) throws Exception { roles.secondary.invoke(device, "AirDrop"); }

    private boolean trusted(int uid) {
        if (uid < 0 || uid / 100000 != Process.myUid() / 100000) return false;
        try { return context.getPackageManager().getPackageUid("com.android.intentresolver", 0) == uid; }
        catch (Exception unavailable) { return false; }
    }

    private IBinder binder(Object wrapper) {
        if (wrapper == null) return null;
        try {
            IInterface callback = (IInterface) roles.callback.get(wrapper);
            return callback == null ? null : callback.asBinder();
        } catch (Exception failure) { Log.w(Entry.TAG, "Chooser callback unavailable", failure); return null; }
    }

    private void add(int uid, Object tag, IBinder callback) {
        if (!deaths.containsKey(callback)) {
            IBinder.DeathRecipient death = () -> MAIN.post(() -> {
                clients.died(callback);
                unlinkUnused(callback);
                OShareBridge.chooserChanged();
            });
            try { callback.linkToDeath(death, 0); }
            catch (android.os.RemoteException dead) { return; }
            deaths.put(callback, death);
        }
        IBinder previous = clients.put(uid, tag, callback);
        unlinkUnused(previous);
        OShareBridge.chooserChanged();
    }

    private void unlinkUnused(IBinder callback) {
        if (callback == null || clients.contains(callback)) return;
        IBinder.DeathRecipient death = deaths.remove(callback);
        if (death != null) callback.unlinkToDeath(death, 0);
    }
}
