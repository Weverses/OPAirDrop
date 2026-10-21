package com.tlsu.oshareairdrop;

import java.util.function.BooleanSupplier;

/** One retry chain: readiness hints wake it, successful handshakes slow it down. */
final class BridgeReconnect implements Runnable {
    interface Scheduler {
        void remove(Runnable task);
        void post(Runnable task, long delay);
    }
    private final Scheduler scheduler;
    private final BooleanSupplier alive;
    private final Runnable connect;
    private int attempts;

    BridgeReconnect(Scheduler scheduler, BooleanSupplier alive, Runnable connect) {
        this.scheduler = scheduler; this.alive = alive; this.connect = connect;
    }

    void wake() { if (!alive.getAsBoolean()) schedule(0); }
    void connected() { attempts = 0; schedule(30000); }
    void lost() { attempts = 0; schedule(0); }

    private void schedule(long delay) {
        scheduler.remove(this);
        scheduler.post(this, delay);
    }

    @Override public void run() {
        if (alive.getAsBoolean()) { connected(); return; }
        connect.run();
        if (alive.getAsBoolean()) { connected(); return; }
        attempts++;
        schedule(attempts == 1 ? 250 : attempts == 2 ? 750 : attempts == 3 ? 1500 : 5000);
    }
}
