-- Functions, procedures and window functions (prokind f / p / w; aggregates are skipped) of the given schemas.
-- Overloads are separate rows; the identity arguments tell them apart. Extension members (pg_depend deptype e) are
-- skipped. pg_get_functiondef returns a complete CREATE OR REPLACE statement.
SELECT p.oid                                                    AS function_oid,
       n.nspname                                                AS schema_name,
       p.proname                                                AS function_name,
       pg_catalog.pg_get_function_identity_arguments(p.oid)     AS identity_arguments,
       p.prokind                                                AS kind,
       CASE WHEN p.prokind <> 'p' THEN pg_catalog.pg_get_function_result(p.oid) END AS result_type,
       l.lanname                                                AS language_name,
       pg_catalog.pg_get_functiondef(p.oid)                     AS definition,
       pg_catalog.pg_get_userbyid(p.proowner)                   AS owner,
       ARRAY(SELECT CASE WHEN a.grantee = 0 THEN 'PUBLIC'
                           ELSE pg_catalog.quote_ident(pg_catalog.pg_get_userbyid(a.grantee)) END
                    || '=' || a.privilege_type || CASE WHEN a.is_grantable THEN '*' ELSE '' END
             FROM pg_catalog.aclexplode(COALESCE(p.proacl, pg_catalog.acldefault('f', p.proowner))) a
             WHERE a.grantee <> p.proowner
             ORDER BY 1)                                             AS acl,
       pg_catalog.obj_description(p.oid, 'pg_proc')             AS comment
FROM pg_catalog.pg_proc p
JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
JOIN pg_catalog.pg_language l ON l.oid = p.prolang
WHERE p.prokind IN ('f', 'p', 'w')
  AND n.nspname = ANY (?::text[])
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
ORDER BY n.nspname, p.proname, pg_catalog.pg_get_function_identity_arguments(p.oid)
