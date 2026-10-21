package com.tlsu.oshareairdrop;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
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

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Handler main = new Handler(Looper.getMainLooper());
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object system = thread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getMethod("getSystemContext").invoke(system);
        HostContext.set(context);
        int uid = context.getPackageManager().getPackageUid("com.coloros.oshare", 0);
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
            main.post(() -> {
                check(ready[0] == 1, "provider readiness did not complete handshake");
                BridgeMessenger.attach(clients[1], uid);
                main.post(() -> {
                    check(admitted[1] == 1 && ready[1] == 1, "warm provider handshake failed");
                    check(ready[0] == 1, "new client restarted existing client's handshake");
                    System.out.println("ANDROID_BRIDGE_HANDSHAKE_PASSED: launch identity, anonymous rejection, pending/ready, isolated clients");
                    System.exit(0);
                });
            });
        });
        main.postDelayed(() -> { throw new AssertionError("handshake test timed out"); }, 5000);
        Looper.loop();
    }
}
