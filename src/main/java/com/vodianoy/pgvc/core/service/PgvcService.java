package com.vodianoy.pgvc.core.service;

import com.vodianoy.pgvc.core.bundle.OfflineBundleGenerator;
import com.vodianoy.pgvc.core.diff.DiffEngine;
import com.vodianoy.pgvc.core.diff.DiffResult;
import com.vodianoy.pgvc.core.env.ConnectionFactory;
import com.vodianoy.pgvc.core.env.EnvironmentConfig;
import com.vodianoy.pgvc.core.env.EnvironmentStore;
import com.vodianoy.pgvc.core.introspect.Introspector;
import com.vodianoy.pgvc.core.model.DatabaseSnapshot;
import com.vodianoy.pgvc.core.repo.AuditEntry;
import com.vodianoy.pgvc.core.repo.CommitHasher;
import com.vodianoy.pgvc.core.repo.CommitRecord;
import com.vodianoy.pgvc.core.repo.CommitSummary;
import com.vodianoy.pgvc.core.repo.PgvcRepository;
import com.vodianoy.pgvc.core.service.Dtos.Bundle;
import com.vodianoy.pgvc.core.service.Dtos.CommitDetails;
import com.vodianoy.pgvc.core.service.Dtos.CommitRequest;
import com.vodianoy.pgvc.core.service.Dtos.CommitResult;
import com.vodianoy.pgvc.core.service.Dtos.CompareResult;
import com.vodianoy.pgvc.core.service.Dtos.ConnectionTest;
import com.vodianoy.pgvc.core.service.Dtos.EnvironmentStatus;
import com.vodianoy.pgvc.core.service.Dtos.ExecuteRequest;
import com.vodianoy.pgvc.core.service.Dtos.Graph;
import com.vodianoy.pgvc.core.service.Dtos.GraphCommit;
import com.vodianoy.pgvc.core.service.Dtos.PushRequest;
import com.vodianoy.pgvc.core.service.Dtos.PushResult;
import com.vodianoy.pgvc.core.service.Dtos.WorkingTree;
import com.vodianoy.pgvc.core.util.Json;
import com.vodianoy.pgvc.core.util.PgvcException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Version-control workflows: Mode A (commit working tree), Mode B (execute &amp; commit), push, offline bundle. */
public final class PgvcService {

    private static final Pattern TX_CONTROL = Pattern.compile(
            "(?im)^\\s*(BEGIN|COMMIT|ROLLBACK|END|START\\s+TRANSACTION|ABORT)\\s*(WORK|TRANSACTION)?\\s*;");

    private final EnvironmentStore environments;
    private final ConnectionFactory connections;
    private final Introspector introspector = new Introspector();
    private final DiffEngine diffEngine = new DiffEngine();
    private final OfflineBundleGenerator bundleGenerator = new OfflineBundleGenerator();

    public PgvcService(EnvironmentStore environments, ConnectionFactory connections) {
        this.environments = environments;
        this.connections = connections;
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T withConnection(EnvironmentConfig env, SqlWork<T> work) {
        try (Connection c = connections.open(env)) {
            return work.run(c);
        } catch (SQLException e) {
            throw database(env, e);
        }
    }

    private <T> T inTransaction(EnvironmentConfig env, SqlWork<T> work) {
        try (Connection c = connections.open(env)) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw database(env, e);
        }
    }

    private static PgvcException database(EnvironmentConfig env, SQLException e) {
        String state = e.getSQLState() == null ? "" : " [SQLSTATE " + e.getSQLState() + "]";
        return new PgvcException(PgvcException.Kind.DATABASE, env.name() + ": " + e.getMessage() + state, e);
    }

    // ------------------------------------------------------------------ environments

