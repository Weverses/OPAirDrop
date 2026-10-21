package com.tlsu.oshareairdrop;

import java.util.ArrayList;
import java.util.List;

/** Reproduce GMS mode=0 updates and callback replacement while RX stays owned. */
public final class DiscoverySessionTest {
    public record Target(long id) {}
    public record Options(int mode) {}
    public interface Callback {
        void a(Target target);
        void b(Target target, int status, Object metadata);
        void c(Target target);
        void d(Target target);
    }
    private static final class Client implements Callback {
        final List<String> events = new ArrayList<>();
        boolean fail;
        public void a(Target t) { events.add("found:" + t.id()); if (fail) throw new IllegalStateException("client died"); }
        public void b(Target t, int s, Object metadata) { events.add("status:" + s); }
        public void c(Target t) { events.add("lost:" + t.id()); }
        public void d(Target t) { events.add("updated:" + t.id()); }
    }
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        boolean[] wanted = {false};
        List<String> errors = new ArrayList<>();
        DiscoverySession session = new DiscoverySession(Callback.class, () -> wanted[0],
                target -> ((Target) target).id(), (message, failure) -> errors.add(message));
        Client nativeClient = new Client(), bridge = new Client();
        Options idle = new Options(0), active = new Options(1);
        check(session.start(false, nativeClient, idle, 0).options == idle, "native idle changed without bridge");
        wanted[0] = true;
        check(session.start(true, bridge, active, 1).options == active, "background RX not discovering");
        Callback physical = (Callback) session.callback();
        Target mac = new Target(1), lost = new Target(2);
        physical.a(mac); physical.a(lost); physical.c(lost);
        check(nativeClient.events.isEmpty(), "idle native UI received bridge-only events");
        check(bridge.events.equals(List.of("found:1", "found:2", "lost:2")), "bridge callback replaced");

        DiscoverySession.Change enter = session.update(active, 1);
        check(enter.replay && enter.options == active, "native foreground update lost");
        session.replayNative();
        check(nativeClient.events.equals(List.of("found:1")), "native UI did not get live target snapshot");
        check(bridge.events.size() == 3, "native snapshot duplicated bridge events");
        physical.d(mac); physical.b(mac, 7, new Object());
        check(nativeClient.events.contains("updated:1") && nativeClient.events.contains("status:7"), "native callback not forwarded");
        check(bridge.events.contains("updated:1") && bridge.events.contains("status:7"), "bridge callback not forwarded");

        DiscoverySession.Change leave = session.update(idle, 0);
        check(leave.options == active, "GMS idle update stopped bridge discovery");
        int nativeBefore = nativeClient.events.size();
        physical.a(new Target(3));
        check(nativeClient.events.size() == nativeBefore && bridge.events.contains("found:3"), "idle gating broke fanout");
        check(session.stop(false).skip, "native stop shut down background RX discovery");
        physical.a(new Target(4));
        check(bridge.events.contains("found:4"), "native stop detached bridge callback");

        Client replacement = new Client();
        check(session.start(false, replacement, active, 1).options == active, "native restart changed options");
        session.replayNative();
        check(replacement.events.equals(List.of("found:1", "found:3", "found:4")), "native callback replay includes lost/missing targets");
        replacement.fail = true;
        physical.a(new Target(5));
        check(bridge.events.contains("found:5") && errors.size() == 1, "native callback failure blocked bridge");

        DiscoverySession.Change failed = session.update(idle, 0);
        failed.rollback();
        check(session.stop(true).options == active, "failed native update was not rolled back");
        wanted[0] = false;
        check(session.stop(true).options == active, "bridge stop did not restore native options");
        int bridgeBefore = bridge.events.size();
        replacement.fail = false;
        physical.d(mac);
        check(bridge.events.size() == bridgeBefore && replacement.events.contains("updated:1"), "bridge release broke native discovery");

        session.update(idle, 0);
        check(session.stop(true).options == idle, "registered native idle callback was discarded");
        check(!session.stop(false).skip, "unowned native stop intercepted");
        session.stopped();
        session.start(false, replacement, active, 1);
        int beforeReplay = replacement.events.size();
        session.replayNative();
        check(beforeReplay == replacement.events.size(), "stopped physical discovery replayed stale targets");

        check(!DiscoverySession.internal(), "internal marker leaked");
        try {
            DiscoverySession.internal(() -> {
                check(DiscoverySession.internal(), "internal operation unmarked");
                DiscoverySession.internal(() -> { check(DiscoverySession.internal(), "nested marker lost"); return null; });
                check(DiscoverySession.internal(), "nested marker not restored");
                throw new IllegalStateException("test");
            });
        } catch (IllegalStateException expected) {}
        check(!DiscoverySession.internal(), "failed operation leaked internal marker");
        System.out.println("DiscoverySession passed: callback fanout, native foreground replay, idle/stop ownership, restoration, rollback, failures");
    }
}
