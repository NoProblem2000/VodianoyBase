-- User schemas that can be version controlled: everything except system schemas, temporary / TOAST schemas,
-- the version-control schema _pgvc and schemas created by extensions.
SELECT n.nspname AS schema_name
FROM pg_catalog.pg_namespace n
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', '_pgvc')
  AND n.nspname NOT LIKE 'pg\_toast%'
  AND n.nspname NOT LIKE 'pg\_temp\_%'
  AND NOT EXISTS (SELECT 1
                  FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_namespace'::regclass
                    AND d.objid = n.oid
                    AND d.deptype = 'e')
ORDER BY n.nspname
