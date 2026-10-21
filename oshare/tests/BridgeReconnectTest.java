package com.tlsu.oshareairdrop;

/** Missed hints, death, repeated hints and HELLO must leave exactly one retry. */
public final class BridgeReconnectTest {
    private static final class Clock implements BridgeReconnect.Scheduler {
        Runnable pending;
        long delay;
        public void remove(Runnable task) { if (pending == task) pending = null; }
        public void post(Runnable task, long delay) {
            if (pending != null) throw new AssertionError("duplicate connection chain");
            pending = task; this.delay = delay;
        }
        void advance() {
            Runnable task = pending;
            if (task == null) throw new AssertionError("missing retry");
            pending = null; task.run();
        }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Clock clock = new Clock();
        boolean[] alive = {false}, finishDuringAttempt = {false};
        int[] connects = {0};
        BridgeReconnect retry = new BridgeReconnect(clock, () -> alive[0], () -> {
            connects[0]++;
            if (finishDuringAttempt[0]) alive[0] = true;
        });
        retry.wake(); retry.wake(); retry.wake();
        check(clock.delay == 0, "startup hints did not connect immediately");
        for (long delay : new long[]{250, 750, 1500, 5000, 5000}) {
            clock.advance(); check(clock.delay == delay, "missing-hint retry backoff failed");
        }
        retry.wake();
        check(clock.delay == 0, "ready provider waited for old slow retry");
        clock.advance();
        alive[0] = true; retry.connected(); retry.connected(); retry.wake();
        check(clock.delay == 30000, "duplicate HELLO/hint accelerated a healthy connection");
        int before = connects[0]; clock.advance();
        check(connects[0] == before && clock.delay == 30000, "healthy connection reattached");
        alive[0] = false; retry.lost();
        check(clock.delay == 0, "Binder death did not trigger immediate reconnect");
        clock.advance(); check(clock.delay == 250, "death did not reset fast retries");
        finishDuringAttempt[0] = true; clock.advance();
        check(clock.delay == 30000, "inline ready response left a stale retry");
        System.out.println("BridgeReconnect passed: ready hints, lost hints, duplicate HELLO, death, bounded retries, one chain");
    }
}
