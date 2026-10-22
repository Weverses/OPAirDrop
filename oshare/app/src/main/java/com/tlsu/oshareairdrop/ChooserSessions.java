package com.tlsu.oshareairdrop;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Tracks registration identity independently of Activity and provider lifetimes. */
final class ChooserSessions<T> {
    private record Key(int uid, Object tag) {}
    private final Map<Key, T> clients = new HashMap<>();

    synchronized T put(int uid, Object tag, T client) { return clients.put(new Key(uid, tag), client); }
    synchronized T remove(int uid, Object tag) { return clients.remove(new Key(uid, tag)); }
    synchronized void died(T client) { clients.values().removeIf(value -> Objects.equals(value, client)); }
    synchronized boolean contains(T client) { return clients.containsValue(client); }
    synchronized boolean isEmpty() { return clients.isEmpty(); }
}
