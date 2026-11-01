package com.vodianoy.pgvc.execution;

import com.vodianoy.pgvc.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlScriptsTest {

    @Test
    void splitsStatementsButNotInsideQuotesDollarBodiesOrComments() {
        String script = """
                -- a comment; with a semicolon
                CREATE TABLE t (a text DEFAULT 'x;y', "b;c" int);
                /* block ; /* nested ; */ still comment */
                CREATE FUNCTION f() RETURNS int LANGUAGE plpgsql AS $body$
                BEGIN
                  -- pgvc:step (inside a body: not a marker)
                  RETURN 1;
                END;
                $body$;
                INSERT INTO t VALUES (E'it\\'s;ok', 1)
                """;
        List<String> statements = SqlScripts.statements(script);
        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).startsWith("CREATE TABLE t").endsWith("int);");
        assertThat(statements.get(1)).contains("$body$;").contains("RETURN 1;");
        assertThat(statements.get(2)).startsWith("INSERT INTO t").contains("E'it\\'s;ok'");
        assertThat(SqlScripts.steps(script)).hasSize(1);
    }

    @Test
    void stepMarkersSplitSteps() {
        String script = """
                ALTER TABLE a ADD CONSTRAINT fk FOREIGN KEY (b) REFERENCES b (id) NOT VALID;
                -- pgvc:step validate
                ALTER TABLE a VALIDATE CONSTRAINT fk;
                -- pgvc:step
                CREATE INDEX CONCURRENTLY a_b_idx ON a (b);
                -- pgvc:step
                """;
        List<MigrationStep> steps = MigrationStep.plan(script);
        assertThat(steps).hasSize(3);
        assertThat(steps).extracting(MigrationStep::transactional).containsExactly(true, true, false);
        assertThat(steps.get(2).statements()).containsExactly("CREATE INDEX CONCURRENTLY a_b_idx ON a (b);");
        assertThat(MigrationStep.isMultiStep(steps)).isTrue();
        assertThat(MigrationStep.isMultiStep(MigrationStep.plan("CREATE TABLE x (id int); CREATE INDEX ON x (id);"))).isFalse();
        assertThat(MigrationStep.isMultiStep(MigrationStep.plan("CREATE INDEX CONCURRENTLY i ON x (id)"))).isTrue();
    }

    @Test
    void normalizeDropsCommentsAndCollapsesWhitespace() {
        assertThat(SqlScripts.normalize("-- c\n  ALTER   TABLE\n\t x /* y */ ADD COLUMN z int ;"))
                .isEqualTo("ALTER TABLE x ADD COLUMN z int");
        assertThat(SqlScripts.normalize("SELECT 'a  --  b'")).isEqualTo("SELECT 'a  --  b'");
    }

    @Test
    void transactionalRewriteRemovesConcurrently() {
        TransactionalScript t = TransactionalScript.of("""
                ALTER TABLE a ADD COLUMN c int;
                -- pgvc:step
                CREATE UNIQUE INDEX CONCURRENTLY a_c_idx ON a (c);
                DROP INDEX CONCURRENTLY old_idx;
                VACUUM a;
                """);
        assertThat(t.changed()).isTrue();
        assertThat(t.rewritten()).hasSize(3);
        assertThat(t.sql()).contains("CREATE UNIQUE INDEX a_c_idx ON a (c);").contains("DROP INDEX old_idx;")
                .contains("-- pgvc: skipped (VACUUM").doesNotContain("CONCURRENTLY a_c_idx");
        assertThat(MigrationStep.isMultiStep(MigrationStep.plan(t.sql()))).isTrue(); // markers stay, all transactional
        assertThat(MigrationStep.plan(t.sql())).allMatch(MigrationStep::transactional);
        TransactionalScript plain = TransactionalScript.of("CREATE INDEX i ON a (c);");
        assertThat(plain.changed()).isFalse();
        assertThat(plain.sql()).isEqualTo("CREATE INDEX i ON a (c);");
    }

    @Test
    void timeoutsAreValidatedBeforeTheyReachSql() {
        Timeouts t = new Timeouts(" 500ms ", null);
        assertThat(t.lockTimeout()).isEqualTo("500ms");
        assertThat(t.statementTimeout()).isEqualTo("60s");
        assertThat(t.setLocalSql()).isEqualTo("SET LOCAL lock_timeout = '500ms'; SET LOCAL statement_timeout = '60s'");
        assertThat(new Timeouts("0", "2 min").statementTimeout()).isEqualTo("2min");
        assertThatThrownBy(() -> new Timeouts("3s'; DROP TABLE x; --", "60s")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new Timeouts("soon", "60s")).isInstanceOf(ApiException.class);
        assertThat(Timeouts.of("", " ", new Timeouts("1s", "5s"))).isEqualTo(new Timeouts("1s", "5s"));
    }
}
