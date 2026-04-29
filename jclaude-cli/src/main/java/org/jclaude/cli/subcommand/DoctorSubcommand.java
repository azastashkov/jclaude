package org.jclaude.cli.subcommand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.cli.OutputFormat;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code jclaude doctor} — health check that prints OS info, Java version, Gradle build version,
 * Ollama reachability, ANTHROPIC_API_KEY presence (without echoing value), and settings file
 * paths. Mirrors the Rust {@code handle_doctor_command} dispatcher.
 */
@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Health check: report OS, Java, providers, and settings.")
public final class DoctorSubcommand implements Callable<Integer> {

    @Option(
            names = {"--output-format"},
            description = "Output format: text or json (default: ${DEFAULT-VALUE}).",
            defaultValue = "text")
    private String output_format;

    @Override
    public Integer call() {
        OutputFormat fmt = OutputFormat.parse(output_format);
        DoctorReport report = collect();
        if (fmt == OutputFormat.JSON) {
            System.out.println(format_doctor_json(report));
        } else {
            System.out.println(format_doctor_text(report));
        }
        return 0;
    }

    static DoctorReport collect() {
        String os_name = System.getProperty("os.name", "unknown");
        String os_arch = System.getProperty("os.arch", "unknown");
        String os_version = System.getProperty("os.version", "unknown");
        String java_version = System.getProperty("java.version", "unknown");
        String java_vendor = System.getProperty("java.vendor", "unknown");
        String java_home = System.getProperty("java.home", null);

        boolean anthropic_api_key_present = present_env("ANTHROPIC_API_KEY");
        boolean anthropic_auth_token_present = present_env("ANTHROPIC_AUTH_TOKEN");
        boolean ollama_reachable = is_ollama_reachable();

        // Settings paths the CLI consults (resolved via JCLAUDE_CONFIG_HOME or ~/.jclaude).
        List<Path> settings_paths = resolve_settings_paths();

        String gradle_version = read_resource_property("/jclaude-build.properties", "version");

        return new DoctorReport(
                os_name,
                os_arch,
                os_version,
                java_version,
                java_vendor,
                java_home,
                gradle_version,
                anthropic_api_key_present,
                anthropic_auth_token_present,
                ollama_reachable,
                settings_paths);
    }

    static String format_doctor_text(DoctorReport r) {
        List<String> lines = new ArrayList<>();
        lines.add("Doctor");
        lines.add("  OS               " + r.os_name() + " " + r.os_version() + " (" + r.os_arch() + ")");
        lines.add("  Java             " + r.java_version() + " (" + r.java_vendor() + ")");
        if (r.java_home() != null) {
            lines.add("  Java home        " + r.java_home());
        }
        if (r.gradle_version() != null) {
            lines.add("  Build version    " + r.gradle_version());
        }
        lines.add("  ANTHROPIC_API_KEY     " + (r.anthropic_api_key_present() ? "present" : "missing"));
        lines.add("  ANTHROPIC_AUTH_TOKEN  " + (r.anthropic_auth_token_present() ? "present" : "missing"));
        lines.add("  Ollama (127.0.0.1:11434)  " + (r.ollama_reachable() ? "reachable" : "unreachable"));
        if (r.settings_paths().isEmpty()) {
            lines.add("  Settings paths   (none discovered)");
        } else {
            lines.add("  Settings paths:");
            for (Path p : r.settings_paths()) {
                String exists_marker = Files.exists(p) ? " [exists]" : "";
                lines.add("    " + p + exists_marker);
            }
        }
        return String.join("\n", lines);
    }

    static String format_doctor_json(DoctorReport r) {
        ObjectMapper mapper = JclaudeMappers.standard();
        ObjectNode root = mapper.createObjectNode();
        root.put("kind", "doctor");

        ObjectNode os = root.putObject("os");
        os.put("name", r.os_name());
        os.put("arch", r.os_arch());
        os.put("version", r.os_version());

        ObjectNode java = root.putObject("java");
        java.put("version", r.java_version());
        java.put("vendor", r.java_vendor());
        if (r.java_home() != null) {
            java.put("home", r.java_home());
        } else {
            java.putNull("home");
        }

        if (r.gradle_version() != null) {
            root.put("build_version", r.gradle_version());
        } else {
            root.putNull("build_version");
        }

        ObjectNode auth = root.putObject("auth");
        auth.put("anthropic_api_key_present", r.anthropic_api_key_present());
        auth.put("anthropic_auth_token_present", r.anthropic_auth_token_present());

        ObjectNode providers = root.putObject("providers");
        providers.put("ollama_reachable", r.ollama_reachable());

        ArrayNode paths = root.putArray("settings_paths");
        for (Path p : r.settings_paths()) {
            ObjectNode entry = paths.addObject();
            entry.put("path", p.toString());
            entry.put("exists", Files.exists(p));
        }
        return root.toPrettyString();
    }

    private static boolean present_env(String key) {
        String value = System.getenv(key);
        return value != null && !value.isBlank();
    }

    private static boolean is_ollama_reachable() {
        try {
            URI uri = URI.create("http://127.0.0.1:11434/api/tags");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(200);
            conn.setReadTimeout(400);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception ignored) {
            return false;
        }
    }

    static List<Path> resolve_settings_paths() {
        List<Path> out = new ArrayList<>();
        String override = System.getenv("JCLAUDE_CONFIG_HOME");
        if (override != null && !override.isBlank()) {
            out.add(Paths.get(override).resolve("settings.json"));
            return out;
        }
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) {
            home = System.getProperty("user.home");
        }
        if (home != null && !home.isBlank()) {
            out.add(Paths.get(home).resolve(".jclaude").resolve("settings.json"));
        }
        return out;
    }

    private static String read_resource_property(String resource, String key) {
        try (var in = DoctorSubcommand.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            java.util.Properties props = new java.util.Properties();
            props.load(in);
            return props.getProperty(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Internal report shape; exposed package-private for tests. */
    record DoctorReport(
            String os_name,
            String os_arch,
            String os_version,
            String java_version,
            String java_vendor,
            String java_home,
            String gradle_version,
            boolean anthropic_api_key_present,
            boolean anthropic_auth_token_present,
            boolean ollama_reachable,
            List<Path> settings_paths) {
        DoctorReport {
            settings_paths = List.copyOf(settings_paths);
            if (os_name != null) {
                os_name = os_name.toLowerCase(Locale.ROOT).contains("mac") ? os_name : os_name;
            }
        }
    }
}
