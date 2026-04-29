package org.jclaude.runtime.branch;

import java.util.List;

/** Branch freshness sealed enum mirrored from Rust. */
public sealed interface BranchFreshness {

    record Fresh() implements BranchFreshness {}

    record Stale(int commits_behind, List<String> missing_fixes) implements BranchFreshness {
        public Stale {
            missing_fixes = List.copyOf(missing_fixes);
        }
    }

    record Diverged(int ahead, int behind, List<String> missing_fixes) implements BranchFreshness {
        public Diverged {
            missing_fixes = List.copyOf(missing_fixes);
        }
    }
}
