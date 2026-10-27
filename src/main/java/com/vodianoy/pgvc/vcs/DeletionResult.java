package com.vodianoy.pgvc.vcs;

import java.util.List;
import java.util.Map;

/**
 * @param deletedCommits hashes that were deleted
 * @param perEnvironment number of commit rows deleted in each environment
 */
public record DeletionResult(String hash, DeletionMode mode, List<String> deletedCommits,
                             Map<String, Integer> perEnvironment) {
}
