package com.vodianoy.pgvc.snapshot;

import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.ScriptStatement;

import java.util.List;

/**
 * Live database of {@code environmentId} vs an uploaded reference snapshot.
 *
 * @param identical                   nothing differs
 * @param missingOrModifiedItems      what the database lacks or has in another form (selected by default)
 * @param destructiveItems            objects that exist only in the database, not in the snapshot: applying them
 *                                    would drop them (never selected by default)
 * @param upStatements                the full UP migration statement by statement, tied to the change ids
 * @param downStatements              the compensating DOWN migration statement by statement
 * @param upSql                       UP script of the default selection (missing / modified only)
 * @param downSql                     DOWN script of the default selection
 * @param targetHasUncommittedChanges the database differs from its own HEAD (or has a schema but no history yet)
 * @param serverVersion               server_version_num of the database (snapshot: {@link SnapshotSource#serverVersion})
 */
public record SnapshotComparisonResponse(String environmentId, String environmentName, String targetHead,
                                         SnapshotSource source, boolean identical,
                                         List<ChangeItem> missingOrModifiedItems, List<ChangeItem> destructiveItems,
                                         List<ScriptStatement> upStatements, List<ScriptStatement> downStatements,
                                         String upSql, String downSql, List<String> warnings,
                                         boolean targetHasUncommittedChanges, int serverVersion) {
}
