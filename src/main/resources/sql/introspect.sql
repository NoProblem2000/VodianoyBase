-- PgGitVisualizer: full schema introspection via pg_catalog (PostgreSQL 11+).
-- information_schema is intentionally NOT used (it does not expose partitions and OIDs).
-- Returns a single text column with the DatabaseSnapshot JSON document.
WITH
excluded_ns AS (
    SELECT n.oid
    FROM pg_catalog.pg_namespace n
    WHERE n.nspname IN ('pg_catalog', 'information_schema', '_pgvc')
       OR n.nspname LIKE 'pg\_toast%'
       OR n.nspname LIKE 'pg\_temp\_%'
       OR EXISTS (SELECT 1
                  FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_namespace'::regclass
                    AND d.objid = n.oid
                    AND d.deptype = 'e')
),
extension_rel AS (
    SELECT d.objid AS oid
    FROM pg_catalog.pg_depend d
    WHERE d.classid = 'pg_catalog.pg_class'::regclass
      AND d.deptype = 'e'
),
rels AS (
    SELECT c.*
    FROM pg_catalog.pg_class c
    WHERE c.relnamespace NOT IN (SELECT oid FROM excluded_ns)
      AND c.oid NOT IN (SELECT oid FROM extension_rel)
),
schemas AS (
    SELECT coalesce(jsonb_agg(n.nspname ORDER BY n.nspname), '[]'::jsonb) AS j
    FROM pg_catalog.pg_namespace n
    WHERE n.oid NOT IN (SELECT oid FROM excluded_ns)
),
tables AS (
    SELECT coalesce(jsonb_agg(t.j ORDER BY t.schema_name, t.table_name), '[]'::jsonb) AS j
    FROM (
        SELECT n.nspname AS schema_name,
               c.relname AS table_name,
               jsonb_build_object(
                   'oid', c.oid::bigint,
                   'schema', n.nspname,
                   'name', c.relname,
                   'partitioned', c.relkind = 'p',
                   'partitionKey', CASE WHEN c.relkind = 'p' THEN pg_catalog.pg_get_partkeydef(c.oid) END,
                   'parentSchema', pn.nspname,
                   'parentTable', pc.relname,
                   'partitionBound', CASE WHEN c.relispartition THEN pg_catalog.pg_get_expr(c.relpartbound, c.oid) END,
                   'columns', (
                       SELECT coalesce(jsonb_agg(jsonb_build_object(
                                  'attnum', a.attnum,
                                  'name', a.attname,
                                  'type', pg_catalog.format_type(a.atttypid, a.atttypmod),
                                  'notNull', a.attnotnull,
                                  'defaultExpr', pg_catalog.pg_get_expr(ad.adbin, ad.adrelid),
                                  'identity', nullif(a.attidentity::text, ''),
                                  'local', a.attislocal
                              ) ORDER BY a.attnum), '[]'::jsonb)
                       FROM pg_catalog.pg_attribute a
                       LEFT JOIN pg_catalog.pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
                       WHERE a.attrelid = c.oid
                         AND a.attnum > 0
                         AND NOT a.attisdropped
                   ),
                   'constraints', (
                       SELECT coalesce(jsonb_agg(jsonb_build_object(
                                  'oid', co.oid::bigint,
                                  'name', co.conname,
                                  'type', co.contype::text,
                                  'definition', pg_catalog.pg_get_constraintdef(co.oid),
                                  'signature', md5(concat_ws('|',
                                      co.contype::text, co.conkey::text, co.confrelid::bigint::text, co.confkey::text,
                                      co.confupdtype::text, co.confdeltype::text, co.confmatchtype::text,
                                      co.condeferrable::text, co.condeferred::text, co.convalidated::text,
                                      co.conexclop::text, co.conbin::text, co.connoinherit::text))
                              ) ORDER BY co.conname), '[]'::jsonb)
                       FROM pg_catalog.pg_constraint co
                       WHERE co.conrelid = c.oid
                         AND co.contype IN ('p', 'f', 'u', 'c', 'x')
                         AND co.conislocal
                         AND co.conparentid = 0
                   )
               ) AS j
        FROM rels c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        LEFT JOIN pg_catalog.pg_inherits inh ON inh.inhrelid = c.oid AND c.relispartition
        LEFT JOIN pg_catalog.pg_class pc ON pc.oid = inh.inhparent
        LEFT JOIN pg_catalog.pg_namespace pn ON pn.oid = pc.relnamespace
        WHERE c.relkind IN ('r', 'p')
    ) t
),
indexes AS (
    SELECT coalesce(jsonb_agg(i.j ORDER BY i.schema_name, i.index_name), '[]'::jsonb) AS j
    FROM (
        SELECT n.nspname AS schema_name,
               ic.relname AS index_name,
               jsonb_build_object(
                   'oid', ic.oid::bigint,
                   'schema', n.nspname,
                   'name', ic.relname,
                   'tableOid', tc.oid::bigint,
                   'tableSchema', tn.nspname,
                   'tableName', tc.relname,
                   'unique', x.indisunique,
                   'definition', pg_catalog.pg_get_indexdef(ic.oid),
                   'signature', md5(concat_ws('|',
                       am.amname, x.indisunique::text, x.indkey::text, x.indclass::text, x.indcollation::text,
                       x.indoption::text, x.indexprs::text, x.indpred::text))
               ) AS j
        FROM pg_catalog.pg_index x
        JOIN rels ic ON ic.oid = x.indexrelid
        JOIN pg_catalog.pg_namespace n ON n.oid = ic.relnamespace
        JOIN pg_catalog.pg_class tc ON tc.oid = x.indrelid
        JOIN pg_catalog.pg_namespace tn ON tn.oid = tc.relnamespace
        JOIN pg_catalog.pg_am am ON am.oid = ic.relam
        WHERE ic.relkind IN ('i', 'I')
          AND tc.relkind IN ('r', 'p', 'm')
          AND NOT EXISTS (SELECT 1
                          FROM pg_catalog.pg_constraint co
                          WHERE co.conindid = ic.oid
                            AND co.conrelid = tc.oid
                            AND co.contype IN ('p', 'u', 'x'))
          AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits ih WHERE ih.inhrelid = ic.oid)
    ) i
),
sequences AS (
    SELECT coalesce(jsonb_agg(s.j ORDER BY s.schema_name, s.seq_name), '[]'::jsonb) AS j
    FROM (
        SELECT n.nspname AS schema_name,
               c.relname AS seq_name,
               jsonb_build_object(
                   'oid', c.oid::bigint,
                   'schema', n.nspname,
                   'name', c.relname,
                   'dataType', pg_catalog.format_type(sq.seqtypid, NULL),
                   'start', sq.seqstart,
                   'increment', sq.seqincrement,
                   'min', sq.seqmin,
                   'max', sq.seqmax,
                   'cache', sq.seqcache,
                   'cycle', sq.seqcycle,
                   'ownedBy', (
                       SELECT jsonb_build_object('schema', otn.nspname, 'table', otc.relname, 'column', oa.attname)
                       FROM pg_catalog.pg_depend d
                       JOIN pg_catalog.pg_class otc ON otc.oid = d.refobjid
                       JOIN pg_catalog.pg_namespace otn ON otn.oid = otc.relnamespace
                       JOIN pg_catalog.pg_attribute oa ON oa.attrelid = d.refobjid AND oa.attnum = d.refobjsubid
                       WHERE d.classid = 'pg_catalog.pg_class'::regclass
                         AND d.objid = c.oid
                         AND d.refclassid = 'pg_catalog.pg_class'::regclass
                         AND d.deptype = 'a'
                       LIMIT 1
                   )
               ) AS j
        FROM pg_catalog.pg_sequence sq
        JOIN rels c ON c.oid = sq.seqrelid
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind = 'S'
          AND NOT EXISTS (SELECT 1
                          FROM pg_catalog.pg_depend d
                          WHERE d.classid = 'pg_catalog.pg_class'::regclass
                            AND d.objid = c.oid
                            AND d.deptype = 'i')
    ) s
),
views AS (
    SELECT coalesce(jsonb_agg(v.j ORDER BY v.schema_name, v.view_name), '[]'::jsonb) AS j
    FROM (
        SELECT n.nspname AS schema_name,
               c.relname AS view_name,
               jsonb_build_object(
                   'oid', c.oid::bigint,
                   'schema', n.nspname,
                   'name', c.relname,
                   'materialized', c.relkind = 'm',
                   'definition', pg_catalog.pg_get_viewdef(c.oid, true),
                   'signature', (SELECT md5(string_agg(r.ev_action::text, '|' ORDER BY r.rulename))
                                 FROM pg_catalog.pg_rewrite r
                                 WHERE r.ev_class = c.oid),
                   'dependencies', (
                       SELECT coalesce(jsonb_agg(DISTINCT jsonb_build_object(
                                  'schema', dn.nspname,
                                  'name', dc.relname,
                                  'column', da.attname
                              )), '[]'::jsonb)
                       FROM pg_catalog.pg_rewrite r
                       JOIN pg_catalog.pg_depend d
                         ON d.classid = 'pg_catalog.pg_rewrite'::regclass
                        AND d.objid = r.oid
                        AND d.refclassid = 'pg_catalog.pg_class'::regclass
                        AND d.refobjid <> c.oid
                       JOIN pg_catalog.pg_class dc ON dc.oid = d.refobjid
                       JOIN pg_catalog.pg_namespace dn ON dn.oid = dc.relnamespace
                       LEFT JOIN pg_catalog.pg_attribute da
                         ON da.attrelid = d.refobjid
                        AND da.attnum = d.refobjsubid
                        AND d.refobjsubid > 0
                       WHERE r.ev_class = c.oid
                   )
               ) AS j
        FROM rels c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind IN ('v', 'm')
    ) v
)
SELECT jsonb_build_object(
           'formatVersion', 1,
           'sourceDatabaseId',
               CASE WHEN pg_catalog.has_function_privilege('pg_catalog.pg_control_system()', 'EXECUTE')
                    THEN (SELECT s.system_identifier::text FROM pg_catalog.pg_control_system() s)
                    ELSE 'unknown'
               END
               || ':' || (SELECT d.oid::text FROM pg_catalog.pg_database d WHERE d.datname = current_database()),
           'serverVersion', current_setting('server_version_num')::int,
           'schemas', (SELECT j FROM schemas),
           'tables', (SELECT j FROM tables),
           'indexes', (SELECT j FROM indexes),
           'sequences', (SELECT j FROM sequences),
           'views', (SELECT j FROM views)
       )::text AS snapshot;
