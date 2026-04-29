package org.jclaude.runtime.trust;

import java.util.Optional;

/** Events emitted during trust resolution lifecycle. */
public sealed interface TrustEvent {

    record TrustRequired(String cwd, Optional<String> repo, Optional<String> worktree) implements TrustEvent {
        public TrustRequired {
            repo = repo == null ? Optional.empty() : repo;
            worktree = worktree == null ? Optional.empty() : worktree;
        }
    }

    record TrustResolved(String cwd, TrustPolicy policy, TrustResolution resolution) implements TrustEvent {}

    record TrustDenied(String cwd, String reason) implements TrustEvent {}
}
