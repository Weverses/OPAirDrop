package com.tlsu.oshareairdrop;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Production policies: mixed peers, namespace collisions, lease isolation and late file preparation. */
public final class ChooserIntegrationTest {
    record Peer(String key, String name) {}
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void targets() {
        Peer n1 = new Peer("native:1", "one"), n2 = new Peer("native:2", "two"), n3 = new Peer("native:3", "three");
        Peer a1 = new Peer("airdrop:1", "current"), stale = new Peer("airdrop:1", "stale"), a2 = new Peer("airdrop:2", "second");
        check(ChooserTargets.merge(List.of(n1, n2, n3), Map.of(), Peer::key).equals(List.of(n1, n2, n3)), "native unchanged");
        check(ChooserTargets.merge(List.of(n1, n2, n3), Map.of(a1.key(), a1), Peer::key)
                .equals(List.of(n1, n2, a1)), "reserve a slot without changing native order");
        check(ChooserTargets.merge(List.of(n1, stale, n1), Map.of(a1.key(), a1), Peer::key)
                .equals(List.of(n1, a1)), "deduplicate and replace stale objects");
        check(ChooserTargets.merge(List.of(stale, n1), Map.of(), Peer::key).equals(List.of(n1)), "remove lost devices");
        LinkedHashMap<String, Peer> snapshot = new LinkedHashMap<>();
        snapshot.put(a2.key(), a2); snapshot.put(a1.key(), a1);
        check(ChooserTargets.merge(List.of(n1), snapshot, Peer::key).equals(List.of(n1, a1, a2)), "stable peer order");
        check(ChooserTargets.merge(List.of(), Map.of("airdrop:9", n1), Peer::key).isEmpty(), "reject mismatched ownership");
        check(ChooserTargets.merge(List.of(), Map.of("native:1", n1), Peer::key).isEmpty(), "only owned namespace");
    }
    static void leases() {
        ChooserSessions<String> leases = new ChooserSessions<>();
        leases.put(10001, 42, "old");
        leases.put(10002, 42, "another user");
        check("old".equals(leases.put(10001, 42, "replacement")), "replace same tag");
        leases.died("old");
        check(leases.contains("replacement"), "late death cannot remove replacement");
        leases.remove(10001, 42);
        check(!leases.isEmpty(), "one unregister cannot stop another client");
        leases.put(10002, 43, "another user");
        leases.died("another user");
        check(leases.isEmpty(), "Binder death releases all registrations for that Binder");
    }
    static void submissions() {
        PendingShares<String> queue = new PendingShares<>();
        PendingShares.Entry<String> cancelled = queue.offer("a", "old descriptors", 100);
        check(queue.offer("a", "duplicate click", 100) == null, "one preparation per peer");
        queue.remove("a");
        PendingShares.Entry<String> current = queue.offer("a", "new descriptors", 200);
        check(!queue.current(cancelled, 201), "late completion after cancel must not send");
        check(!queue.remove(cancelled) && queue.current(current, 201), "old timeout cannot remove new request");
        check(queue.current(current, 200 + PendingShares.TIMEOUT_MS - 1), "deadline before expiry");
        check(!queue.current(current, 200 + PendingShares.TIMEOUT_MS), "deadline at expiry");
        for (int i = 0; i < PendingShares.LIMIT - 1; i++) check(queue.offer("p" + i, "files", 200) != null, "fill queue");
        check(queue.offer("overflow", "files", 200) == null, "bounded preparation queue");
        for (PendingShares.Entry<String> entry : queue.snapshot()) queue.remove(entry);
        check(queue.isEmpty(), "disconnect drains all pending ownership");
    }
    public static void main(String[] args) {
        targets(); leases(); submissions();
        OutgoingPreparations preparing = new OutgoingPreparations();
        check(!preparing.busy(0), "idle provider");
        check(preparing.acquire(42, "session", 100), "prepare request");
        preparing.release(43, "session");
        check(preparing.busy(200), "another UID cannot release the barrier");
        check(preparing.acquire(42, "session", 500), "duplicate ACK request");
        check(!preparing.busy(100 + OutgoingPreparations.TIMEOUT_MS), "retry cannot extend timeout");
        check(!preparing.acquire(42, "", 0), "empty session rejected");
        for (int i = 0; i < PendingShares.LIMIT; i++) check(preparing.acquire(42, "s" + i, 0), "bounded lease capacity");
        check(!preparing.acquire(42, "overflow", 0), "provider cannot accumulate unbounded preparations");
        for (int i = 0; i < PendingShares.LIMIT; i++) preparing.release(42, "s" + i);
        check(!preparing.busy(1), "submit/cancel releases idle barrier");
        OutgoingTransfers<String> staging = new OutgoingTransfers<>();
        OutgoingTransfers.Lease<String> staged = staging.begin(1, "files", "staging-session");
        check(staging.hasActive(), "staging files is busy before transport target registration");
        staging.finish(staged);
        check(!staging.hasActive(), "failed or completed staging releases ownership");
        System.out.println("Chooser candidates, sessions and pending submissions passed");
    }
}
