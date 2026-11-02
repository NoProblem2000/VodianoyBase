package com.vodianoy.pgvc.vcs;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Throwing away uncommitted changes ("git reset --hard HEAD") and stale DDL events. */
@RestController
@RequestMapping("/api/vc/working-tree")
@RequiredArgsConstructor
public class WorkingTreeController {

    private final VersionControlService versionControl;

    /** The SQL a discard would run (for review and editing), without running it. */
    @PostMapping("/discard-plan")
    public DiscardPlan discardPlan(@RequestBody DiscardPlanRequest request) {
        return versionControl.discardPlan(request);
    }

    /** Reverts all (or the selected) uncommitted changes in one transaction. */
    @PostMapping("/discard")
    public DiscardResult discard(@RequestBody DiscardRequest request) {
        return versionControl.discardWorkingTree(request);
    }

    /** Empties _pgvc.ddl_audit_log only. */
    @PostMapping("/clear-log")
    public ClearLogResult clearLog(@RequestParam("envId") String envId) {
        return versionControl.clearDdlLog(envId);
    }
}
