-- Relations (and columns) every view / materialized view reads, from pg_depend of its rewrite rule.
-- Needed to order DDL safely: a view must be dropped before a column it uses changes type.
SELECT DISTINCT
       r.ev_class                                               AS view_oid,
       dn.nspname                                               AS referenced_schema,
       dc.relname                                               AS referenced_name,
       dc.relkind::text                                         AS referenced_kind,
       da.attname                                               AS referenced_column
FROM pg_catalog.pg_rewrite r
JOIN pg_catalog.pg_class c ON c.oid = r.ev_class
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
JOIN pg_catalog.pg_depend d ON d.classid = 'pg_catalog.pg_rewrite'::regclass
                           AND d.objid = r.oid
                           AND d.refclassid = 'pg_catalog.pg_class'::regclass
                           AND d.refobjid <> r.ev_class
JOIN pg_catalog.pg_class dc ON dc.oid = d.refobjid
JOIN pg_catalog.pg_namespace dn ON dn.oid = dc.relnamespace
LEFT JOIN pg_catalog.pg_attribute da ON da.attrelid = d.refobjid
                                    AND da.attnum = d.refobjsubid
                                    AND d.refobjsubid > 0
WHERE c.relkind IN ('v', 'm')
  AND n.nspname = ANY (?::text[])
ORDER BY 1, 2, 3, 5
