-- Columns of the tables above (attnum > 0 and not dropped), ordered by attnum.
-- {{ATTGENERATED}} is replaced by a.attgenerated on PostgreSQL 12+ and by '' on PostgreSQL 11.
SELECT a.attrelid                                               AS table_oid,
       a.attnum                                                 AS attnum,
       a.attname                                                AS column_name,
       pg_catalog.format_type(a.atttypid, a.atttypmod)          AS data_type,
       NOT a.attnotnull                                         AS nullable,
       pg_catalog.pg_get_expr(ad.adbin, ad.adrelid)             AS default_expr,
       CASE a.attidentity WHEN 'a' THEN 'ALWAYS' WHEN 'd' THEN 'BY DEFAULT' END AS identity_generation,
       ({{ATTGENERATED}})::text = 's'                           AS generated_stored,
       CASE WHEN a.attcollation <> t.typcollation THEN pg_catalog.quote_ident(co.collname) END AS collation_name,
       NOT a.attislocal                                         AS inherited
FROM pg_catalog.pg_attribute a
JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
LEFT JOIN pg_catalog.pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
LEFT JOIN pg_catalog.pg_collation co ON co.oid = a.attcollation
WHERE c.relkind IN ('r', 'p')
  AND a.attnum > 0
  AND NOT a.attisdropped
  AND n.nspname = ANY (?::text[])
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
ORDER BY a.attrelid, a.attnum
