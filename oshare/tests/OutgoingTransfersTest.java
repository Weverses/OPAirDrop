import com.tlsu.oshareairdrop.OutgoingTransfers;

public final class OutgoingTransfersTest {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        OutgoingTransfers<String> transfers = new OutgoingTransfers<>();
        long id = -459914851;
        OutgoingTransfers.Lease<String> first = transfers.begin(id, "first-cache");
        try { transfers.begin(id, "duplicate"); throw new AssertionError("duplicate accepted"); }
        catch (OutgoingTransfers.BusyException expected) {}
        check(transfers.isCurrent(first), "duplicate corrupted the waiting transfer");
        check(!OutgoingTransfers.isTerminal(1003, false), "waiting for acceptance must stay active");
        for (int status : new int[]{1006, 1007, 1008, 1009, 1010, 1018}) {
            check(OutgoingTransfers.isTerminal(status, false), "terminal status " + status);
        }
        check(OutgoingTransfers.isTerminal(9999, true), "new final status from provider");
        check(OutgoingTransfers.progress(Double.NaN) == 0, "NaN must not break terminal JSON");
        check(OutgoingTransfers.progress(Double.POSITIVE_INFINITY) == 0, "infinite progress");
        check(transfers.finish(first), "completion did not release peer");
        OutgoingTransfers.Lease<String> next = transfers.begin(id, "next-cache");
        check(!transfers.finish(first), "late old callback released new task");
        check(transfers.isCurrent(next), "new task lost after old callback");
        check(transfers.finish(next), "cancel did not release peer");
        OutgoingTransfers.Lease<String> retry = transfers.begin(id, null);
        check(transfers.finish(retry), "failed submission did not release text task");
        check(!transfers.isActive(id), "lease retained");
        System.out.println("OutgoingTransfers: duplicate, completion, cancellation, retry and stale callback regressions passed");
    }
}
