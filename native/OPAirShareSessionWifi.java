import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Looper;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Root-only UI-session metadata; never stores SSIDs, passwords or MACs. */
public final class OPAirShareSessionWifi {
    private static boolean autojoin(WifiManager wifi) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] value = new boolean[1];
        wifi.getClass().getMethod("queryAutojoinGlobal", Executor.class, Consumer.class)
            .invoke(wifi, (Executor) Runnable::run, (Consumer<Boolean>) enabled -> {
                value[0] = enabled;
                latch.countDown();
            });
        if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("autojoin query timed out");
        return value[0];
    }

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0 || args.length != 2)
            throw new IllegalArgumentException("root: capture|restore /data/adb/.../session-wifi");
        Path path = Path.of(args[1]);
        if (!path.isAbsolute() || !path.normalize().equals(path) ||
                !path.startsWith("/data/adb/opairshare/"))
            throw new IllegalArgumentException("unexpected session state path");
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Context context = (Context) at.getMethod("getSystemContext")
            .invoke(at.getMethod("systemMain").invoke(null));
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        String boot = Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim();
        if (args[0].equals("finish-original")) {
            // One-time recovery for a prior forcibly stopped Mosey controller:
            // its original normal shutdown calls allowAutojoinGlobal(true).
            wifi.getClass().getMethod("allowAutojoinGlobal", boolean.class).invoke(wifi, true);
            if (!autojoin(wifi)) throw new IllegalStateException("original autojoin release failed");
            System.out.println("ORIGINAL_MOSEY_AUTOJOIN_RELEASED");
            return;
        }
        if (args[0].equals("capture")) {
            if (Files.exists(path)) throw new IllegalStateException("unrestored session metadata");
            int network = wifi.isWifiEnabled() && wifi.getConnectionInfo() != null
                ? wifi.getConnectionInfo().getNetworkId() : -1;
            boolean auto = autojoin(wifi);
            Files.writeString(path, boot + "\n" + network + "\n" + auto + "\n");
            System.out.println("SESSION_WIFI_CAPTURED network=" + network + " autojoin=" + auto);
            return;
        }
        if (!args[0].equals("restore")) throw new IllegalArgumentException("invalid operation");
        String[] saved = Files.readString(path).split("\n");
        if (saved.length != 3 || !saved[0].equals(boot) ||
                !(saved[2].equals("true") || saved[2].equals("false")))
            throw new IllegalStateException("session boot or metadata mismatch");
        int network = Integer.parseInt(saved[1]);
        if (network < -1) throw new IllegalStateException("invalid saved network");
        boolean auto = Boolean.parseBoolean(saved[2]);
        if (autojoin(wifi) != auto)
            wifi.getClass().getMethod("allowAutojoinGlobal", boolean.class).invoke(wifi, auto);
        if (autojoin(wifi) != auto) throw new IllegalStateException("autojoin restore not confirmed");
        int currentNetwork = wifi.getConnectionInfo() == null ? -1 : wifi.getConnectionInfo().getNetworkId();
        if (network >= 0 && wifi.isWifiEnabled() && (currentNetwork < 0 || currentNetwork == network)) {
            boolean exists = wifi.getConfiguredNetworks().stream().anyMatch(c -> c.networkId == network);
            if (exists) {
                Class<?> listener = Class.forName("android.net.wifi.WifiManager$ActionListener");
                Object callback = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                    (proxy, method, values) -> {
                        if (method.getName().equals("onSuccess")) {
                            Files.delete(path);
                            System.out.println("SESSION_WIFI_RESTORED autojoin=" + auto + " reconnect=request-accepted");
                            System.exit(0);
                        }
                        if (method.getName().equals("onFailure")) {
                            System.err.println("saved network reconnect request rejected");
                            System.exit(1);
                        }
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == values[0];
                        if (method.getName().equals("toString")) return "OPAirShareSessionWifi callback";
                        return null;
                    });
                wifi.getClass().getMethod("connect", int.class, listener).invoke(wifi, network, callback);
                new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
                    System.err.println("saved network reconnect request timed out");
                    System.exit(2);
                }, 8000);
                Looper.loop();
                return;
            }
        }
        Files.delete(path);
        System.out.println("SESSION_WIFI_RESTORED autojoin=" + auto);
    }
}
