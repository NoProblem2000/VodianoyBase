-- Sequences (pg_sequence + relkind S). Identity-column sequences (pg_depend deptype i) are part of the column
-- definition and are skipped; serial sequences report the owning column (deptype a).
SELECT c.oid                                                    AS sequence_oid,
       n.nspname                                                AS schema_name,
       c.relname                                                AS sequence_name,
       pg_catalog.format_type(s.seqtypid, NULL)                 AS data_type,
       s.seqstart                                               AS start_value,
       s.seqincrement                                           AS increment_by,
       s.seqmin                                                 AS min_value,
       s.seqmax                                                 AS max_value,
       s.seqcache                                               AS cache_size,
       s.seqcycle                                               AS cycle,
       own_n.nspname                                            AS owned_by_schema,
       own_c.relname                                            AS owned_by_table,
       own_a.attname                                            AS owned_by_column
FROM pg_catalog.pg_sequence s
JOIN pg_catalog.pg_class c ON c.oid = s.seqrelid
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_catalog.pg_depend own ON own.classid = 'pg_catalog.pg_class'::regclass
                                  AND own.objid = c.oid
                                  AND own.refclassid = 'pg_catalog.pg_class'::regclass
                                  AND own.refobjsubid > 0
                                  AND own.deptype = 'a'
LEFT JOIN pg_catalog.pg_class own_c ON own_c.oid = own.refobjid
LEFT JOIN pg_catalog.pg_namespace own_n ON own_n.oid = own_c.relnamespace
LEFT JOIN pg_catalog.pg_attribute own_a ON own_a.attrelid = own.refobjid AND own_a.attnum = own.refobjsubid
WHERE c.relkind = 'S'
  AND n.nspname = ANY (?::text[])
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype IN ('e', 'i'))
ORDER BY n.nspname, c.relname
