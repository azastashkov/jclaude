package org.jclaude.runtime.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/** Sandbox helpers; macOS is a no-op for the launcher builder. */
public final class Sandbox {

    private Sandbox() {}

    public static ContainerEnvironment detect_container_environment() {
        String cgroup;
        try {
            cgroup = Files.readString(Paths.get("/proc/1/cgroup"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            cgroup = null;
        }
        List<Map.Entry<String, String>> env_pairs = new ArrayList<>();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            env_pairs.add(Map.entry(e.getKey(), e.getValue()));
        }
        return detect_container_environment_from(new SandboxDetectionInputs(
                env_pairs,
                Files.exists(Paths.get("/.dockerenv")),
                Files.exists(Paths.get("/run/.containerenv")),
                cgroup));
    }

    public static ContainerEnvironment detect_container_environment_from(SandboxDetectionInputs inputs) {
        TreeSet<String> markers = new TreeSet<>();
        if (inputs.dockerenv_exists()) {
            markers.add("/.dockerenv");
        }
        if (inputs.containerenv_exists()) {
            markers.add("/run/.containerenv");
        }
        for (Map.Entry<String, String> entry : inputs.env_pairs()) {
            String normalized = entry.getKey().toLowerCase(Locale.ROOT);
            boolean known = normalized.equals("container")
                    || normalized.equals("docker")
                    || normalized.equals("podman")
                    || normalized.equals("kubernetes_service_host");
            if (known && !entry.getValue().isEmpty()) {
                markers.add("env:" + entry.getKey() + "=" + entry.getValue());
            }
        }
        if (inputs.proc_1_cgroup() != null) {
            for (String needle : new String[] {"docker", "containerd", "kubepods", "podman", "libpod"}) {
                if (inputs.proc_1_cgroup().contains(needle)) {
                    markers.add("/proc/1/cgroup:" + needle);
                }
            }
        }
        List<String> sorted = new ArrayList<>(markers);
        return new ContainerEnvironment(!sorted.isEmpty(), sorted);
    }

    public static SandboxStatus resolve_sandbox_status(SandboxConfig config, Path cwd) {
        SandboxRequest request = config.resolve_request(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        return resolve_sandbox_status_for_request(request, cwd);
    }

    public static SandboxStatus resolve_sandbox_status_for_request(SandboxRequest request, Path cwd) {
        ContainerEnvironment container = detect_container_environment();
        boolean is_linux = is_linux();
        boolean namespace_supported = is_linux && unshare_user_namespace_works();
        boolean network_supported = namespace_supported;
        boolean filesystem_active = request.enabled() && request.filesystem_mode() != FilesystemIsolationMode.OFF;
        List<String> fallback_reasons = new ArrayList<>();

        if (request.enabled() && request.namespace_restrictions() && !namespace_supported) {
            fallback_reasons.add("namespace isolation unavailable (requires Linux with `unshare`)");
        }
        if (request.enabled() && request.network_isolation() && !network_supported) {
            fallback_reasons.add("network isolation unavailable (requires Linux with `unshare`)");
        }
        if (request.enabled()
                && request.filesystem_mode() == FilesystemIsolationMode.ALLOW_LIST
                && request.allowed_mounts().isEmpty()) {
            fallback_reasons.add("filesystem allow-list requested without configured mounts");
        }

        boolean active = request.enabled()
                && (!request.namespace_restrictions() || namespace_supported)
                && (!request.network_isolation() || network_supported);

        List<String> allowed_mounts = normalize_mounts(request.allowed_mounts(), cwd);

        return new SandboxStatus(
                request.enabled(),
                request,
                namespace_supported,
                active,
                namespace_supported,
                request.enabled() && request.namespace_restrictions() && namespace_supported,
                network_supported,
                request.enabled() && request.network_isolation() && network_supported,
                request.filesystem_mode(),
                filesystem_active,
                allowed_mounts,
                container.in_container(),
                container.markers(),
                fallback_reasons.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", fallback_reasons)));
    }

    public static Optional<LinuxSandboxCommand> build_linux_sandbox_command(
            String command, Path cwd, SandboxStatus status) {
        if (!is_linux() || !status.enabled() || (!status.namespace_active() && !status.network_active())) {
            return Optional.empty();
        }

        List<String> args =
                new ArrayList<>(List.of("--user", "--map-root-user", "--mount", "--ipc", "--pid", "--uts", "--fork"));
        if (status.network_active()) {
            args.add("--net");
        }
        args.add("sh");
        args.add("-lc");
        args.add(command);

        Path sandbox_home = cwd.resolve(".sandbox-home");
        Path sandbox_tmp = cwd.resolve(".sandbox-tmp");
        List<Map.Entry<String, String>> env = new ArrayList<>();
        env.add(Map.entry("HOME", sandbox_home.toString()));
        env.add(Map.entry("TMPDIR", sandbox_tmp.toString()));
        env.add(Map.entry(
                "CLAWD_SANDBOX_FILESYSTEM_MODE", status.filesystem_mode().as_str()));
        env.add(Map.entry("CLAWD_SANDBOX_ALLOWED_MOUNTS", String.join(":", status.allowed_mounts())));
        String path_env = System.getenv("PATH");
        if (path_env != null) {
            env.add(Map.entry("PATH", path_env));
        }

        return Optional.of(new LinuxSandboxCommand("unshare", args, env));
    }

    private static List<String> normalize_mounts(List<String> mounts, Path cwd) {
        List<String> out = new ArrayList<>();
        for (String mount : mounts) {
            Path path = Paths.get(mount);
            Path resolved = path.isAbsolute() ? path : cwd.resolve(path);
            out.add(resolved.toString());
        }
        return out;
    }

    private static boolean is_linux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static boolean command_exists(String command) {
        String path_env = System.getenv("PATH");
        if (path_env == null) {
            return false;
        }
        for (String dir : path_env.split(java.io.File.pathSeparator)) {
            if (Files.exists(Paths.get(dir, command))) {
                return true;
            }
        }
        return false;
    }

    private static volatile Optional<Boolean> UNSHARE_RESULT = Optional.empty();

    private static synchronized boolean unshare_user_namespace_works() {
        if (UNSHARE_RESULT.isPresent()) {
            return UNSHARE_RESULT.get();
        }
        boolean result;
        if (!command_exists("unshare")) {
            result = false;
        } else {
            try {
                ProcessBuilder pb = new ProcessBuilder("unshare", "--user", "--map-root-user", "true");
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                Process p = pb.start();
                p.getOutputStream().close();
                int exit = p.waitFor();
                result = exit == 0;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                result = false;
            }
        }
        UNSHARE_RESULT = Optional.of(result);
        return result;
    }

    /** Exposed only for tests so they can adjust to environment. */
    static Map<String, String> linux_sandbox_env_map(LinuxSandboxCommand command) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : command.env()) {
            result.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(result);
    }
}
