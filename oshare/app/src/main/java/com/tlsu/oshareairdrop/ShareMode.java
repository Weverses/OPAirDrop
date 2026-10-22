package com.tlsu.oshareairdrop;

import java.util.concurrent.Callable;

/** AirDrop entry selection; ordinary Nearby/Quick Share protocols stay in GMS. */
enum ShareMode {
    QUICKSHARE, OSHARE, BOTH;

    static final String SETTING = "opairshare_share_mode";
    static final String PROPERTY = "debug.opairshare.share_mode";
    private static final ThreadLocal<Boolean> BRIDGE = ThreadLocal.withInitial(() -> false);

    static ShareMode parse(String value) {
        if ("quickshare".equals(value)) return QUICKSHARE;
        if ("oshare".equals(value)) return OSHARE;
        return BOTH;
    }

    boolean usesOShare() { return this != QUICKSHARE; }

    static boolean startsWork(String method) {
        return switch (method) {
            case "g", "k", "f", "j", "d" -> true;
            default -> false;
        };
    }

    // null means pass through. Stops/directory updates are harmless no-ops;
    // starting a disabled native AirDrop operation must report failure.
    Integer nativeResult(String method) {
        if (this != OSHARE || BRIDGE.get()) return null;
        return switch (method) {
            case "i", "h", "m" -> 0;
            case "g", "k", "f", "j", "d", "a", "b", "c" -> 1;
            default -> null;
        };
    }

    static <T> T bridgeCall(Callable<T> action) throws Exception {
        boolean previous = BRIDGE.get();
        BRIDGE.set(true);
        try { return action.call(); }
        finally { BRIDGE.set(previous); }
    }
}
