package com.tlsu.oshareairdrop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Native candidates keep their order; a live bridge peer gets at least one of three slots. */
final class ChooserTargets {
    static final int LIMIT = 3;
    private ChooserTargets() {}

    static <T> List<T> merge(List<T> nativeTargets, Map<String, T> owned, Function<T, String> key) {
        LinkedHashMap<String, T> nativePeers = new LinkedHashMap<>();
        for (T target : nativeTargets) {
            if (target == null) continue;
            String id = key.apply(target);
            // Replace old bridge objects with the current snapshot, including removals.
            if (!id.startsWith("airdrop:")) nativePeers.putIfAbsent(id, target);
        }
        ArrayList<T> result = new ArrayList<>();
        int nativeLimit = owned.isEmpty() ? LIMIT : LIMIT - 1;
        for (T target : nativePeers.values()) {
            if (result.size() == nativeLimit) break;
            result.add(target);
        }
        owned.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (result.size() < LIMIT && entry.getValue() != null
                    && entry.getKey().startsWith("airdrop:")
                    && entry.getKey().equals(key.apply(entry.getValue()))) result.add(entry.getValue());
        });
        return result;
    }
}
