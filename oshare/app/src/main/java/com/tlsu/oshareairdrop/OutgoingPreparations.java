package com.tlsu.oshareairdrop;

import java.util.HashMap;
import java.util.Map;

/** Provider-side, bounded idle barrier while the caller opens its granted content URIs. */
final class OutgoingPreparations {
    static final long TIMEOUT_MS = 15_000;
    private record Key(int uid, String session) {}
    private final Map<Key, Long> leases = new HashMap<>();

    synchronized boolean acquire(int uid, String session, long now) {
        expire(now);
        if (session == null || session.isEmpty() || session.length() > 128) return false;
        Key key = new Key(uid, session);
        if (leases.containsKey(key)) return true; // retries never extend a lease
        if (leases.size() >= PendingShares.LIMIT) return false;
        leases.put(key, now + TIMEOUT_MS);
        return true;
    }
    synchronized void release(int uid, String session) { leases.remove(new Key(uid, session)); }
    synchronized boolean busy(long now) { expire(now); return !leases.isEmpty(); }
    private void expire(long now) { leases.values().removeIf(deadline -> now >= deadline); }
}
