import com.tlsu.oshareairdrop.IncomingTransfers;

/** Pure-Java receive admission, stale-command and file-publication regressions. */
public final class IncomingTransfersTest {
    private static int failures;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejected(Runnable operation, String reason) {
        try { operation.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError(reason);
    }
    private static void test(String name, Runnable body) {
        try { body.run(); System.out.println("PASS IncomingTransfers " + name); }
        catch (Throwable failure) {
            failures++;
            System.err.println("FAIL IncomingTransfers " + name + ": " + failure.getMessage());
        }
    }

    public static void main(String[] args) {
        test("duplicate invitation and stale command tokens", IncomingTransfersTest::tokensAndDuplicates);
        test("one incoming task through pending, accepting and verifying", IncomingTransfersTest::singleIncoming);
        test("late invitation cannot restore Accept", IncomingTransfersTest::lateInvitation);
        test("protocol complete retains publication lease", IncomingTransfersTest::publicationLease);
        test("publication retry reacquires lease", IncomingTransfersTest::publicationRetry);
        test("failure states and unfamiliar final callback", IncomingTransfersTest::failures);
        test("terminal tombstones and same-peer next request", IncomingTransfersTest::samePeerRetry);
        test("retained history and stale retired callbacks", IncomingTransfersTest::boundedHistory);
        test("invalid progress cannot poison snapshots", IncomingTransfersTest::progress);
        if (failures != 0) throw new AssertionError(failures + " IncomingTransfers regressions failed");
        System.out.println("IncomingTransfers: all receive state regressions passed");
    }

    private static void tokensAndDuplicates() {
        IncomingTransfers<Object> tasks = new IncomingTransfers<>();
        Object target = new Object();
        IncomingTransfers.Task<Object> first = tasks.request(-37, target, 100);
        IncomingTransfers.Task<Object> duplicate = tasks.request(-37, new Object(), 200);
        check(duplicate == first, "duplicate callback created a second invitation");
        check(first.target == target && first.created == 100, "duplicate replaced original target or invitation deadline");
        check(tasks.snapshot().size() == 1, "duplicate invitation leaked into history");
        check(tasks.require(-37, first.session) == first, "current token rejected");
        rejected(() -> tasks.require(-37, "stale"), "stale token authorized a decision");
        rejected(() -> tasks.require(-37, null), "null token authorized a decision");
        rejected(() -> tasks.require(999, first.session), "token authorized a different peer");
        IncomingTransfers.Task<Object> afterRestart = new IncomingTransfers<Object>().request(-37, target, 100);
        check(!afterRestart.session.equals(first.session), "process generations reused an actionable token");
        tasks.finish(first, "rejected", "user rejected");
        rejected(() -> tasks.require(-37, first.session), "terminal token remains actionable");
        check(!tasks.update(first, target, 1005, 10, false), "late callback resurrected terminal task");
        check(first.phase.equals("rejected") && first.message.equals("user rejected"), "late callback overwrote terminal decision");
    }

    private static void singleIncoming() {
        IncomingTransfers<String> tasks = new IncomingTransfers<>();
        IncomingTransfers.Task<String> first = tasks.request(1, "sender-one", 0);
        check(tasks.busy() && tasks.active(1), "pending invitation failed to hold session");
        rejected(() -> tasks.request(2, "sender-two", 0), "second invitation admitted while first pending");
        tasks.accept(1, first.session);
        check(first.phase.equals("accepting") && tasks.busy(), "accept submission released session");
        rejected(() -> tasks.accept(1, first.session), "Accept was submitted twice");
        rejected(() -> tasks.request(2, "sender-two", 0), "second invitation admitted while accepting");
        tasks.update(first, "sender-one", 1005, 30, false);
        check(first.phase.equals("receiving"), "active protocol transfer not represented");
        tasks.update(first, "sender-one", 1017, 100, false);
        check(first.phase.equals("verifying") && !first.terminal && tasks.busy(), "verification released session prematurely");
        rejected(() -> tasks.request(2, "sender-two", 0), "second invitation admitted during verification");
        tasks.finish(first, "complete", "saved");
        check(!tasks.busy() && !tasks.active(1), "terminal task retained admission lease");
        check(tasks.request(2, "sender-two", 1).phase.equals("pending"), "next sender not admitted after completion");
    }

    private static void lateInvitation() {
        IncomingTransfers<String> tasks = new IncomingTransfers<>();
        IncomingTransfers.Task<String> task = tasks.request(3, "original", 10);
        tasks.accept(3, task.session);
        check(tasks.update(task, "confirm", 1002, 0, false), "duplicate invitation during accepting should be harmless");
        check(task.phase.equals("accepting"), "duplicate invitation re-enabled Accept");
        tasks.update(task, "receiving", 1005, 45, false);
        check(!tasks.update(task, "late-original", 1002, 0, false), "late invitation was applied after receiving began");
        check(task.phase.equals("receiving") && task.progress == 45 && task.target.equals("receiving"), "late invitation rewound the active task");
    }

    private static void publicationLease() {
        IncomingTransfers<String> tasks = new IncomingTransfers<>();
        IncomingTransfers.Task<String> task = tasks.request(4, "sender", 0);
        tasks.accept(4, task.session);
        check(tasks.update(task, "completed", 1006, 100, true), "protocol completion was discarded");
        check(task.phase.equals("publishing") && !task.terminal && tasks.busy(), "protocol final flag exposed completion before files were published");
        rejected(() -> tasks.request(5, "another", 1), "publication lease did not block new transfer");
        check(!tasks.update(task, "late-error", 1007, 0, true), "late protocol error overrode file publication");
        check(task.target.equals("completed") && task.status == 1006, "publication inputs were overwritten");
        tasks.finish(task, "complete", "saved to Downloads");
        check(task.terminal && !tasks.busy(), "published files did not release receive lease");
        tasks.finish(task, "failed", "late callback");
        check(task.phase.equals("complete"), "late completion callback rewrote terminal result");
    }

    private static void publicationRetry() {
        IncomingTransfers<String> tasks = new IncomingTransfers<>();
        IncomingTransfers.Task<String> task = tasks.request(6, "sender", 0);
        tasks.update(task, "received-files", 1006, 100, true);
        tasks.finish(task, "failed", "no space");
        check(task.status == 1006 && task.terminal && !tasks.busy(), "publication failure lost completed protocol state");
        rejected(() -> tasks.require(6, task.session), "generic protocol actions allowed after publication failure");
        // IncomingReceiver.retryPublish authorizes the old session, then performs this
        // explicit transition. File-I/O completion still uses the same task identity.
        task.terminal = false; task.phase = "publishing"; task.message = "";
        check(tasks.busy() && tasks.active(6), "publication retry failed to reacquire receive lease");
        rejected(() -> tasks.request(7, "another", 1), "new transfer admitted during publication retry");
        check(!tasks.update(task, "late-wire-state", 1005, 0, false), "protocol callback altered publication retry");
        tasks.finish(task, "complete", "saved after freeing space");
        check(task.terminal && task.phase.equals("complete") && !tasks.busy(), "successful retry remained failed or busy");
    }

    private static void failures() {
        int[] statuses = {1004, 1007, 1008, 1009, 1010, 1011, 1013, 1014, 1015, 1018, 1020, 1025, 1029};
        for (int status : statuses) {
            IncomingTransfers<String> tasks = new IncomingTransfers<>();
            IncomingTransfers.Task<String> task = tasks.request(status, "sender", 0);
            tasks.update(task, "sender", status, 50, false);
            String phase = status == 1008 ? "rejected" : status == 1009 || status == 1018
                    ? "cancelled" : status == 1010 ? "expired" : "failed";
            check(task.terminal && task.phase.equals(phase) && !tasks.busy(), "failure mapping incorrect for " + status);
        }
        IncomingTransfers<String> tasks = new IncomingTransfers<>();
        IncomingTransfers.Task<String> task = tasks.request(10, "sender", 0);
        tasks.update(task, "sender", 9999, 1, false);
        check(!task.terminal && tasks.busy(), "unknown non-final state discarded active receive");
        tasks.update(task, "sender", 9999, 1, true);
        check(task.terminal && task.phase.equals("failed") && !tasks.busy(), "unknown final state stranded receive lease");
    }

    private static void samePeerRetry() {
        IncomingTransfers<String> tasks = new IncomingTransfers<>();
        IncomingTransfers.Task<String> old = tasks.request(11, "same-sender", 10);
        tasks.finish(old, "rejected", "declined");
        // Mosey60715 allocates a monotonically increasing target ID for each ASK,
        // even from the same peer (cdw -> cfm.b.a -> cfl AtomicLong). Same-ID1002
        // after a terminal event is a duplicate, not evidence of a new invitation.
        check(tasks.request(11, "late-same-id", 20) == old, "terminal duplicate created a new actionable request");
        check(old.terminal && old.phase.equals("rejected"), "duplicate invitation resurrected terminal state");
        rejected(() -> tasks.require(11, old.session), "old terminal decision token remained actionable");
        IncomingTransfers.Task<String> next = tasks.request(12, "same-sender", 20);
        check(next != old && !next.session.equals(old.session), "next ASK did not get distinct task identity");
        check(next.phase.equals("pending") && !next.terminal && next.created == 20, "next ASK retained previous terminal state");
        rejected(() -> tasks.require(12, old.session), "old decision token authorized next request");
        check(!tasks.update(old, "late-old-callback", 1007, 100, true), "retired callback changed old terminal result");
        tasks.finish(old, "failed", "late failure");
        check(tasks.current(12) == next && !next.terminal && tasks.busy(), "old completion released next request");
    }

    private static void boundedHistory() {
        IncomingTransfers<String> tasks = new IncomingTransfers<>();
        IncomingTransfers.Task<String> oldest = null;
        for (int i = 0; i < 70; i++) {
            IncomingTransfers.Task<String> task = tasks.request(i, "sender", i);
            if (i == 0) oldest = task;
            tasks.finish(task, "cancelled", "done");
        }
        check(tasks.snapshot().size() <= 64, "unbounded terminal history");
        check(tasks.current(0) == null, "old task not evicted");
        check(!tasks.update(oldest, "stale", 1005, 80, false), "evicted callback resurrected task");
        rejected(() -> tasks.request(0, "late-invitation", 100), "evicted invitation bypassed request high-watermark");
        tasks.snapshot().clear();
        check(tasks.snapshot().size() == 64, "UI snapshot mutated authoritative state");
    }

    private static void progress() {
        IncomingTransfers<String> tasks = new IncomingTransfers<>();
        IncomingTransfers.Task<String> task = tasks.request(12, "sender", 0);
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 150, 23.5}) {
            tasks.update(task, "sender", 1005, value, false);
            check(Double.isFinite(task.progress) && task.progress >= 0 && task.progress <= 100, "invalid progress escaped into state");
        }
        check(task.progress == 23.5, "fractional provider progress was lost");
    }
}
