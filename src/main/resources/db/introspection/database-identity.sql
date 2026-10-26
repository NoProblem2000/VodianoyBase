-- Identity of the database (system_identifier of the cluster + oid of the database) and the server version.
-- pg_control_system() may be restricted for non-superusers; then the cluster part is 'unknown'.
SELECT CASE WHEN pg_catalog.has_function_privilege('pg_catalog.pg_control_system()', 'EXECUTE')
            THEN (SELECT s.system_identifier::text FROM pg_catalog.pg_control_system() s)
            ELSE 'unknown'
       END || ':' || (SELECT d.oid::text FROM pg_catalog.pg_database d WHERE d.datname = current_database())
                                                              AS source_database_id,
       current_setting('server_version_num')::int             AS server_version
