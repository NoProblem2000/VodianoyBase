package com.vodianoy.pgvc.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.SchemaDiffEngine;
import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.environment.Environment;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.safety.MigrationSafetyAnalyzer;
import com.vodianoy.pgvc.safety.RiskLevel;
import com.vodianoy.pgvc.snapshot.SnapshotBundleDto;
import com.vodianoy.pgvc.snapshot.SnapshotSyncService;
import com.vodianoy.pgvc.storage.AuditLogEntry;
import com.vodianoy.pgvc.storage.PgVcStatus;
import com.vodianoy.pgvc.storage.PgVcStorageService;
import com.vodianoy.pgvc.vcs.VersionControlService;
import com.vodianoy.pgvc.vcs.WorkingTreeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Commands of the CLI mode ({@code java -jar pg-git-vc.jar --cli --action=...}) for CI/CD pipelines and scheduled
 * drift checks. Every command returns the process exit code:
 * <ul>
 *   <li>{@value #OK} — success, nothing to report;</li>
 *   <li>{@value #ERROR} — wrong arguments, unknown environment, database not reachable or not initialized;</li>
 *   <li>{@value #DRIFT} — {@code check-drift}: the database differs from its HEAD; {@code diff-report} with
 *       {@code --fail-on}: a change reaches that risk level.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CliCommands {

    public static final int OK = 0;
    public static final int ERROR = 1;
    public static final int DRIFT = 2;

    static final String USAGE = """
            PgGitVisualizer CLI

              java -jar pg-git-vc.jar --cli --action=check-drift --env=<id> [--output=drift.md]
                  Compares the live database with its HEAD commit. Exit code 2 when there are uncommitted
                  schema changes (schema drift), 0 when the database matches HEAD.

              java -jar pg-git-vc.jar --cli --action=diff-report --env=<id> [--snapshot=<file.pgsnap.json>]
                                      [--output=pr-comment.md] [--fail-on=dangerous|warning]
                  Markdown report for a Merge Request / Pull Request comment: what has to change in the database
                  to reach the snapshot (without --snapshot: the uncommitted changes since HEAD), with the risk of
                  every change. With --fail-on the exit code is 2 when a change reaches that risk level.

              Environments are read from ./pgvc-environments.json (--pgvc.environments-file=<path> to change it);
              the default ignore rules from pgvc-settings.json next to it.
              Exit codes: 0 ok, 1 error, 2 drift / risk threshold reached.
            """;

    private final EnvironmentManagerService environments;
    private final PgVcStorageService storage;
    private final PostgresSchemaIntrospector introspector;
    private final SchemaDiffEngine diffEngine;
    private final VersionControlService versionControl;
    private final MigrationSafetyAnalyzer safety;
    private final ObjectMapper objectMapper;
    private final MessageSource messages;

    /**
     * @param options {@code action}, {@code env}, {@code snapshot}, {@code output}, {@code fail-on}
     */
    public int run(Map<String, String> options, PrintStream out, PrintStream err) {
        String action = options.get("action");
        try {
            if (action == null || action.isBlank() || options.containsKey("help")) {
                out.print(USAGE);
                return action == null || action.isBlank() ? ERROR : OK;
            }
            return switch (action.strip()) {
                case "check-drift" -> checkDrift(options, out, err);
                case "diff-report" -> diffReport(options, out, err);
                default -> {
                    err.println("Unknown action: " + action);
                    err.print(USAGE);
                    yield ERROR;
                }
            };
        } catch (ApiException e) {
            err.println("ERROR: " + messages.getMessage(e.getCode(), e.getArgs(), e.getMessage(), Locale.ENGLISH));
            return ERROR;
        } catch (RuntimeException e) {
            err.println("ERROR: " + rootMessage(e));
            return ERROR;
        }
    }

    // ------------------------------------------------------------------ check-drift

    int checkDrift(Map<String, String> options, PrintStream out, PrintStream err) {
        Environment env = requireEnvironment(options, err);
        if (env == null) {
            return ERROR;
        }
        PgVcStatus status = storage.getStatus(env.getId());
        if (!status.reachable()) {
            err.println("ERROR: cannot connect to " + env.getName() + ": " + status.error());
            return ERROR;
        }
        if (!status.initialized()) {
            err.println("ERROR: version control is not initialized in " + env.getName()
                    + " (open the UI and press Initialize, or POST /api/environments/" + env.getId() + "/pgvc/initialize)");
            return ERROR;
        }
        WorkingTreeStatus tree = versionControl.getWorkingTreeStatus(env.getId());
        SchemaDiffResult diff = tree.diff();
        String head = tree.head() == null ? "(no commits)" : tree.head().substring(0, 7);
        if (!diff.isHasChanges()) {
            out.println("OK: " + env.getName() + " (" + env.getId() + ") matches HEAD " + head + ": no schema drift.");
            writeOutput(options, "## ✅ No schema drift\n\n`" + env.getName() + "` matches HEAD `" + head + "`.\n", out);
            return OK;
        }
        StringBuilder text = new StringBuilder();
        text.append("SCHEMA DRIFT: ").append(env.getName()).append(" (").append(env.getId()).append(") has ")
                .append(diff.getChanges().size()).append(" uncommitted change(s) since HEAD ").append(head).append('\n');
        for (ChangeItem c : diff.getChanges()) {
            text.append("  ").append(letter(c)).append(' ').append(c.getObjectType()).append(' ').append(c.getObjectName())
                    .append(c.getDetails() == null || c.getDetails().isBlank() ? "" : ": " + c.getDetails()).append('\n');
        }
        List<AuditLogEntry> audit = tree.auditLog();
        if (!audit.isEmpty()) {
            text.append("\nDDL captured since the last commit:\n");
            for (AuditLogEntry a : audit) {
                text.append("  ").append(a.executedAt()).append("  ").append(a.dbUser()).append("  ").append(a.commandTag())
                        .append(' ').append(a.objectIdentity() == null ? "" : a.objectIdentity()).append('\n');
            }
        }
        text.append("\nSQL that records the current state (UP):\n").append(diff.getUpSql().strip()).append('\n');
        out.print(text);

        List<String> context = new ArrayList<>();
        context.add("**Database:** `" + env.getName() + "` (env `" + env.getId() + "`) · **HEAD:** `" + head + "`");
        if (!audit.isEmpty()) {
            context.add("**DDL captured since the last commit:** " + audit.size() + " statement(s), last by `"
                    + audit.get(audit.size() - 1).dbUser() + "`");
        }
        writeOutput(options, MarkdownReport.render("Schema drift detected", context, diff), out);
        return DRIFT;
    }

    // ------------------------------------------------------------------ diff-report

    int diffReport(Map<String, String> options, PrintStream out, PrintStream err) {
        Environment env = requireEnvironment(options, err);
        if (env == null) {
            return ERROR;
        }
        PgVcStatus status = storage.getStatus(env.getId());
        if (!status.reachable()) {
            err.println("ERROR: cannot connect to " + env.getName() + ": " + status.error());
            return ERROR;
        }
        String snapshotPath = options.get("snapshot");
        SchemaDiffResult diff;
        List<String> context = new ArrayList<>();
        String title;
        if (snapshotPath != null && !snapshotPath.isBlank()) {
            SnapshotBundleDto bundle;
            Path file = Path.of(snapshotPath.strip());
            try {
                bundle = SnapshotSyncService.validate(objectMapper.readValue(file.toFile(), SnapshotBundleDto.class));
            } catch (IOException e) {
                err.println("ERROR: cannot read snapshot " + file + ": " + e.getMessage());
                return ERROR;
            }
            DatabaseSnapshot current = introspector.introspectWithData(env.getId(),
                    PostgresSchemaIntrospector.dataTables(bundle.getSchemaSnapshot()));
            diff = diffEngine.compare(current, bundle.getSchemaSnapshot(), true);
            safety.annotate(diff, current);
            title = "Schema diff: " + env.getName() + " → " + file.getFileName();
            context.add("**Database:** `" + env.getName() + "` (env `" + env.getId() + "`, PostgreSQL "
                    + major(current.getServerVersion()) + ")");
            context.add("**Target snapshot:** `" + file.getFileName() + "` — " + describe(bundle));
        } else {
            if (!status.initialized()) {
                err.println("ERROR: version control is not initialized in " + env.getName()
                        + ": pass --snapshot=<file.pgsnap.json> or initialize it");
                return ERROR;
            }
            WorkingTreeStatus tree = versionControl.getWorkingTreeStatus(env.getId());
            diff = tree.diff();
            String head = tree.head() == null ? "(no commits)" : tree.head().substring(0, 7);
            title = "Uncommitted schema changes: " + env.getName();
            context.add("**Database:** `" + env.getName() + "` (env `" + env.getId() + "`) · **HEAD:** `" + head + "`");
        }
        RiskLevel highest = MarkdownReport.highestRisk(diff.getChanges());
        context.add("**Highest risk:** " + switch (highest) {
            case DANGEROUS -> "🔴 DANGEROUS";
            case WARNING -> "🟡 WARNING";
            case SAFE -> "🟢 SAFE";
        });
        String markdown = MarkdownReport.render(title, context, diff);
        if (options.get("output") == null || options.get("output").isBlank()) {
            out.print(markdown);
        } else {
            writeOutput(options, markdown, out);
            out.println(diff.getChanges().size() + " change(s), highest risk " + highest + ": report written to "
                    + options.get("output"));
        }
        String failOn = options.get("fail-on");
        if (failOn != null && !failOn.isBlank()) {
            RiskLevel threshold;
            try {
                threshold = RiskLevel.valueOf(failOn.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                err.println("ERROR: --fail-on must be dangerous or warning, not " + failOn);
                return ERROR;
            }
            if (threshold != RiskLevel.SAFE && highest.ordinal() >= threshold.ordinal()) {
                err.println("FAILED: a change is " + highest + " (--fail-on=" + failOn + ")");
                return DRIFT;
            }
        }
        return OK;
    }

    // ------------------------------------------------------------------ helpers

    private Environment requireEnvironment(Map<String, String> options, PrintStream err) {
        String id = options.get("env");
        if (id == null || id.isBlank()) {
            err.println("ERROR: --env=<environment id> is required. Known environments: " + known());
            return null;
        }
        try {
            return environments.getEnvironment(id.strip());
        } catch (ApiException e) {
            err.println("ERROR: unknown environment '" + id + "'. Known environments: " + known());
            return null;
        }
    }

    private String known() {
        List<String> ids = environments.listEnvironments().stream().map(Environment::getId).toList();
        return ids.isEmpty() ? "(none in " + environments.getFile() + ")" : String.join(", ", ids);
    }

    private static void writeOutput(Map<String, String> options, String markdown, PrintStream out) {
        String output = options.get("output");
        if (output == null || output.isBlank()) {
            return;
        }
        try {
            Path file = Path.of(output.strip());
            if (file.toAbsolutePath().getParent() != null) {
                Files.createDirectories(file.toAbsolutePath().getParent());
            }
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + output + ": " + e.getMessage(), e);
        }
    }

    private static String describe(SnapshotBundleDto b) {
        StringBuilder sb = new StringBuilder();
        sb.append(b.getSourceEnvironmentName() == null ? "unknown source" : "`" + b.getSourceEnvironmentName() + "`");
        if (b.getSourceCommitHash() != null) {
            sb.append(" @ `").append(b.getSourceCommitHash(), 0, Math.min(7, b.getSourceCommitHash().length())).append('`');
            if (b.getSourceCommitMessage() != null) {
                sb.append(" “").append(MarkdownReport.cell(b.getSourceCommitMessage().split("\\R", 2)[0])).append('”');
            }
        } else {
            sb.append(" (live schema)");
        }
        if (b.getExportedAt() != null) {
            sb.append(", exported ").append(b.getExportedAt());
        }
        return sb.toString();
    }

    private static String letter(ChangeItem c) {
        return switch (c.getChangeType()) {
            case ADDED -> "A";
            case MODIFIED -> "M";
            case RENAMED -> "R";
            case DELETED -> "D";
        };
    }

    private static String major(int serverVersion) {
        return serverVersion <= 0 ? "?" : String.valueOf(serverVersion / 10000);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
