package com.tlsu.oshareairdrop;

import java.util.List;

/** The installed OPAIRSHARE daemon accepts fixed149, even when country lookup falls back to 6. */
public final class DiscoveryChannels {
    private DiscoveryChannels() {}
    public static List<?> select(String policy, int staMhz, boolean connected, List<?> requested) {
        boolean owned = ("same149".equals(policy) && staMhz == 5745)
                || ("cross5g".equals(policy) && staMhz == 5200);
        if (!owned || !connected || requested == null || requested.isEmpty()) return requested;
        return List.of(149);
    }
}
