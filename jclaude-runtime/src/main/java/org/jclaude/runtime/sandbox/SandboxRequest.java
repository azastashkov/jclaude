package org.jclaude.runtime.sandbox;

import java.util.List;

/** Resolved sandbox request. */
public record SandboxRequest(
        boolean enabled,
        boolean namespace_restrictions,
        boolean network_isolation,
        FilesystemIsolationMode filesystem_mode,
        List<String> allowed_mounts) {

    public SandboxRequest {
        allowed_mounts = List.copyOf(allowed_mounts);
    }
}
