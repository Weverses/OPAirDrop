package com.tlsu.oshareairdrop;

import android.content.Context;
import android.os.Looper;
import android.os.Process;

/** Read-only PackageManager checks against production bridge admission code. */
public final class BridgeAccessTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object main = thread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getMethod("getSystemContext").invoke(main);
        HostContext.set(context);
        int oshare = context.getPackageManager().getPackageUid("com.coloros.oshare", 0);
        int module = context.getPackageManager().getPackageUid("com.tlsu.oshareairdrop", 0);
        check(BridgeMessenger.allowedUid(oshare), "OShare UID rejected");
        check(BridgeMessenger.allowedUid(module), "module UID rejected");
        check(!BridgeMessenger.allowedUid(-1), "anonymous broadcast identity accepted");
        check(!BridgeMessenger.allowedUid(oshare + 100000), "other Android user accepted");
        int unrelated = context.getPackageManager().getPackageUid("com.android.settings", 0);
        check(unrelated == Process.myUid() || !BridgeMessenger.allowedUid(unrelated), "unrelated package accepted");
        check(ReceiveFiles.safeName("../a/b\\c\u0000").equals(".._a_b_c_"), "path characters retained");
        check(!ReceiveFiles.safeName(".. ").equals(".."), "parent traversal retained");
        String longName = "测试".repeat(100);
        check(ReceiveFiles.safeName(longName).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 220,
                "UTF-8 filename limit exceeded");
        System.out.println("ANDROID_BRIDGE_ACCESS_PASSED: trusted UIDs, anonymous/unrelated/cross-user rejection, safe filenames");
        System.exit(0);
    }
}
