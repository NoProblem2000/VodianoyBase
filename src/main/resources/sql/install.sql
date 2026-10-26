-- PgGitVisualizer: metadata schema (_pgvc) for PostgreSQL 11+
-- Idempotent: safe to execute many times. Requires SUPERUSER (event triggers).

CREATE SCHEMA IF NOT EXISTS _pgvc;

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

CREATE TABLE IF NOT EXISTS _pgvc.refs (
    ref_name    VARCHAR(100) PRIMARY KEY,
    commit_hash VARCHAR(64)  NULL REFERENCES _pgvc.commits (hash)
);

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

CREATE OR REPLACE FUNCTION _pgvc.fn_log_ddl_command_end()
    RETURNS event_trigger
    LANGUAGE plpgsql
AS $pgvc_fn$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT * FROM pg_event_trigger_ddl_commands() LOOP
        IF r.schema_name IS NOT NULL
           AND (r.schema_name = '_pgvc' OR r.schema_name LIKE 'pg\_temp%' OR r.schema_name LIKE 'pg\_toast%') THEN
            CONTINUE;
        END IF;
        IF r.object_type = 'schema' AND r.object_identity = '_pgvc' THEN
            CONTINUE;
        END IF;
        INSERT INTO _pgvc.ddl_audit_log (event_type, command_tag, object_type, object_identity, raw_query)
        VALUES ('ddl_command_end', r.command_tag, r.object_type, r.object_identity, current_query());
    END LOOP;
END;
$pgvc_fn$;

CREATE OR REPLACE FUNCTION _pgvc.fn_log_sql_drop()
    RETURNS event_trigger
    LANGUAGE plpgsql
AS $pgvc_fn$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT * FROM pg_event_trigger_dropped_objects() LOOP
        IF NOT r.original THEN
            CONTINUE;
        END IF;
        IF r.schema_name IS NOT NULL
           AND (r.schema_name = '_pgvc' OR r.schema_name LIKE 'pg\_temp%' OR r.schema_name LIKE 'pg\_toast%') THEN
            CONTINUE;
        END IF;
        INSERT INTO _pgvc.ddl_audit_log (event_type, command_tag, object_type, object_identity, raw_query)
        VALUES ('sql_drop', tg_tag, r.object_type, r.object_identity, current_query());
    END LOOP;
END;
$pgvc_fn$;

DO $pgvc_install$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_event_trigger WHERE evtname = 'pgvc_ddl_command_end') THEN
        CREATE EVENT TRIGGER pgvc_ddl_command_end ON ddl_command_end
            EXECUTE PROCEDURE _pgvc.fn_log_ddl_command_end();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_event_trigger WHERE evtname = 'pgvc_sql_drop') THEN
        CREATE EVENT TRIGGER pgvc_sql_drop ON sql_drop
            EXECUTE PROCEDURE _pgvc.fn_log_sql_drop();
    END IF;
END;
$pgvc_install$;
