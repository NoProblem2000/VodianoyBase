package com.vodianoy.pgvc.diff;

import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.EnumTypeModel;
import com.vodianoy.pgvc.model.schema.ExtensionModel;
import com.vodianoy.pgvc.model.schema.FunctionModel;
import com.vodianoy.pgvc.model.schema.IndexModel;
import com.vodianoy.pgvc.model.schema.SequenceModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import com.vodianoy.pgvc.model.schema.TriggerModel;
import com.vodianoy.pgvc.model.schema.ViewModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies {@link DiffIgnoreRules} to a snapshot: returns a filtered copy (the original is never modified).
 *
 * <p>Snapshots of format version 1 (taken before v1.6.0) contain no functions, triggers, ENUM types, extensions,
 * comments, owners, grants or tablespaces. When such a snapshot takes part in a comparison, these categories are
 * removed from both sides ({@code legacy = true}), so an upgrade does not report every function as new; the next
 * commit records them.
 */
public final class SnapshotFilter {

    private SnapshotFilter() {
    }

    /** The two snapshots cannot be compared for the categories of format version 2. */
    public static boolean legacy(DatabaseSnapshot a, DatabaseSnapshot b) {
        return (a != null && !a.hasExtendedObjects()) || (b != null && !b.hasExtendedObjects());
    }

    public static DatabaseSnapshot apply(DatabaseSnapshot s, DiffIgnoreRules rules) {
        return apply(s, rules, false);
    }

    public static DatabaseSnapshot apply(DatabaseSnapshot s, DiffIgnoreRules rules, boolean legacy) {
        DiffIgnoreRules r = rules == null ? DiffIgnoreRules.NONE : rules;
        if (s == null || (r.isEmpty() && !legacy)) {
            return s;
        }
        boolean noComments = legacy || r.ignoreComments();
        boolean noAccess = legacy || r.ignoreOwnersAndGrants();
        boolean noTablespace = legacy || r.ignoreTablespaces();

        DatabaseSnapshot out = s.toBuilder()
                .schemas(new ArrayList<>(s.getSchemas()))
                .tables(new LinkedHashMap<>())
                .views(new LinkedHashMap<>())
                .sequences(new LinkedHashMap<>())
                .functions(new LinkedHashMap<>())
                .enumTypes(new LinkedHashMap<>())
                .extensions(new LinkedHashMap<>())
                .build();

        // tables: excluded by pattern, partitions when ignored, and everything below an excluded table
        for (TableModel t : s.getTables().values()) {
            if (r.excludes(t.getSchemaName(), t.getName()) || (r.ignorePartitions() && t.isPartition())) {
                continue;
            }
            out.getTables().put(t.getQualifiedName(), copy(t, r, noComments, noAccess, noTablespace, legacy));
        }
        boolean removed = true;
        while (removed) {
            removed = out.getTables().values().removeIf(t -> t.isPartition()
                    && s.getTables().containsKey(t.getParentTable()) && !out.getTables().containsKey(t.getParentTable()));
        }

        for (ViewModel v : s.getViews().values()) {
            if (r.excludes(v.getSchemaName(), v.getName())) {
                continue;
            }
            ViewModel c = v.toBuilder().indexes(indexes(v.getIndexes(), v.getSchemaName(), r)).build();
            if (noComments) {
                c.setComment(null);
            }
            if (noAccess) {
                c.setOwner(null);
                c.setAcl(null);
            }
            out.getViews().put(c.getQualifiedName(), c);
        }

        for (SequenceModel q : s.getSequences().values()) {
            boolean ownerExcluded = q.getOwnedByTable() != null && s.getTables().containsKey(q.getOwnedByTable())
                    && !out.getTables().containsKey(q.getOwnedByTable());
            if (r.excludes(q.getSchemaName(), q.getName()) || ownerExcluded) {
                continue;
            }
            SequenceModel c = q.toBuilder().build();
            if (noAccess) {
                c.setOwner(null);
                c.setAcl(null);
            }
            out.getSequences().put(c.getQualifiedName(), c);
        }

        if (legacy) {
            return out;
        }
        for (FunctionModel f : s.getFunctions().values()) {
            if (r.excludes(f.getSchemaName(), f.getName())) {
                continue;
            }
            FunctionModel c = f.toBuilder().build();
            if (noComments) {
                c.setComment(null);
            }
            if (noAccess) {
                c.setOwner(null);
                c.setAcl(null);
            }
            out.getFunctions().put(c.getKey(), c);
        }
        for (EnumTypeModel e : s.getEnumTypes().values()) {
            if (r.excludes(e.getSchemaName(), e.getName())) {
                continue;
            }
            EnumTypeModel c = e.toBuilder().values(new ArrayList<>(e.getValues())).build();
            if (noComments) {
                c.setComment(null);
            }
            if (noAccess) {
                c.setOwner(null);
            }
            out.getEnumTypes().put(c.getQualifiedName(), c);
        }
        for (ExtensionModel e : s.getExtensions().values()) {
            if (!r.excludes(null, e.getName())) {
                out.getExtensions().put(e.getName(), e.toBuilder().build());
            }
        }
        return out;
    }

