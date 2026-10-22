package com.tlsu.oshareairdrop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded, identity-checked submissions. A cancelled/expired preparation cannot submit later. */
final class PendingShares<T> {
    static final int LIMIT = 4;
    static final long TIMEOUT_MS = 12_000;
    static final class Entry<T> {
        final String key;
        final T value;
        final long expires;
        boolean preparing;
        Entry(String key, T value, long now) { this.key = key; this.value = value; expires = now + TIMEOUT_MS; }
    }
    private final LinkedHashMap<String, Entry<T>> entries = new LinkedHashMap<>();
    Entry<T> offer(String key, T value, long now) {
        if (entries.containsKey(key) || entries.size() >= LIMIT) return null;
        Entry<T> entry = new Entry<>(key, value, now);
        entries.put(key, entry);
        return entry;
    }
    boolean current(Entry<T> entry, long now) { return entries.get(entry.key) == entry && now < entry.expires; }
    Entry<T> remove(String key) { return entries.remove(key); }
    boolean remove(Entry<T> entry) { return entries.remove(entry.key, entry); }
    List<Entry<T>> snapshot() { return new ArrayList<>(entries.values()); }
    boolean isEmpty() { return entries.isEmpty(); }
}
