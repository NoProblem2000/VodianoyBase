-- PRIMARY KEY, FOREIGN KEY, UNIQUE, CHECK and EXCLUDE constraints defined on the table itself.
-- Constraints a partition inherits from its partitioned parent (conparentid <> 0 / not local) are skipped:
-- PostgreSQL creates them automatically.
SELECT con.oid                                                  AS constraint_oid,
       con.conrelid                                             AS table_oid,
       con.conname                                              AS constraint_name,
       con.contype::text                                        AS constraint_type,
       pg_catalog.pg_get_constraintdef(con.oid)                 AS definition,
       con.condeferrable                                        AS deferrable,
       con.condeferred                                          AS initially_deferred,
       con.convalidated                                         AS validated,
       ARRAY(SELECT a.attname
             FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
             JOIN pg_catalog.pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum
             ORDER BY k.ord)::text[]                            AS column_names,
       fn.nspname                                               AS referenced_schema,
       fc.relname                                               AS referenced_table,
       ARRAY(SELECT a.attname
             FROM unnest(con.confkey) WITH ORDINALITY AS k(attnum, ord)
             JOIN pg_catalog.pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.attnum
             ORDER BY k.ord)::text[]                            AS referenced_columns,
       md5(concat_ws('|', con.contype::text, con.conkey::text, con.confrelid::bigint::text, con.confkey::text,
                     con.confupdtype::text, con.confdeltype::text, con.confmatchtype::text,
                     con.condeferrable::text, con.condeferred::text, con.convalidated::text,
                     con.conexclop::text, con.conbin::text, con.connoinherit::text)) AS signature
FROM pg_catalog.pg_constraint con
JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_catalog.pg_class fc ON fc.oid = con.confrelid
LEFT JOIN pg_catalog.pg_namespace fn ON fn.oid = fc.relnamespace
WHERE con.contype IN ('p', 'f', 'u', 'c', 'x')
  AND con.conislocal
  AND con.conparentid = 0
  AND c.relkind IN ('r', 'p')
  AND n.nspname = ANY (?::text[])
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
ORDER BY con.conrelid, con.conname
