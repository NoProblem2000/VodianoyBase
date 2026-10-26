-- Indexes (relkind i, and I for partitioned indexes) of tables and materialized views.
-- Excluded, because the DDL would duplicate them:
--   * indexes that implement a PRIMARY KEY / UNIQUE / EXCLUDE constraint (pg_constraint.conindid);
--   * partition indexes attached to an index of the partitioned parent (pg_inherits) - created automatically.
SELECT ic.oid                                                   AS index_oid,
       x.indrelid                                               AS table_oid,
       ic.relname                                               AS index_name,
       pg_catalog.pg_get_indexdef(ic.oid)                       AS definition,
       x.indisunique                                            AS is_unique,
       am.amname                                                AS method,
       pg_catalog.pg_get_expr(x.indpred, x.indrelid)            AS predicate,
       md5(concat_ws('|', am.amname, x.indisunique::text, x.indkey::text, x.indclass::text,
                     x.indcollation::text, x.indoption::text, x.indexprs::text, x.indpred::text)) AS signature
FROM pg_catalog.pg_index x
JOIN pg_catalog.pg_class ic ON ic.oid = x.indexrelid
JOIN pg_catalog.pg_class tc ON tc.oid = x.indrelid
JOIN pg_catalog.pg_namespace n ON n.oid = tc.relnamespace
JOIN pg_catalog.pg_am am ON am.oid = ic.relam
WHERE ic.relkind IN ('i', 'I')
  AND tc.relkind IN ('r', 'p', 'm')
  AND n.nspname = ANY (?::text[])
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = tc.oid AND d.deptype = 'e')
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint co
                  WHERE co.conindid = ic.oid AND co.conrelid = tc.oid AND co.contype IN ('p', 'u', 'x'))
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits ih WHERE ih.inhrelid = ic.oid)
ORDER BY x.indrelid, ic.relname
