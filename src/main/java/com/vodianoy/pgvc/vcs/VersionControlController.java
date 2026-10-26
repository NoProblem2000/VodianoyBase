package com.vodianoy.pgvc.vcs;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Working tree, commits (Mode A / Mode B) and rollback of one environment. */
@RestController
@RequestMapping("/api/environments/{envId}/vc")
@RequiredArgsConstructor
public class VersionControlController {

    private final VersionControlService versionControl;

    /** Uncommitted changes: diff HEAD vs live schema with UP / DOWN SQL, plus the captured DDL. */
    @GetMapping("/working-tree")
    public WorkingTreeStatus workingTree(@PathVariable String envId) {
        return versionControl.getWorkingTreeStatus(envId);
    }

    /** Mode A: record the working tree as a commit. */
    @PostMapping("/commit")
    public CommitResult commit(@PathVariable String envId, @RequestBody CommitRequest request) {
        return versionControl.commitWorkingTree(envId, request);
    }

    /** Mode B: execute a migration and commit it in one transaction. */
    @PostMapping("/execute")
    public CommitResult execute(@PathVariable String envId, @RequestBody ExecuteMigrationRequest request) {
        return versionControl.executeAndCommitSql(envId, request);
    }

    @GetMapping("/commits/{hash}")
    public CommitDetails commitDetails(@PathVariable String envId, @PathVariable String hash) {
        return versionControl.getCommit(envId, hash);
    }

    /** The rollback SQL that would be executed to return to {@code target} (to review or edit). */
    @GetMapping("/rollback-plan")
    public RollbackPlan rollbackPlan(@PathVariable String envId, @RequestParam("target") String targetHash) {
        return versionControl.getRollbackPlan(envId, targetHash);
    }

    @PostMapping("/rollback")
    public RollbackResult rollback(@PathVariable String envId, @RequestBody RollbackRequest request) {
        return versionControl.rollbackToCommit(envId, request);
    }
}
