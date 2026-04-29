package org.jclaude.runtime.lane;

import java.util.Optional;

/** Session identity metadata. */
public record SessionIdentity(String title, String workspace, String purpose, Optional<String> placeholder_reason) {

    public SessionIdentity {
        placeholder_reason = placeholder_reason == null ? Optional.empty() : placeholder_reason;
    }

    public static SessionIdentity of(String title, String workspace, String purpose) {
        return new SessionIdentity(title, workspace, purpose, Optional.empty());
    }
}
