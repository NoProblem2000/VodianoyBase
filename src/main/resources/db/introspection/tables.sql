-- Ordinary and partitioned tables (relkind r / p) of the given schemas.
-- Partitions get their parent through pg_inherits and their bound through pg_get_expr(relpartbound).
SELECT c.oid                                                    AS table_oid,
       n.nspname                                                AS schema_name,
       c.relname                                                AS table_name,
       c.relkind = 'p'                                          AS partitioned,
       CASE WHEN c.relkind = 'p' THEN pg_catalog.pg_get_partkeydef(c.oid) END AS partition_key_def,
       pn.nspname                                               AS parent_schema,
       pc.relname                                               AS parent_table,
       CASE WHEN c.relispartition THEN pg_catalog.pg_get_expr(c.relpartbound, c.oid) END AS partition_bound_expr
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_catalog.pg_inherits inh ON inh.inhrelid = c.oid AND c.relispartition
LEFT JOIN pg_catalog.pg_class pc ON pc.oid = inh.inhparent
LEFT JOIN pg_catalog.pg_namespace pn ON pn.oid = pc.relnamespace
WHERE c.relkind IN ('r', 'p')
  AND n.nspname = ANY (?::text[])
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
ORDER BY n.nspname, c.relname
