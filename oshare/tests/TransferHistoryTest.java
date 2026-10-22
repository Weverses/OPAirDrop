package com.tlsu.oshareairdrop;

/** Real callback wrappers and timing decisions, without Xposed or a radio session. */
public final class TransferHistoryTest {
    interface Callback { void update(Target target, Metadata metadata); }
    static final class Target {
        public long a;
        Target(long id) { a = id; }
    }
    static final class Metadata {
        public int a;
        public boolean e;
        Metadata(int status) { a = status; }
    }
    static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    static void state(TransferHistory history, String expected) {
        check(history.state().equals(expected), "expected " + expected + ", got " + history.state());
    }
    public static void main(String[] args) {
        TransferHistory history = new TransferHistory();
        int[] calls = {0};
        Callback original = (target, metadata) -> calls[0]++;
        TransferHistory.Source send = history.wrap(Callback.class, original, false,
                failure -> { throw new AssertionError(failure); });
        Callback callback = (Callback) send.callback;
        check(callback.equals(callback) && callback.hashCode() == System.identityHashCode(callback), "proxy identity broken");
        send.begin(7);
        state(history, "waiting");
        for (int status : new int[]{1001, 1002, 1003}) callback.update(new Target(7), new Metadata(status));
        check(calls[0] == 3, "native callback was not forwarded");
        state(history, "waiting");
        callback.update(new Target(7), new Metadata(1005));
        state(history, "active");
        callback.update(new Target(7), new Metadata(1003));
        state(history, "active");
        callback.update(new Target(7), new Metadata(1017));
        state(history, "active");
        callback.update(new Target(7), new Metadata(1006));
        state(history, "idle");
        callback.update(new Target(7), new Metadata(1003));
        state(history, "idle");

        TransferHistory.Source retry = history.source(false);
        retry.begin(7);
        history.update(retry, 7, 1005, false);
        callback.update(new Target(7), new Metadata(1009));
        state(history, "active");
        retry.finish(7);
        state(history, "idle");

        TransferHistory.Source receive = history.source(true);
        history.update(receive, 7, 1002, false);
        state(history, "waiting");
        history.accepted(7);
        state(history, "active");
        history.update(receive, 7, 1002, false);
        state(history, "active");
        history.update(receive, 7, 1006, true);
        state(history, "idle");
        history.accepted(7);
        state(history, "idle");

        TransferHistory.Source other = history.source(false);
        other.begin(8);
        history.update(receive, 9, 1005, false);
        state(history, "active");
        history.update(receive, 9, 1006, true);
        state(history, "waiting");
        other.finish(8);
        state(history, "idle");

        for (int status : new int[]{1008, 1009, 1010, 1018}) {
            TransferHistory.Source rejected = history.source(false);
            rejected.begin(10);
            history.update(rejected, 10, status, false);
            state(history, "idle");
        }
        for (int i = 0; i < 140; i++) history.update(receive, 100+i, 1008, true);
        callback.update(new Target(7), new Metadata(1005));
        history.update(receive, 100, 1005, false);
        state(history, "idle");
        Callback throwsOriginal = (target, metadata) -> { throw new IllegalArgumentException("native failure"); };
        Callback wrapped = (Callback) history.wrap(Callback.class, throwsOriginal, true, failure -> {}).callback;
        try {
            wrapped.update(new Target(11), new Metadata(1002));
            throw new AssertionError("native callback exception was swallowed");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().equals("native failure"), "native exception changed");
        }

        IncomingTransfers<String> tasks = new IncomingTransfers<>();
        IncomingTransfers.Task<String> pending = tasks.request(1, "sender", 0);
        check(tasks.busy() && !tasks.historyActive(), "pending invitation started receive timer");
        tasks.accept(1, pending.session);
        check(tasks.historyActive(), "accept did not start receive timer");
        tasks.update(pending, "sender", 1006, 100, true);
        check(tasks.historyActive(), "publication time was dropped");
        tasks.finish(pending, "complete", "");
        check(!tasks.historyActive(), "completed publication retained timer");
        IncomingTransfers.Task<String> denied = tasks.request(2, "sender", 0);
        tasks.finish(denied, "rejected", "");
        check(!tasks.historyActive(), "rejected invitation was counted");
        System.out.println("TransferHistory passed: callback forwarding, confirmation waits, native exceptions, stale callbacks, concurrent tasks and publication");
    }
}
