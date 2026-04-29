package org.jclaude.runtime.sandbox;

import java.util.List;
import java.util.Optional;

/** Sandbox configuration record. */
public record SandboxConfig(
        Optional<Boolean> enabled,
        Optional<Boolean> namespace_restrictions,
        Optional<Boolean> network_isolation,
        Optional<FilesystemIsolationMode> filesystem_mode,
        List<String> allowed_mounts) {

    public SandboxConfig {
        allowed_mounts = List.copyOf(allowed_mounts);
        enabled = enabled == null ? Optional.empty() : enabled;
        namespace_restrictions = namespace_restrictions == null ? Optional.empty() : namespace_restrictions;
        network_isolation = network_isolation == null ? Optional.empty() : network_isolation;
        filesystem_mode = filesystem_mode == null ? Optional.empty() : filesystem_mode;
    }

    public static SandboxConfig empty() {
        return new SandboxConfig(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    public SandboxRequest resolve_request(
            Optional<Boolean> enabled_override,
            Optional<Boolean> namespace_override,
            Optional<Boolean> network_override,
            Optional<FilesystemIsolationMode> filesystem_mode_override,
            Optional<List<String>> allowed_mounts_override) {
        return new SandboxRequest(
                enabled_override.orElse(enabled.orElse(true)),
                namespace_override.orElse(namespace_restrictions.orElse(true)),
                network_override.orElse(network_isolation.orElse(false)),
                filesystem_mode_override.or(() -> filesystem_mode).orElse(FilesystemIsolationMode.default_mode()),
                allowed_mounts_override.orElse(allowed_mounts));
    }
}
