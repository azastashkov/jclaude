package org.jclaude.runtime.trust;

import java.util.Optional;

/** Allowlist entry for trust matching. */
public record TrustAllowlistEntry(String pattern, Optional<String> worktree_pattern, Optional<String> description) {

    public TrustAllowlistEntry {
        worktree_pattern = worktree_pattern == null ? Optional.empty() : worktree_pattern;
        description = description == null ? Optional.empty() : description;
    }

    public static TrustAllowlistEntry of(String pattern) {
        return new TrustAllowlistEntry(pattern, Optional.empty(), Optional.empty());
    }

    public TrustAllowlistEntry with_worktree_pattern(String pattern) {
        return new TrustAllowlistEntry(this.pattern, Optional.of(pattern), description);
    }

    public TrustAllowlistEntry with_description(String desc) {
        return new TrustAllowlistEntry(pattern, worktree_pattern, Optional.of(desc));
    }
}
