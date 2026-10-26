-- PgGitVisualizer: version-control metadata schema (_pgvc) for PostgreSQL 11+.
-- Idempotent: safe to run repeatedly. Must be executed by a SUPERUSER (event triggers).

CREATE SCHEMA IF NOT EXISTS _pgvc;

-- Commit graph: every commit stores its migration SQL and the full schema snapshot.
CREATE TABLE IF NOT EXISTS _pgvc.commits (
    hash            VARCHAR(64)  PRIMARY KEY,
    parent_hash     VARCHAR(64)  NULL REFERENCES _pgvc.commits (hash),
    author          VARCHAR(100) NOT NULL,
    message         TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    up_sql          TEXT         NOT NULL,
    down_sql        TEXT         NOT NULL,
    schema_snapshot JSONB        NOT NULL,
    ddl_log_history JSONB        NULL
);

CREATE INDEX IF NOT EXISTS commits_parent_hash_idx ON _pgvc.commits (parent_hash);

-- Named pointers into the graph; 'HEAD' is the commit the database schema is at.
CREATE TABLE IF NOT EXISTS _pgvc.refs (
    ref_name    VARCHAR(100) PRIMARY KEY,
    commit_hash VARCHAR(64)  NULL REFERENCES _pgvc.commits (hash)
);

-- DDL captured by the event triggers since the last commit.
CREATE TABLE IF NOT EXISTS _pgvc.ddl_audit_log (
    id              BIGSERIAL    PRIMARY KEY,
    event_type      VARCHAR(50),
    command_tag     VARCHAR(100),
    object_type     VARCHAR(100),
    object_identity TEXT,
    raw_query       TEXT,
    db_user         VARCHAR(100) DEFAULT session_user,
    executed_at     TIMESTAMPTZ  DEFAULT now()
);

-- The trigger functions are SECURITY DEFINER: they run with the rights of the superuser who installed them,
-- so DDL executed by ordinary developers is logged instead of failing on "permission denied" for _pgvc.
-- db_user keeps session_user, i.e. the real login that executed the DDL.

CREATE OR REPLACE FUNCTION _pgvc.fn_log_ddl_end()
    RETURNS event_trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $pgvc_fn$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT * FROM pg_event_trigger_ddl_commands() LOOP
        -- never log the version-control schema itself or temporary objects
        CONTINUE WHEN r.schema_name = '_pgvc'
                   OR r.schema_name LIKE 'pg\_temp%'
                   OR r.schema_name LIKE 'pg\_toast%';
        CONTINUE WHEN r.object_type = 'schema' AND r.object_identity = '_pgvc';
        INSERT INTO _pgvc.ddl_audit_log (event_type, command_tag, object_type, object_identity, raw_query)
        VALUES ('ddl_command_end', r.command_tag, r.object_type, r.object_identity, current_query());
    END LOOP;
END;
$pgvc_fn$;

CREATE OR REPLACE FUNCTION _pgvc.fn_log_sql_drop()
    RETURNS event_trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $pgvc_fn$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT * FROM pg_event_trigger_dropped_objects() LOOP
        -- only the objects named in the command, not their implicitly dropped indexes/types/constraints
        CONTINUE WHEN NOT r.original;
        CONTINUE WHEN r.is_temporary
                   OR r.schema_name = '_pgvc'
                   OR r.schema_name LIKE 'pg\_temp%'
                   OR r.schema_name LIKE 'pg\_toast%';
        CONTINUE WHEN r.object_type = 'schema' AND r.object_identity = '_pgvc';
        INSERT INTO _pgvc.ddl_audit_log (event_type, command_tag, object_type, object_identity, raw_query)
        VALUES ('sql_drop', tg_tag, r.object_type, r.object_identity, current_query());
    END LOOP;
END;
$pgvc_fn$;

REVOKE ALL ON FUNCTION _pgvc.fn_log_ddl_end() FROM PUBLIC;
REVOKE ALL ON FUNCTION _pgvc.fn_log_sql_drop() FROM PUBLIC;

DO $pgvc_install$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_event_trigger WHERE evtname = 'pgvc_ddl_trigger') THEN
        CREATE EVENT TRIGGER pgvc_ddl_trigger ON ddl_command_end
            EXECUTE PROCEDURE _pgvc.fn_log_ddl_end();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_event_trigger WHERE evtname = 'pgvc_drop_trigger') THEN
        CREATE EVENT TRIGGER pgvc_drop_trigger ON sql_drop
            EXECUTE PROCEDURE _pgvc.fn_log_sql_drop();
    END IF;
    -- re-enable triggers if someone disabled them
    ALTER EVENT TRIGGER pgvc_ddl_trigger ENABLE;
    ALTER EVENT TRIGGER pgvc_drop_trigger ENABLE;
END;
$pgvc_install$;
