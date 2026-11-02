-- Ordinary and partitioned tables (relkind r / p) of the given schemas.
-- Size statistics: estimated rows (reltuples) and total size with indexes and TOAST.
-- Also comment, owner, privileges (aclexplode) and tablespace.
-- Partitions get their parent through pg_inherits and their bound through pg_get_expr(relpartbound).
SELECT c.oid                                                    AS table_oid,
       n.nspname                                                AS schema_name,
       c.relname                                                AS table_name,
       c.relkind = 'p'                                          AS partitioned,
       CASE WHEN c.relkind = 'p' THEN pg_catalog.pg_get_partkeydef(c.oid) END AS partition_key_def,
       pn.nspname                                               AS parent_schema,
       pc.relname                                               AS parent_table,
       CASE WHEN c.relispartition THEN pg_catalog.pg_get_expr(c.relpartbound, c.oid) END AS partition_bound_expr,
       -- size statistics (planner estimate of the row count; -1 / 0 when the table was never analyzed)
       GREATEST(c.reltuples, 0)::bigint                         AS estimated_rows,
       pg_catalog.pg_total_relation_size(c.oid)                 AS total_size_bytes,
       pg_catalog.pg_size_pretty(pg_catalog.pg_total_relation_size(c.oid)) AS pretty_size,
       pg_catalog.obj_description(c.oid, 'pg_class')            AS comment,
       pg_catalog.pg_get_userbyid(c.relowner)                   AS owner,
       -- privileges of other roles than the owner (NULL relacl = default privileges of the owner only)
       ARRAY(SELECT CASE WHEN a.grantee = 0 THEN 'PUBLIC'
                           ELSE pg_catalog.quote_ident(pg_catalog.pg_get_userbyid(a.grantee)) END
                    || '=' || a.privilege_type || CASE WHEN a.is_grantable THEN '*' ELSE '' END
             FROM pg_catalog.aclexplode(COALESCE(c.relacl, pg_catalog.acldefault('r', c.relowner))) a
             WHERE a.grantee <> c.relowner
             ORDER BY 1)                                             AS acl,
       -- reltablespace 0 = default tablespace of the database
       COALESCE(ts.spcname, (SELECT dts.spcname
                             FROM pg_catalog.pg_database d
                             JOIN pg_catalog.pg_tablespace dts ON dts.oid = d.dattablespace
                             WHERE d.datname = current_database()))  AS tablespace_name
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_catalog.pg_tablespace ts ON ts.oid = c.reltablespace
LEFT JOIN pg_catalog.pg_inherits inh ON inh.inhrelid = c.oid AND c.relispartition
LEFT JOIN pg_catalog.pg_class pc ON pc.oid = inh.inhparent
LEFT JOIN pg_catalog.pg_namespace pn ON pn.oid = pc.relnamespace
WHERE c.relkind IN ('r', 'p')
  AND n.nspname = ANY (?::text[])
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
ORDER BY n.nspname, c.relname
