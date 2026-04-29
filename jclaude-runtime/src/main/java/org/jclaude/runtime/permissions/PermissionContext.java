package org.jclaude.runtime.permissions;

import java.util.Objects;
import java.util.Optional;

/** Additional permission context supplied by hooks or higher-level orchestration. */
public final class PermissionContext {
    private final PermissionOverride overrideDecision;
    private final String overrideReason;

    public PermissionContext(Optional<PermissionOverride> overrideDecision, Optional<String> overrideReason) {
        this.overrideDecision = overrideDecision.orElse(null);
        this.overrideReason = overrideReason.orElse(null);
    }

    public static PermissionContext defaultContext() {
        return new PermissionContext(Optional.empty(), Optional.empty());
    }

    public Optional<PermissionOverride> override_decision() {
        return Optional.ofNullable(overrideDecision);
    }

    public Optional<String> override_reason() {
        return Optional.ofNullable(overrideReason);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PermissionContext other)) {
            return false;
        }
        return overrideDecision == other.overrideDecision && Objects.equals(overrideReason, other.overrideReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(overrideDecision, overrideReason);
    }

    @Override
    public String toString() {
        return "PermissionContext{overrideDecision=" + overrideDecision + ", overrideReason=" + overrideReason + "}";
    }
}
