package com.vodianoy.pgvc.core.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Pairs objects of two snapshots. When OIDs are comparable (same source database) objects are first matched
 * by OID - this is how RENAME is detected - and the rest by name.
 */
final class Matching<T> {

    record Pair<T>(T from, T to) {
    }

    final List<Pair<T>> matched = new ArrayList<>();
    final List<T> removed = new ArrayList<>();
    final List<T> added = new ArrayList<>();

    static <T> Matching<T> of(List<T> fromList, List<T> toList, Function<T, Long> oid, Function<T, String> key,
                              boolean useOid) {
        Matching<T> m = new Matching<>();
        Set<T> usedTo = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<T, T> pairs = new IdentityHashMap<>();

        if (useOid) {
            Map<Long, T> toByOid = new HashMap<>();
            for (T t : toList) {
                long o = oid.apply(t);
                if (o > 0) {
                    toByOid.put(o, t);
                }
            }
            for (T f : fromList) {
                long o = oid.apply(f);
                T t = o > 0 ? toByOid.get(o) : null;
                if (t != null && usedTo.add(t)) {
                    pairs.put(f, t);
                }
            }
        }
        Map<String, T> toByKey = new HashMap<>();
        for (T t : toList) {
            if (!usedTo.contains(t)) {
                toByKey.put(key.apply(t), t);
            }
        }
        for (T f : fromList) {
            if (pairs.containsKey(f)) {
                continue;
            }
            T t = toByKey.get(key.apply(f));
            if (t != null && usedTo.add(t)) {
                pairs.put(f, t);
            }
        }
        for (T f : fromList) {
            T t = pairs.get(f);
            if (t == null) {
                m.removed.add(f);
            } else {
                m.matched.add(new Pair<>(f, t));
            }
        }
        for (T t : toList) {
            if (!usedTo.contains(t)) {
                m.added.add(t);
            }
        }
        return m;
    }
}
