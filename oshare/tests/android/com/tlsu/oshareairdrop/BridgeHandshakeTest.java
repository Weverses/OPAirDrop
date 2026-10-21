package com.tlsu.oshareairdrop;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Intent;
import android.app.BroadcastOptions;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Messenger;

/** Exercises production launch options and asynchronous admission/readiness on Android. */
public final class BridgeHandshakeTest {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    private static final class Launcher extends Activity {
        Intent intent;
        Bundle options;
        @Override public void startActivity(Intent intent, Bundle options) {
            this.intent = intent;
            this.options = options;
        }
    }

    private static final class Signals extends ContextWrapper {
        Intent last;
        Bundle options;
        int sent;
        BroadcastReceiver registered;
        Signals(Context base) { super(base); }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
            registered = receiver;
            check(flags == Context.RECEIVER_EXPORTED, "cross-UID readiness receiver not exported");
            return null;
        }
        @Override public void sendBroadcast(Intent intent, String permission, Bundle options) {
            last = intent; this.options = options; sent++;
        }
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Handler main = new Handler(Looper.getMainLooper());
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object system = thread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getMethod("getSystemContext").invoke(system);
        HostContext.set(context);
        int uid = context.getPackageManager().getPackageUid("com.coloros.oshare", 0);
        int hostUid = context.getPackageManager().getPackageUid(Entry.HOST, 0);
        check(BridgeMessenger.trustedReadyUid(context, hostUid), "Mosey readiness hint rejected");
        check(!BridgeMessenger.trustedReadyUid(context, -1), "anonymous readiness hint admitted");
        check(!BridgeMessenger.trustedReadyUid(context, uid), "OShare can spoof Mosey readiness");
        check(!BridgeMessenger.trustedReadyUid(context, hostUid + 100000), "cross-user readiness admitted");
        Signals signals = new Signals(context);
        BridgeMessenger.install(signals);
        check(signals.sent == 1 && BridgeMessenger.READY.equals(signals.last.getAction()), "receiver readiness not announced");
        check("com.coloros.oshare".equals(signals.last.getPackage()), "readiness hint not package-scoped");
        check(BroadcastOptions.fromBundle(signals.options).isShareIdentityEnabled(), "readiness sender UID hidden");
        int[] wakeups = {0};
        BridgeMessenger.observeReadiness(signals, () -> wakeups[0]++);
        signals.registered.onReceive(signals, new Intent(BridgeMessenger.READY));
        check(wakeups[0] == 0, "anonymous hint woke the connection");
        int[] admitted = {0, 0}, ready = {0, 0};
        Messenger[] clients = new Messenger[2];
        for (int i = 0; i < clients.length; i++) {
            final int index = i;
            clients[i] = new Messenger(new Handler(Looper.getMainLooper(), message -> {
                if (message.what == BridgeMessenger.MSG_ATTACHED) admitted[index]++;
                else if (message.what == BridgeMessenger.MSG_HELLO) {
                    check(message.replyTo != null, "ready without command channel");
                    check(admitted[index] > 0, "ready before admission");
                    ready[index]++;
                }
                return true;
            }));
        }

        Launcher launcher = new Launcher();
        BridgeMessenger.boot(launcher, clients[0]);
        ActivityOptions options = (ActivityOptions) ActivityOptions.class
                .getDeclaredMethod("fromBundle", Bundle.class).invoke(null, launcher.options);
        check(options != null && options.isShareIdentityEnabled(), "cross-UID launch hides caller identity");
        check(launcher.intent.getComponent().getPackageName().equals(Entry.HOST), "wrong boot target");
        check(launcher.intent.getBooleanExtra(Entry.BOOT_EXTRA, false), "missing boot marker");
        check(launcher.intent.getParcelableExtra(BridgeMessenger.EXTRA_CLIENT, Messenger.class)
                .getBinder().equals(clients[0].getBinder()), "callback lost during boot");
        try {
            BridgeMessenger.attach(clients[0], -1);
            throw new AssertionError("anonymous caller admitted");
        } catch (SecurityException expected) {}

        ProviderHolder.set(null);
        BridgeMessenger.attach(clients[0], uid);
        main.post(() -> {
            check(admitted[0] == 1 && ready[0] == 0, "pending provider reported ready");
            ProviderHolder.install(BridgeHandshakeTest.class.getClassLoader());
            ProviderHolder.set(new Object());
            BridgeMessenger.providerReady();
            check(signals.sent == 2, "provider readiness did not wake unattached clients");
            main.post(() -> {
                check(ready[0] == 1, "provider readiness did not complete handshake");
                BridgeMessenger.attach(clients[1], uid);
                main.post(() -> {
                    check(admitted[1] == 1 && ready[1] == 1, "warm provider handshake failed");
                    check(ready[0] == 1, "new client restarted existing client's handshake");
                    try {
                        int[] acknowledged = {0};
                        BridgeMessenger.onReceiveClientReady(() -> acknowledged[0]++);
                        java.lang.reflect.Method command = BridgeMessenger.class.getDeclaredMethod("handleCommand",
                                String.class, java.util.ArrayList.class, int.class, Messenger.class);
                        command.setAccessible(true);
                        command.invoke(null, "{\"cmd\":\"receive_ui_ready\"}", null, uid, clients[0]);
                        check(acknowledged[0] == 1 && BridgeMessenger.hasReceiveClient(), "receive ACK did not wake advertising preparation");
                        command.invoke(null, "{\"cmd\":\"receive_ui_ready\"}", null, uid, clients[0]);
                        check(acknowledged[0] == 1, "duplicate receive ACK restarted preparation");
                    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    System.out.println("ANDROID_BRIDGE_HANDSHAKE_PASSED: launch identity, trusted readiness hints, pending/ready, isolated clients");
                    System.exit(0);
                });
            });
        });
        main.postDelayed(() -> { throw new AssertionError("handshake test timed out"); }, 5000);
        Looper.loop();
    }
}
