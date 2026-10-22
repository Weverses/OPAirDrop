package com.tlsu.oshareairdrop;

import java.util.concurrent.ConcurrentHashMap;

/** Each send owns a distinct lease, even when the peer id is reused. */
public final class OutgoingTransfers<T> {
    public static final class BusyException extends IllegalStateException {
        public BusyException() { super("当前任务正在等待接收或传输中，请先取消后再发送"); }
    }

    public static final class Lease<T> {
        public final long targetId;
        public final T resource;
        public final String session;
        private Lease(long targetId, T resource, String session) {
            this.targetId = targetId;
            this.resource = resource;
            this.session = session;
        }
    }

    private final ConcurrentHashMap<Long, Lease<T>> active = new ConcurrentHashMap<>();

    public Lease<T> begin(long targetId, T resource) {
        return begin(targetId, resource, java.util.UUID.randomUUID().toString());
    }

    public Lease<T> begin(long targetId, T resource, String session) {
        if (session == null || session.isEmpty()) throw new IllegalArgumentException("empty outgoing session");
        Lease<T> lease = new Lease<>(targetId, resource, session);
        if (active.putIfAbsent(targetId, lease) != null) throw new BusyException();
        return lease;
    }

    public boolean isCurrent(Lease<T> lease) { return active.get(lease.targetId) == lease; }
    public Lease<T> current(long targetId) { return active.get(targetId); }
    public boolean isActive(long targetId) { return active.containsKey(targetId); }
    public boolean hasActive() { return !active.isEmpty(); }
    public boolean matches(long targetId, String session) {
        Lease<T> lease = active.get(targetId);
        return lease != null && session != null && !session.isEmpty() && session.equals(lease.session);
    }
    public boolean finish(Lease<T> lease) { return active.remove(lease.targetId, lease); }

    public static boolean isTerminal(int status, boolean finalFlag) {
        if (finalFlag) return true;
        switch (status) {
            case 1004: case 1006: case 1007: case 1008: case 1009: case 1010:
            case 1011: case 1013: case 1014: case 1015: case 1018: case 1020: case 1025:
                return true;
            default: return false;
        }
    }

    public static double progress(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(100, value)) : 0;
    }
}