    public ConnectionTest testConnection(EnvironmentConfig input) {
        EnvironmentConfig env = environments.resolvePassword(input);
        try (Connection c = connections.open(env)) {
            String[] info = serverInfo(c);
            boolean superuser = Boolean.parseBoolean(info[1]);
            return new ConnectionTest(true, info[0], superuser, superuser
                    ? "Connected"
                    : "Connected, but the user is not a SUPERUSER: event triggers cannot be installed");
        } catch (SQLException e) {
            return new ConnectionTest(false, null, false, e.getMessage());
        }
    }

    private static String[] serverInfo(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_setting('server_version'),"
                     + " (SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = current_user)")) {
            rs.next();
            return new String[]{rs.getString(1), Boolean.toString(rs.getBoolean(2))};
        }
    }

    public EnvironmentStatus status(String envId) {
        return status(environments.get(envId));
    }

    private EnvironmentStatus status(EnvironmentConfig env) {
        try (Connection c = connections.open(env)) {
            String[] info = serverInfo(c);
            PgvcRepository repo = new PgvcRepository(c);
            boolean installed = repo.isInstalled();
            return new EnvironmentStatus(env.id(), env.name(), env.color(), true, installed,
                    installed && repo.eventTriggersInstalled(), Boolean.parseBoolean(info[1]), info[0],
                    installed ? repo.head() : null, null);
        } catch (SQLException e) {
            return new EnvironmentStatus(env.id(), env.name(), env.color(), false, false, false, false, null, null,
                    e.getMessage());
        }
    }

    public EnvironmentStatus install(String envId) {
        EnvironmentConfig env = environments.get(envId);
        inTransaction(env, c -> {
            new PgvcRepository(c).install();
            return null;
        });
        return status(env);
    }

    // ------------------------------------------------------------------ graph

    public Graph graph() {
        List<EnvironmentConfig> envs = environments.list();
        if (envs.isEmpty()) {
            return new Graph(List.of(), List.of());
        }
        record EnvData(EnvironmentStatus status, List<CommitSummary> commits) {
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(envs.size(), 8));
        try {
            List<CompletableFuture<EnvData>> futures = new ArrayList<>();
            for (EnvironmentConfig env : envs) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    EnvironmentStatus st = status(env);
                    if (!st.reachable() || !st.installed()) {
                        return new EnvData(st, List.of());
                    }
                    try (Connection c = connections.open(env)) {
                        return new EnvData(st, new PgvcRepository(c).listCommits());
                    } catch (SQLException e) {
                        return new EnvData(new EnvironmentStatus(env.id(), env.name(), env.color(), false, false,
                                false, false, null, null, e.getMessage()), List.of());
                    }
                }, pool));
            }
            Map<String, CommitSummary> byHash = new LinkedHashMap<>();
            Map<String, List<String>> presence = new HashMap<>();
            List<EnvironmentStatus> statuses = new ArrayList<>();
            for (CompletableFuture<EnvData> f : futures) {
                EnvData d = f.join();
                statuses.add(d.status());
                for (CommitSummary s : d.commits()) {
                    byHash.putIfAbsent(s.hash(), s);
                    presence.computeIfAbsent(s.hash(), k -> new ArrayList<>()).add(d.status().id());
                }
            }
            List<GraphCommit> commits = new ArrayList<>();
            for (CommitSummary s : byHash.values()) {
                commits.add(new GraphCommit(s.hash(), s.parentHash(), s.author(), s.message(), s.createdAt(),
                        presence.getOrDefault(s.hash(), List.of())));
            }
            commits.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
            return new Graph(commits, statuses);
        } finally {
            pool.shutdown();
        }
    }

    public CommitDetails commit(String envId, String hash) {
        EnvironmentConfig env = environments.get(envId);
        CommitRecord r = withConnection(env, c -> new PgvcRepository(c).findCommit(hash));
        if (r == null) {
            throw PgvcException.notFound("Commit " + hash + " not found in " + env.name());
        }
        return new CommitDetails(env.id(), r.hash(), r.parentHash(), r.author(), r.message(), r.createdAt().toString(),
                r.upSql(), r.downSql(), Json.tree(r.ddlLogHistoryJson()), r.snapshot());
    }

    /** Schema difference between two commits (or a commit and the empty schema when fromHash is null). */
    public CompareResult compare(String envId, String fromHash, String toHash) {
        EnvironmentConfig env = environments.get(envId);
        return withConnection(env, c -> {
            PgvcRepository repo = new PgvcRepository(c);
            DatabaseSnapshot a = fromHash == null || fromHash.isBlank() ? DatabaseSnapshot.empty() : require(repo, fromHash, env).snapshot();
            DatabaseSnapshot b = require(repo, toHash, env).snapshot();
            DiffResult up = diffEngine.diff(a, b);
            DiffResult down = diffEngine.diff(b, a);
            return new CompareResult(fromHash, toHash, up.sql(), down.sql(), up.summary(), up.warnings());
        });
    }

    private static CommitRecord require(PgvcRepository repo, String hash, EnvironmentConfig env) throws SQLException {
        CommitRecord r = repo.findCommit(hash);
        if (r == null) {
            throw PgvcException.notFound("Commit " + hash + " not found in " + env.name());
        }
        return r;
    }

    private static void requireInstalled(PgvcRepository repo, EnvironmentConfig env) throws SQLException {
        if (!repo.isInstalled()) {
            throw PgvcException.conflict("Schema _pgvc is not installed in " + env.name() + ". Initialize the environment first.");
        }
    }

    private DatabaseSnapshot headSnapshot(PgvcRepository repo, String head, EnvironmentConfig env) throws SQLException {
        return head == null ? DatabaseSnapshot.empty() : require(repo, head, env).snapshot();
    }

    // ------------------------------------------------------------------ Mode A: working tree

    public WorkingTree workingTree(String envId) {
        EnvironmentConfig env = environments.get(envId);
        return withConnection(env, c -> {
            PgvcRepository repo = new PgvcRepository(c);
            requireInstalled(repo, env);
            String head = repo.head();
            DatabaseSnapshot base = headSnapshot(repo, head, env);
            DatabaseSnapshot live = introspector.snapshot(c);
            DiffResult up = diffEngine.diff(base, live);
            DiffResult down = diffEngine.diff(live, base);
            return new WorkingTree(env.id(), head, up.hasChanges(), up.sql(), down.sql(), up.summary(),
                    up.warnings(), repo.auditLog());
        });
    }

    public CommitResult commitWorkingTree(String envId, CommitRequest req) {
        EnvironmentConfig env = environments.get(envId);
        String message = requireMessage(req.message());
        String author = author(req.author(), env);
        return inTransaction(env, c -> {
            PgvcRepository repo = new PgvcRepository(c);
            requireInstalled(repo, env);
            repo.lock();
            String head = repo.head();
            if (!Objects.equals(emptyToNull(req.expectedHead()), head)) {
                throw PgvcException.conflict("HEAD of " + env.name() + " has moved (now " + abbreviate(head)
                        + "). Refresh the working tree and try again.");
            }
            DatabaseSnapshot base = headSnapshot(repo, head, env);
            DatabaseSnapshot live = introspector.snapshot(c);
            DiffResult up = diffEngine.diff(base, live);
            if (!up.hasChanges() && isBlank(req.upSql())) {
                throw PgvcException.badRequest("Nothing to commit: the working tree matches HEAD");
            }
            String upSql = isBlank(req.upSql()) ? up.sql() : req.upSql();
            String downSql = isBlank(req.downSql()) ? diffEngine.diff(live, base).sql() : req.downSql();
            List<AuditEntry> audit = repo.auditLog();
            CommitRecord record = newCommit(head, author, message, upSql, downSql, live, PgvcRepository.auditHistoryJson(audit));
            repo.insertCommit(record);
            repo.setHead(record.hash());
            if (!audit.isEmpty()) {
                repo.clearAuditLog(audit.get(audit.size() - 1).id());
            }
            return new CommitResult(record.summary(), up.summary());
        });
    }

    // ------------------------------------------------------------------ Mode B: execute & commit

    public CommitResult executeAndCommit(String envId, ExecuteRequest req) {
        EnvironmentConfig env = environments.get(envId);
        String message = requireMessage(req.message());
        String author = author(req.author(), env);
        if (isBlank(req.upSql())) {
            throw PgvcException.badRequest("UP SQL is empty");
        }
        if (TX_CONTROL.matcher(req.upSql()).find()) {
            throw PgvcException.badRequest("UP SQL must not contain transaction control statements"
                    + " (BEGIN/COMMIT/ROLLBACK): it is executed inside a single transaction managed by PgGitVisualizer");
        }
        return inTransaction(env, c -> {
            PgvcRepository repo = new PgvcRepository(c);
            requireInstalled(repo, env);
            repo.lock();
            String head = repo.head();
            DatabaseSnapshot base = headSnapshot(repo, head, env);
            DatabaseSnapshot before = introspector.snapshot(c);
            DiffResult drift = diffEngine.diff(base, before);
            if (drift.hasChanges() && !req.allowDirty()) {
                throw PgvcException.conflict("The working tree of " + env.name() + " has uncommitted changes ("
                        + drift.summary().size() + "). Commit them first or enable 'allow dirty'.");
            }
            try (Statement st = c.createStatement()) {
                st.execute(req.upSql());
            }
            DatabaseSnapshot after = introspector.snapshot(c);
            DiffResult change = diffEngine.diff(before, after);
            String downSql = isBlank(req.downSql()) ? diffEngine.diff(after, before).sql() : req.downSql();
            List<AuditEntry> audit = repo.auditLog();
            CommitRecord record = newCommit(head, author, message, req.upSql(), downSql, after,
                    PgvcRepository.auditHistoryJson(audit));
            repo.insertCommit(record);
            repo.setHead(record.hash());
            repo.clearAuditLog();
            return new CommitResult(record.summary(), change.summary());
        });
    }

    private CommitRecord newCommit(String parent, String author, String message, String upSql, String downSql,
                                   DatabaseSnapshot snapshot, String auditJson) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String snapshotJson = Json.write(snapshot);
        String hash = CommitHasher.hash(parent, author, message, now, upSql, downSql, snapshotJson);
        return new CommitRecord(hash, parent, author, message, now, upSql, downSql, snapshotJson, auditJson);
    }

    // ------------------------------------------------------------------ push

    public PushResult push(String targetEnvId, PushRequest req) {
        EnvironmentConfig target = environments.get(targetEnvId);
        EnvironmentConfig source = environments.get(req.sourceEnvironmentId());
        if (target.id().equals(source.id())) {
            throw PgvcException.badRequest("Source and target environments are the same");
        }
        String targetHead = withConnection(target, c -> {
            PgvcRepository repo = new PgvcRepository(c);
            return repo.isInstalled() ? repo.head() : null;
        });
        List<CommitRecord> chain = withConnection(source, c -> chain(new PgvcRepository(c), source, req.commitHash(), targetHead, true));
        if (chain.isEmpty()) {
            throw PgvcException.badRequest(target.name() + " is already at " + abbreviate(req.commitHash()));
        }
        return inTransaction(target, c -> {
            PgvcRepository repo = new PgvcRepository(c);
            repo.install();
            repo.lock();
            String head = repo.head();
            if (!Objects.equals(head, targetHead)) {
                throw PgvcException.conflict("HEAD of " + target.name() + " moved during push. Retry.");
            }
            if (!req.allowDirty()) {
                DatabaseSnapshot live = introspector.snapshot(c);
                if (head == null) {
                    if (!live.isEmpty()) {
                        throw PgvcException.conflict(target.name() + " has no version history but already contains schema"
                                + " objects. Enable 'allow dirty' to apply the commits on top of them anyway.");
                    }
                } else {
                    DiffResult drift = diffEngine.diff(require(repo, head, target).snapshot(), live);
                    if (drift.hasChanges()) {
                        throw PgvcException.conflict(target.name() + " has uncommitted schema changes ("
                                + String.join("; ", drift.summary().subList(0, Math.min(5, drift.summary().size())))
                                + "). Commit them on that environment or enable 'allow dirty'.");
                    }
                }
            }
            List<CommitSummary> applied = new ArrayList<>();
            for (CommitRecord r : chain) {
                try (Statement st = c.createStatement()) {
                    st.execute(r.upSql());
                } catch (SQLException e) {
                    throw new SQLException("Commit " + abbreviate(r.hash()) + " (" + r.message() + "): " + e.getMessage(),
                            e.getSQLState(), e);
                }
                repo.insertCommit(r);
                applied.add(r.summary());
            }
            String newHead = chain.get(chain.size() - 1).hash();
            repo.setHead(newHead);
            repo.clearAuditLog();
            return new PushResult(target.id(), head, newHead, applied);
        });
    }

    /**
     * Commits from (exclusive) {@code stopAt} up to (inclusive) {@code tip}, oldest first.
     *
     * @param stopMustBeAncestor when true, fails if {@code stopAt} is not an ancestor of {@code tip}
     */
    private List<CommitRecord> chain(PgvcRepository repo, EnvironmentConfig env, String tip, String stopAt,
                                     boolean stopMustBeAncestor) throws SQLException {
        List<CommitRecord> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cur = tip;
        while (cur != null && !cur.equals(stopAt)) {
            if (!seen.add(cur)) {
                throw PgvcException.conflict("Cycle in commit history at " + cur);
            }
            CommitRecord r = require(repo, cur, env);
            out.add(r);
            cur = r.parentHash();
        }
        if (stopMustBeAncestor && stopAt != null && cur == null) {
            throw PgvcException.conflict("Histories diverged: " + abbreviate(stopAt) + " is not an ancestor of "
                    + abbreviate(tip) + " in " + env.name() + ". The target is on another branch or ahead.");
        }
        Collections.reverse(out);
        return out;
    }

    // ------------------------------------------------------------------ offline bundle

    /** Bundle with commits fromHash..toHash (both inclusive, fromHash must be an ancestor of toHash). */
    public Bundle exportBundle(String sourceEnvId, String fromHash, String toHash) {
        EnvironmentConfig source = environments.get(sourceEnvId);
        if (isBlank(toHash)) {
            throw PgvcException.badRequest("Target commit (to) is required");
        }
        List<CommitRecord> chain = withConnection(source, c -> {
            PgvcRepository repo = new PgvcRepository(c);
            if (isBlank(fromHash) || fromHash.equals(toHash)) {
                return List.of(require(repo, toHash, source));
            }
            CommitRecord start = require(repo, fromHash, source);
            List<CommitRecord> list = chain(repo, source, toHash, start.parentHash(), false);
            if (list.isEmpty() || !list.get(0).hash().equals(fromHash)) {
                throw PgvcException.badRequest(abbreviate(fromHash) + " is not an ancestor of " + abbreviate(toHash));
            }
            return list;
        });
        String sql = bundleGenerator.generate(chain, source.name());
        String fileName = "pgvc-bundle-" + abbreviate(chain.get(0).hash()) + "-" + abbreviate(chain.get(chain.size() - 1).hash()) + ".sql";
        return new Bundle(fileName, sql);
    }

    // ------------------------------------------------------------------ helpers

    private static String requireMessage(String message) {
        if (isBlank(message)) {
            throw PgvcException.badRequest("Commit message is required");
        }
        return message.strip();
    }

    private static String author(String author, EnvironmentConfig env) {
        if (!isBlank(author)) {
            return author.strip().length() > 100 ? author.strip().substring(0, 100) : author.strip();
        }
        return isBlank(env.username()) ? "unknown" : env.username();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String emptyToNull(String s) {
        return isBlank(s) ? null : s;
    }

    private static String abbreviate(String hash) {
        return hash == null ? "<none>" : hash.length() > 10 ? hash.substring(0, 10) : hash;
    }
}
