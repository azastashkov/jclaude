package org.jclaude.runtime.sandbox;

import java.util.List;
import java.util.Optional;

/** Sandbox status snapshot. */
public record SandboxStatus(
        boolean enabled,
        SandboxRequest requested,
        boolean supported,
        boolean active,
        boolean namespace_supported,
        boolean namespace_active,
        boolean network_supported,
        boolean network_active,
        FilesystemIsolationMode filesystem_mode,
        boolean filesystem_active,
        List<String> allowed_mounts,
        boolean in_container,
        List<String> container_markers,
        Optional<String> fallback_reason) {

    public SandboxStatus {
        allowed_mounts = List.copyOf(allowed_mounts);
        container_markers = List.copyOf(container_markers);
        fallback_reason = fallback_reason == null ? Optional.empty() : fallback_reason;
    }
}
