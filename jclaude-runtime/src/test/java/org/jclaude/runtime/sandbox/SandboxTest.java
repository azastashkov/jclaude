package org.jclaude.runtime.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SandboxTest {

    @Test
    void detects_container_markers_from_multiple_sources() {
        ContainerEnvironment detected = Sandbox.detect_container_environment_from(new SandboxDetectionInputs(
                List.of(Map.entry("container", "docker")), true, false, "12:memory:/docker/abc"));

        assertThat(detected.in_container()).isTrue();
        assertThat(detected.markers()).contains("/.dockerenv", "env:container=docker", "/proc/1/cgroup:docker");
    }

    @Test
    void resolves_request_with_overrides() {
        SandboxConfig config = new SandboxConfig(
                Optional.of(true),
                Optional.of(true),
                Optional.of(false),
                Optional.of(FilesystemIsolationMode.WORKSPACE_ONLY),
                List.of("logs"));

        SandboxRequest request = config.resolve_request(
                Optional.of(true),
                Optional.of(false),
                Optional.of(true),
                Optional.of(FilesystemIsolationMode.ALLOW_LIST),
                Optional.of(List.of("tmp")));

        assertThat(request.enabled()).isTrue();
        assertThat(request.namespace_restrictions()).isFalse();
        assertThat(request.network_isolation()).isTrue();
        assertThat(request.filesystem_mode()).isEqualTo(FilesystemIsolationMode.ALLOW_LIST);
        assertThat(request.allowed_mounts()).containsExactly("tmp");
    }

    @Test
    void builds_linux_launcher_with_network_flag_when_requested() {
        SandboxConfig config = SandboxConfig.empty();
        SandboxStatus status = Sandbox.resolve_sandbox_status_for_request(
                config.resolve_request(
                        Optional.of(true),
                        Optional.of(true),
                        Optional.of(true),
                        Optional.of(FilesystemIsolationMode.WORKSPACE_ONLY),
                        Optional.empty()),
                Path.of("/workspace"));

        Optional<LinuxSandboxCommand> launcher =
                Sandbox.build_linux_sandbox_command("printf hi", Path.of("/workspace"), status);

        if (launcher.isPresent()) {
            assertThat(launcher.get().program()).isEqualTo("unshare");
            assertThat(launcher.get().args()).contains("--mount");
            assertThat(launcher.get().args().contains("--net")).isEqualTo(status.network_active());
        }
    }
}
