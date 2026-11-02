-- User triggers of the tables above (NOT tgisinternal: no foreign-key triggers).
-- {{TRIGGER_CLONES}} is replaced by "AND t.tgparentid = 0" on PostgreSQL 13+ (triggers cloned from a partitioned
-- table to its partitions); on PostgreSQL 11 / 12 such clones are internal already.
SELECT t.oid                                                    AS trigger_oid,
       t.tgrelid                                                AS table_oid,
       t.tgname                                                 AS trigger_name,
       pg_catalog.pg_get_triggerdef(t.oid, true)                AS definition,
       t.tgenabled::text                                        AS enabled,
       fn.nspname                                               AS function_schema,
       f.proname                                                AS function_name,
       pg_catalog.pg_get_function_identity_arguments(f.oid)     AS function_arguments
FROM pg_catalog.pg_trigger t
JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
JOIN pg_catalog.pg_proc f ON f.oid = t.tgfoid
JOIN pg_catalog.pg_namespace fn ON fn.oid = f.pronamespace
WHERE NOT t.tgisinternal
  {{TRIGGER_CLONES}}
  AND c.relkind IN ('r', 'p')
  AND n.nspname = ANY (?::text[])
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
ORDER BY t.tgrelid, t.tgname
