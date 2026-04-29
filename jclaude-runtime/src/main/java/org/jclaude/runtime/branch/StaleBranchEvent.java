package org.jclaude.runtime.branch;

import java.util.List;

/** Events emitted by stale branch checks. */
public sealed interface StaleBranchEvent {

    record BranchStaleAgainstMain(String branch, int commits_behind, List<String> missing_fixes)
            implements StaleBranchEvent {
        public BranchStaleAgainstMain {
            missing_fixes = List.copyOf(missing_fixes);
        }
    }

    record RebaseAttempted(String branch, String result) implements StaleBranchEvent {}

    record MergeForwardAttempted(String branch, String result) implements StaleBranchEvent {}
}
