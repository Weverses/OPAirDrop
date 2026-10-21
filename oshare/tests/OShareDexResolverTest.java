package com.tlsu.oshareairdrop;

import android.os.Looper;
import android.graphics.Bitmap;
import dalvik.system.PathClassLoader;
import org.luckypray.dexkit.DexKitBridge;
import java.util.List;
import java.util.Map;

/** Runs against a supplied APK without installing or starting OShare. */
public final class OShareDexResolverTest {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable failure) { failure.printStackTrace(System.err); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        System.load(args[0] + "!/lib/arm64-v8a/libdexkit.so");
        try (DexKitBridge dex = DexKitBridge.create(OShareApkFiles.readDexFiles(List.of(args[1])))) {
            if (args.length > 2 && args[2].equals("ambiguous")) {
                try { OShareDexResolver.resolve(dex); throw new AssertionError("ambiguous adapter accepted"); }
                catch (IllegalStateException expected) {
                    check(expected.getMessage().contains("type.adapter: expected 1 candidate, got 2"), expected.toString());
                }
                System.out.println("OSHARE_DEX_AMBIGUITY_PASSED");
                return;
            }
            Map<String, String> roles = OShareDexResolver.resolve(dex);
            roles.forEach((key, value) -> System.out.println(key + "=" + value));
            check(roles.size() == 34, "missing semantic roles: " + roles.size());
            if (args.length > 2 && args[2].equals("renamed")) {
                check(roles.get("type.wrapper").equals("fixture.Role0"), "fixture did not rename wrapper");
                check(roles.get("type.adapter").equals("fixture.Role3"), "fixture did not rename adapter");
                for (String key : List.of("device.key", "device.setType", "holder.image", "adapter.bind",
                        "manager.list", "manager.refresh", "activity.update"))
                    check(roles.get(key).contains("->renamedMethod"), "old method name used: " + key);
                System.out.println("OSHARE_DEX_RENAMED_PASSED");
            }
            if (args.length > 2 && args[2].equals("baseline")) {
                check(roles.get("type.wrapper").equals("l9.b"), "wrapper baseline");
                check(roles.get("type.extended").equals("v9.d"), "extended baseline");
                check(roles.get("device.setType").equals("Lv9/d;->w0(I)V"), "device type");
                check(roles.get("holder.image").equals("Lcom/oplus/oshare/ui/DeviceAdapter$b;->i()Landroid/widget/ImageView;"), "foreground");
                check(roles.get("manager.refresh").equals("Lv9/a;->r()V"), "manager notification");
                check(roles.get("service.send").equals("Lcom/oplus/oshare/OShareClient$6;->sendData(Landroid/content/Intent;Ll9/b;)V"), "send service");
                Looper.prepareMainLooper();
                OShareSymbols bound = new OShareSymbols(roles, new PathClassLoader(args[1],
                        ClassLoader.getSystemClassLoader().getParent()));
                Object device = bound.deviceConstructor.newInstance();
                bound.invoke("device.setKey", device, "airdrop:123");
                bound.invoke("device.setName", device, "DexKit test");
                bound.invoke("device.setProgress", device, 42);
                bound.invoke("device.setState", device, bound.state("TRANSIT_WAIT"));
                bound.setType(device, 2);
                Bitmap icon = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
                bound.setIcon(device, icon);
                check(bound.key(device).equals("airdrop:123"), "key round trip");
                check(bound.invoke("device.state", device).toString().equals("TRANSIT_WAIT"), "state round trip");
                check(bound.invoke("device.icon", device) == icon, "icon round trip");
                icon.recycle();
                System.out.println("OSHARE_DEX_BINDINGS_PASSED");
            }
        }
        for (List<String> values : List.of(List.<String>of(), List.of("a", "b"))) {
            try { OShareDexResolver.unique("ambiguous fixture", values); throw new AssertionError("accepted " + values); }
            catch (IllegalStateException expected) { check(expected.getMessage().contains("OSHARE_DEX_UNSUPPORTED"), "diagnostic"); }
        }
        check(OShareDexResolver.unique("duplicate reference", List.of("a", "a")).equals("a"), "deduplicate refs");
        System.out.println("OSHARE_DEX_RESOLVER_PASSED");
        System.exit(0);
    }
}
