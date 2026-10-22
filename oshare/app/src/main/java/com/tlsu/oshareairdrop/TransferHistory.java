package com.tlsu.oshareairdrop;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/** Observation only: confirmation waits keep radio ownership but do not start history. */
final class TransferHistory {
    private record Key(boolean incoming, long id) {}
    private static final class Task {
        final long source;
        boolean active, terminal;
        Task(long source) { this.source = source; }
    }
    final class Source {
        final long generation;
        final boolean incoming;
        boolean finished;
        long highestFinishedId = Long.MIN_VALUE;
        Object callback;
        Source(long generation, boolean incoming) { this.generation = generation; this.incoming = incoming; }
        void begin(long id) { update(this, id, 1001, false); }
        void finish(long id) { update(this, id, 1009, true); }
    }

    private long generation;
    private final LinkedHashMap<Key, Task> tasks = new LinkedHashMap<>();

    synchronized Source source(boolean incoming) { return new Source(++generation, incoming); }

    synchronized void update(Source source, long id, int status, boolean terminal) {
        if (source.finished) return;
        Key key = new Key(source.incoming, id);
        Task task = tasks.get(key);
        if (task == null && source.incoming && id <= source.highestFinishedId) return;
        if (task != null && (task.source > source.generation ||
                (task.source == source.generation && task.terminal))) return;
        if (task == null || task.source != source.generation) {
            task = new Task(source.generation);
            tasks.put(key, task);
        }
        task.terminal = OutgoingTransfers.isTerminal(status, terminal) || status == 1029;
        if (task.terminal) {
            if (source.incoming) source.highestFinishedId = Math.max(source.highestFinishedId, id);
            else source.finished = true;
        }
        if (!task.terminal && (status == 1005 || status == 1017)) task.active = true;
        // A late WAITING update must not reset an accepted/started transfer.
        if (tasks.size() > 128) {
            var iterator = tasks.entrySet().iterator();
            while (tasks.size() > 128 && iterator.hasNext()) {
                if (iterator.next().getValue().terminal) iterator.remove();
            }
        }
    }

    synchronized void accepted(long id) {
        Task task = tasks.get(new Key(true, id));
        if (task != null && !task.terminal) task.active = true;
    }

    synchronized String state() {
        boolean waiting = false;
        for (Task task : tasks.values()) {
            if (task.terminal) continue;
            if (task.active) return "active";
            waiting = true;
        }
        return waiting ? "waiting" : "idle";
    }

    static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    Source wrap(Class<?> iface, Object original, boolean incoming, Consumer<Throwable> onError) {
        Source source = source(incoming);
        source.callback = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> args != null && proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "TransferHistoryCallback(" + iface.getName() + ")";
                        };
                    }
                    if (method.getDeclaringClass() != Object.class && args != null && args.length >= 2) {
                        try {
                            update(source, ((Number) field(args[0], "a")).longValue(),
                                    ((Number) field(args[1], "a")).intValue(),
                                    Boolean.TRUE.equals(field(args[1], "e")));
                        } catch (ReflectiveOperationException | RuntimeException failure) { onError.accept(failure); }
                    }
                    try { method.setAccessible(true); return method.invoke(original, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        return source;
    }
}
