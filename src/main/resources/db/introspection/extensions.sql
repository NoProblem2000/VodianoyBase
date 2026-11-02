-- Installed extensions of the database (database-wide). plpgsql is installed in every database and is skipped.
SELECT e.extname                                                AS extension_name,
       n.nspname                                                AS schema_name,
       e.extversion                                             AS extension_version
FROM pg_catalog.pg_extension e
JOIN pg_catalog.pg_namespace n ON n.oid = e.extnamespace
WHERE e.extname <> 'plpgsql'
ORDER BY e.extname
