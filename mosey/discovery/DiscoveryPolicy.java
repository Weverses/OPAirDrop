package dev.evermore.opairshare;

import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.SupplicantState;
import android.util.Log;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Default-off opt-in policy for the verified fixed149 discovery profile.
 * A configured discovery request on another channel fails before the original
 * controller's autojoin/disconnect block. Transfers retain the same interface and request a bounded TX profile upgrade.
 */
public final class DiscoveryPolicy {
    private static final String TAG = "OPAIRSHAREDiscovery";
    private static final Object LOCK = new Object();
    private static final Set<Long> transfers = new HashSet<>();
    private static int discoveryNetwork = -1;
    private static boolean discoveryReady;
    private static String advertisingName;
    private static long id(Object target) throws Exception {
        return target.getClass().getField("a").getLong(target);
    }
    public static boolean isTransferActive() {
        synchronized (LOCK) { return !transfers.isEmpty(); }
    }
    private static String property(String name) {
        try {
            return (String)Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class).invoke(null, name, "");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Discovery control unavailable", e);
        }
    }
    private static boolean enabled() {
        return "same149".equals(property("debug.opairshare.discovery")) || "cross5g".equals(property("debug.opairshare.discovery"));
    }
    private static boolean allowedFrequency(int freq) {
        return "same149".equals(property("debug.opairshare.discovery")) ? freq == 5745 : freq == 5200;
    }
    private static int discoveryFrequency;
    private static boolean standbyOwned() {
        return enabled() &&
            "1".equals(property("debug.opairshare.standby"));
    }
    /** Explicit module-owned visibility overrides a passive GMS lockscreen mode.
     * It never suppresses user Accept/Reject, shutdown, or an active transfer Stop.
     */
    public static Object advertisingOptions(Object options) {
        if (!standbyOwned() || options == null) return options;
        synchronized (LOCK) {
            try {
                Class<?> type=options.getClass();
                String name=(String)type.getField("b").get(options);
                if (name != null && !name.isEmpty()) advertisingName=name;
                int mode=type.getField("a").getInt(options);
                boolean plaintext=type.getField("c").getBoolean(options);
                if (!transfers.isEmpty() || advertisingName == null || (mode != 3 && mode != 4 && plaintext))
                    return options;
                boolean pin=type.getField("d").getBoolean(options);
                Object normalized=type.getConstructor(int.class,String.class,boolean.class,boolean.class)
                    .newInstance(0,advertisingName,true,pin);
                Log.i(TAG,"STANDBY_VISIBILITY_OWNED previous_mode="+mode);
                return normalized;
            } catch (ReflectiveOperationException e) {throw new IllegalStateException("Advertising options changed",e);}
        }
    }
    public static boolean retainAdvertising() {
        if (!standbyOwned()) return false;
        synchronized (LOCK) {
            if (advertisingName == null || !transfers.isEmpty()) return false;
            Log.i(TAG,"STANDBY_STOP_RETAINED"); return true;
        }
    }
    public static boolean keepWifi(WifiManager wifi, List<?> channels) {
        if (!enabled()) return false;
        WifiInfo info = wifi.getConnectionInfo();
        if (!wifi.isWifiEnabled() || info == null ||
            !allowedFrequency(info.getFrequency()) || info.getSupplicantState() != SupplicantState.COMPLETED ||
            channels == null || channels.size() != 1 || !Integer.valueOf(149).equals(channels.get(0))) {
            synchronized (LOCK) { discoveryReady=false; }
            Log.w(TAG, "DISCOVERY_REFUSED frequency="+(info==null?0:info.getFrequency())+" supplicant="+(info==null?null:info.getSupplicantState())+" channels="+channels);
            throw new IllegalStateException("Background discovery requires verified connected STA and fixed AWDL149");
        }
        synchronized (LOCK) { discoveryNetwork = info.getNetworkId(); discoveryFrequency = info.getFrequency(); discoveryReady=true; }
        Log.i(TAG, "DISCOVERY_WIFI_RETAINED network=" + info.getNetworkId() + " frequency=" + info.getFrequency());
        return true;
    }
    /** Called only after the original Accept validation or explicit Send routing. */
    public static void beginTransfer(WifiManager wifi, Object target) {
        if (!enabled()) return;
        synchronized (LOCK) {
            try {
                long key=id(target);
                if (!discoveryReady) throw new IllegalStateException("No verified discovery session");
                WifiInfo info=wifi.getConnectionInfo();
                if (!wifi.isWifiEnabled() || info == null ||
                    info.getSupplicantState() != SupplicantState.COMPLETED ||
                    (discoveryNetwork >= 0 && info.getNetworkId() >= 0 && info.getNetworkId() != discoveryNetwork) || info.getFrequency() != discoveryFrequency)
                    throw new IllegalStateException("STA changed away from verified discovery network/frequency");
                if (transfers.contains(key)) return;
                transfers.add(key);
                Log.i(TAG,"FULL_TRANSFER_BEGIN id="+key+" owners="+transfers.size());
            } catch (Exception e) { throw new IllegalStateException("Cannot enter transfer state",e); }
        }
    }
    public static void sendResult(Object target, int status) {
        if (status == 0) return;
        try { finish(id(target),"send-rejected"); }
        catch (Exception e) { Log.e(TAG,"SEND_RESULT_ERROR",e); }
    }
    /** Runs before the real Binder transfer update, for either direction. */
    public static void onMetadata(Object target, Object metadata) {
        if (target == null || metadata == null) return;
        synchronized (LOCK) { if (transfers.isEmpty()) return; }
        try {
            if (metadata.getClass().getField("e").getBoolean(metadata))
                finish(id(target),"terminal-"+metadata.getClass().getField("a").getInt(metadata));
        } catch (Exception e) { Log.e(TAG,"TRANSFER_METADATA_ERROR",e); }
    }
    private static void finish(long key, String reason) {
        synchronized (LOCK) {
            if (!transfers.remove(key)) return;
            Log.i(TAG,"TRANSFER_END id="+key+" reason="+reason+" owners="+transfers.size());
        }
    }

}
