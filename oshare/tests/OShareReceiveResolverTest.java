package com.tlsu.oshareairdrop;

import org.luckypray.dexkit.DexKitBridge;
import java.util.List;
import java.util.Map;

/** Offline APK inspection only; does not install or launch OShare. */
public final class OShareReceiveResolverTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        try {
            System.load(args[0] + "!/lib/arm64-v8a/libdexkit.so");
            try (DexKitBridge dex = DexKitBridge.create(OShareApkFiles.readDexFiles(List.of(args[1])))) {
                Map<String, String> roles = OShareReceiveResolver.scan(dex);
                roles.forEach((role, descriptor) -> System.out.println(role + "=" + descriptor));
                check(roles.size() == 8, "expected independent receive role set: " + roles);
                if (args.length > 2 && args[2].equals("baseline")) {
                    check(roles.get("type.activity").equals("com.oplus.oshare.ui.ReceiveDialogActivity"), "receive Activity anchor");
                    check(roles.get("render").equals("Lcom/oplus/oshare/ui/ReceiveDialogActivity;->S0(Landroid/content/Intent;)V"), "receive render anchor");
                    check(roles.get("type.builder").equals("com.coui.appcompat.dialog.a"), "native COUI builder anchor");
                }
                // Also bind constructors, all lifecycle super implementations and every dialog
                // method before hooks can be installed. This catches COUI signature mismatches.
                new OShareReceiveResolver.Roles(roles, new dalvik.system.PathClassLoader(args[1],
                        ClassLoader.getSystemClassLoader().getParent()));
            }
            System.out.println("OSHARE_RECEIVE_DEX_RESOLVER_PASSED");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(System.err); System.exit(1); }
    }
}
