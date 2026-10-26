-- Views (relkind v) and materialized views (relkind m) with their definition.
-- signature hashes the stored rewrite rule, which does not change when referenced objects are renamed.
SELECT c.oid                                                    AS view_oid,
       n.nspname                                                AS schema_name,
       c.relname                                                AS view_name,
       c.relkind = 'm'                                          AS materialized,
       pg_catalog.pg_get_viewdef(c.oid, true)                   AS definition,
       c.relispopulated                                         AS populated,
       (SELECT md5(string_agg(r.ev_action::text, '|' ORDER BY r.rulename))
        FROM pg_catalog.pg_rewrite r
        WHERE r.ev_class = c.oid)                               AS signature
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('v', 'm')
  AND n.nspname = ANY (?::text[])
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
ORDER BY n.nspname, c.relname
