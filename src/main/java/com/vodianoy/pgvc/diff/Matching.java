package com.vodianoy.pgvc.diff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Pairs objects of the old and the new snapshot. When identities are comparable (both snapshots come from the same
 * database) objects are first paired by identity (oid / attnum) - that is how a RENAME is recognised - and the
 * remaining ones by name.
 */
final class Matching<T> {

    record Pair<T>(T from, T to) {
    }

    final List<Pair<T>> matched = new ArrayList<>();
    final List<T> removed = new ArrayList<>();
    final List<T> added = new ArrayList<>();

    static <T> Matching<T> of(Collection<T> fromObjects, Collection<T> toObjects, Function<T, Long> identity,
                              Function<T, String> name, boolean useIdentity) {
        Matching<T> m = new Matching<>();
        Set<T> usedTo = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<T, T> pairs = new IdentityHashMap<>();

        if (useIdentity) {
            Map<Long, T> toById = new HashMap<>();
            for (T t : toObjects) {
                long id = identity.apply(t);
                if (id > 0) {
                    toById.put(id, t);
                }
            }
            for (T f : fromObjects) {
                long id = identity.apply(f);
                T t = id > 0 ? toById.get(id) : null;
                if (t != null && usedTo.add(t)) {
                    pairs.put(f, t);
                }
            }
        }
        Map<String, T> toByName = new HashMap<>();
        for (T t : toObjects) {
            if (!usedTo.contains(t)) {
                toByName.put(name.apply(t), t);
            }
        }
        for (T f : fromObjects) {
            if (pairs.containsKey(f)) {
                continue;
            }
            T t = toByName.get(name.apply(f));
            if (t != null && usedTo.add(t)) {
                pairs.put(f, t);
            }
        }
        for (T f : fromObjects) {
            T t = pairs.get(f);
            if (t == null) {
                m.removed.add(f);
            } else {
                m.matched.add(new Pair<>(f, t));
            }
        }
        for (T t : toObjects) {
            if (!usedTo.contains(t)) {
                m.added.add(t);
            }
        }
        return m;
    }
}