    /**
     * Only the objects of the given schemas (a shallow copy; extensions are database-wide and stay). Used for the HEAD
     * snapshot of an environment that tracks a subset of its schemas: a commit taken while it tracked more schemas
     * must not make the schemas no longer tracked look dropped. {@code schemas} null = no restriction.
     */
    public static DatabaseSnapshot onlySchemas(DatabaseSnapshot s, java.util.Collection<String> schemas) {
        if (s == null || schemas == null) {
            return s;
        }
        java.util.Set<String> keep = new java.util.HashSet<>(schemas);
        DatabaseSnapshot out = s.toBuilder()
                .schemas(new java.util.ArrayList<>(s.getSchemas().stream().filter(keep::contains).toList()))
                .tables(new LinkedHashMap<>())
                .views(new LinkedHashMap<>())
                .sequences(new LinkedHashMap<>())
                .functions(new LinkedHashMap<>())
                .enumTypes(new LinkedHashMap<>())
                .extensions(new LinkedHashMap<>(s.getExtensions()))
                .build();
        s.getTables().forEach((k, v) -> {
            if (keep.contains(v.getSchemaName())) {
                out.getTables().put(k, v);
            }
        });
        s.getViews().forEach((k, v) -> {
            if (keep.contains(v.getSchemaName())) {
                out.getViews().put(k, v);
            }
        });
        s.getSequences().forEach((k, v) -> {
            if (keep.contains(v.getSchemaName())) {
                out.getSequences().put(k, v);
            }
        });
        s.getFunctions().forEach((k, v) -> {
            if (keep.contains(v.getSchemaName())) {
                out.getFunctions().put(k, v);
            }
        });
        s.getEnumTypes().forEach((k, v) -> {
            if (keep.contains(v.getSchemaName())) {
                out.getEnumTypes().put(k, v);
            }
        });
        return out;
    }

    /**
     * {@code live} without the rows of tables that {@code reference} has but does not track as reference data. Used
     * by the checks "the database now equals that commit" (after a rollback / push, when linking): a dictionary that
     * started being tracked after the commit must not make the check fail.
     */
    public static DatabaseSnapshot withoutDataUntrackedIn(DatabaseSnapshot reference, DatabaseSnapshot live) {
        if (reference == null || live == null) {
            return live;
        }
        boolean any = live.getTables().entrySet().stream().anyMatch(e -> e.getValue().getReferenceData() != null
                && reference.getTables().containsKey(e.getKey())
                && reference.getTables().get(e.getKey()).getReferenceData() == null);
        if (!any) {
            return live;
        }
        DatabaseSnapshot out = live.toBuilder().tables(new LinkedHashMap<>()).build();
        live.getTables().forEach((k, t) -> {
            TableModel ref = reference.getTables().get(k);
            boolean drop = t.getReferenceData() != null && ref != null && ref.getReferenceData() == null;
            out.getTables().put(k, drop ? t.toBuilder().referenceData(null).build() : t);
        });
        return out;
    }

    private static TableModel copy(TableModel t, DiffIgnoreRules r, boolean noComments, boolean noAccess,
                                   boolean noTablespace, boolean legacy) {
        Map<String, ColumnModel> columns = new LinkedHashMap<>();
        for (ColumnModel col : t.getColumns().values()) {
            ColumnModel c = col.toBuilder().build();
            if (noComments) {
                c.setComment(null);
            }
            columns.put(c.getName(), c);
        }
        Map<String, TriggerModel> triggers = new LinkedHashMap<>();
        if (!legacy) {
            for (TriggerModel tr : t.getTriggers().values()) {
                if (!r.excludes(t.getSchemaName(), tr.getName())) {
                    triggers.put(tr.getName(), tr.toBuilder().build());
                }
            }
        }
        TableModel c = t.toBuilder()
                .columns(columns)
                .constraints(new LinkedHashMap<>(t.getConstraints()))
                .indexes(indexes(t.getIndexes(), t.getSchemaName(), r))
                .triggers(triggers)
                .build();
        if (noComments) {
            c.setComment(null);
        }
        if (noAccess) {
            c.setOwner(null);
            c.setAcl(null);
        }
        if (noTablespace) {
            c.setTablespace(null);
        }
        return c;
    }

    private static Map<String, IndexModel> indexes(Map<String, IndexModel> source, String schema, DiffIgnoreRules r) {
        Map<String, IndexModel> out = new LinkedHashMap<>();
        for (IndexModel i : source.values()) {
            if (!r.excludes(schema, i.getName())) {
                out.put(i.getName(), i);
            }
        }
        return out;
    }
}
