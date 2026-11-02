-- ENUM types (typtype e) of the given schemas with their labels in sort order (pg_enum.enumsortorder).
SELECT t.oid                                                    AS type_oid,
       n.nspname                                                AS schema_name,
       t.typname                                                AS type_name,
       ARRAY(SELECT e.enumlabel::text
             FROM pg_catalog.pg_enum e
             WHERE e.enumtypid = t.oid
             ORDER BY e.enumsortorder)                          AS enum_values,
       pg_catalog.pg_get_userbyid(t.typowner)                   AS owner,
       pg_catalog.obj_description(t.oid, 'pg_type')             AS comment
FROM pg_catalog.pg_type t
JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
WHERE t.typtype = 'e'
  AND n.nspname = ANY (?::text[])
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e')
ORDER BY n.nspname, t.typname
