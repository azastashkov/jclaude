package org.jclaude.cli.subcommand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.cli.OutputFormat;
import org.jclaude.runtime.sandbox.Sandbox;
import org.jclaude.runtime.sandbox.SandboxConfig;
import org.jclaude.runtime.sandbox.SandboxStatus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code jclaude sandbox} — sandbox status report. Linux: prints which sandbox tool is available
 * (bwrap/firejail/unshare). macOS: prints {@code no-op}. Mirrors Rust {@code
 * handle_sandbox_command}.
 */
@Command(
        name = "sandbox",
        mixinStandardHelpOptions = true,
        description = "Sandbox availability and active configuration.")
public final class SandboxSubcommand implements Callable<Integer> {

    @Option(
            names = {"--output-format"},
            description = "Output format: text or json (default: ${DEFAULT-VALUE}).",
            defaultValue = "text")
    private String output_format;

    @Override
    public Integer call() {
        OutputFormat fmt = OutputFormat.parse(output_format);
        SandboxReport report = collect();
        if (fmt == OutputFormat.JSON) {
            System.out.println(format_sandbox_json(report));
        } else {
            System.out.println(format_sandbox_text(report));
        }
        return 0;
    }

    static SandboxReport collect() {
        Path cwd = Paths.get("").toAbsolutePath();
        SandboxStatus status = Sandbox.resolve_sandbox_status(SandboxConfig.empty(), cwd);
        boolean is_linux = is_linux();
        boolean is_macos = is_macos();

        List<String> tools_available = new ArrayList<>();
        if (is_linux) {
            for (String tool : new String[] {"unshare", "bwrap", "firejail"}) {
                if (command_exists(tool)) {
                    tools_available.add(tool);
                }
            }
        }
        return new SandboxReport(
                is_macos ? "macos" : (is_linux ? "linux" : "other"),
                is_macos,
                is_linux,
                tools_available,
                status.enabled(),
                status.active(),
                status.namespace_supported(),
                status.namespace_active(),
                status.network_supported(),
                status.network_active(),
                status.filesystem_mode().as_str(),
                status.filesystem_active(),
                status.in_container(),
                status.container_markers(),
                status.fallback_reason().orElse(null));
    }

    static String format_sandbox_text(SandboxReport r) {
        List<String> lines = new ArrayList<>();
        lines.add("Sandbox");
        lines.add("  Platform         " + r.platform());
        if (r.is_macos()) {
            lines.add("  Mode             no-op (macOS)");
        } else if (r.is_linux()) {
            lines.add("  Mode             linux");
            lines.add("  Tools available  "
                    + (r.tools_available().isEmpty() ? "(none)" : String.join(", ", r.tools_available())));
        } else {
            lines.add("  Mode             unsupported (sandbox is no-op)");
        }
        lines.add("  Enabled          " + r.enabled());
        lines.add("  Active           " + r.active());
        lines.add("  Namespace ok     " + r.namespace_supported() + " (active: " + r.namespace_active() + ")");
        lines.add("  Network ok       " + r.network_supported() + " (active: " + r.network_active() + ")");
        lines.add("  Filesystem mode  " + r.filesystem_mode() + " (active: " + r.filesystem_active() + ")");
        lines.add("  In container     " + r.in_container());
        if (r.fallback_reason() != null) {
            lines.add("  Fallback reason  " + r.fallback_reason());
        }
        return String.join("\n", lines);
    }

    static String format_sandbox_json(SandboxReport r) {
        ObjectMapper mapper = JclaudeMappers.standard();
        ObjectNode root = mapper.createObjectNode();
        root.put("kind", "sandbox");
        root.put("platform", r.platform());
        root.put("is_linux", r.is_linux());
        root.put("is_macos", r.is_macos());
        if (r.is_macos()) {
            root.put("mode", "no-op");
        } else if (r.is_linux()) {
            root.put("mode", "linux");
        } else {
            root.put("mode", "unsupported");
        }
        ArrayNode tools = root.putArray("tools_available");
        for (String t : r.tools_available()) {
            tools.add(t);
        }
        root.put("enabled", r.enabled());
        root.put("active", r.active());
        ObjectNode namespace = root.putObject("namespace");
        namespace.put("supported", r.namespace_supported());
        namespace.put("active", r.namespace_active());
        ObjectNode network = root.putObject("network");
        network.put("supported", r.network_supported());
        network.put("active", r.network_active());
        ObjectNode fs = root.putObject("filesystem");
        fs.put("mode", r.filesystem_mode());
        fs.put("active", r.filesystem_active());
        root.put("in_container", r.in_container());
        ArrayNode markers = root.putArray("container_markers");
        for (String marker : r.container_markers()) {
            markers.add(marker);
        }
        if (r.fallback_reason() != null) {
            root.put("fallback_reason", r.fallback_reason());
        } else {
            root.putNull("fallback_reason");
        }
        return root.toPrettyString();
    }

    private static boolean is_linux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static boolean is_macos() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    private static boolean command_exists(String command) {
        String path_env = System.getenv("PATH");
        if (path_env == null) {
            return false;
        }
        for (String dir : path_env.split(File.pathSeparator)) {
            if (new File(dir, command).canExecute()) {
                return true;
            }
        }
        return false;
    }

    /** Internal report shape; exposed package-private for tests. */
    record SandboxReport(
            String platform,
            boolean is_macos,
            boolean is_linux,
            List<String> tools_available,
            boolean enabled,
            boolean active,
            boolean namespace_supported,
            boolean namespace_active,
            boolean network_supported,
            boolean network_active,
            String filesystem_mode,
            boolean filesystem_active,
            boolean in_container,
            List<String> container_markers,
            String fallback_reason) {
        SandboxReport {
            tools_available = List.copyOf(tools_available);
            container_markers = List.copyOf(container_markers);
        }
    }
}
