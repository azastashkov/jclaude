package org.jclaude.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Static helpers used by {@code scripts/run_mock_parity_diff.py} to extract command, tool, and
 * bootstrap manifests from the upstream Claude Code TypeScript sources. Direct port of Rust's
 * {@code compat_harness::lib} module.
 */
public final class CompatHarness {

    private CompatHarness() {}

    public static ExtractedManifest extract_manifest(UpstreamPaths paths) throws IOException {
        String commands_source = Files.readString(paths.commands_path());
        String tools_source = Files.readString(paths.tools_path());
        String cli_source = Files.readString(paths.cli_path());
        return new ExtractedManifest(
                extract_commands(commands_source), extract_tools(tools_source), extract_bootstrap_plan(cli_source));
    }

    public static CommandRegistry extract_commands(String source) {
        List<CommandManifestEntry> entries = new ArrayList<>();
        boolean in_internal_block = false;
        for (String raw_line : source.split("\\R", -1)) {
            String line = raw_line.trim();
            if (line.startsWith("export const INTERNAL_ONLY_COMMANDS = [")) {
                in_internal_block = true;
                continue;
            }
            if (in_internal_block) {
                if (line.startsWith("]")) {
                    in_internal_block = false;
                    continue;
                }
                String name = first_identifier(line);
                if (name != null) {
                    entries.add(new CommandManifestEntry(name, CommandSource.INTERNAL_ONLY));
                }
                continue;
            }
            if (line.startsWith("import ")) {
                for (String imported : imported_symbols(line)) {
                    entries.add(new CommandManifestEntry(imported, CommandSource.BUILTIN));
                }
            }
            if (line.contains("feature('") && line.contains("./commands/")) {
                String name = first_assignment_identifier(line);
                if (name != null) {
                    entries.add(new CommandManifestEntry(name, CommandSource.FEATURE_GATED));
                }
            }
        }
        return new CommandRegistry(dedupe_commands(entries));
    }

    public static ToolRegistry extract_tools(String source) {
        List<ToolManifestEntry> entries = new ArrayList<>();
        for (String raw_line : source.split("\\R", -1)) {
            String line = raw_line.trim();
            if (line.startsWith("import ") && line.contains("./tools/")) {
                for (String imported : imported_symbols(line)) {
                    if (imported.endsWith("Tool")) {
                        entries.add(new ToolManifestEntry(imported, ToolSource.BASE));
                    }
                }
            }
            if (line.contains("feature('") && line.contains("Tool")) {
                String name = first_assignment_identifier(line);
                if (name != null && (name.endsWith("Tool") || name.endsWith("Tools"))) {
                    entries.add(new ToolManifestEntry(name, ToolSource.CONDITIONAL));
                }
            }
        }
        return new ToolRegistry(dedupe_tools(entries));
    }

    public static BootstrapPlan extract_bootstrap_plan(String source) {
        List<BootstrapPhase> phases = new ArrayList<>();
        phases.add(BootstrapPhase.CLI_ENTRY);
        if (source.contains("--version")) phases.add(BootstrapPhase.FAST_PATH_VERSION);
        if (source.contains("startupProfiler")) phases.add(BootstrapPhase.STARTUP_PROFILER);
        if (source.contains("--dump-system-prompt")) phases.add(BootstrapPhase.SYSTEM_PROMPT_FAST_PATH);
        if (source.contains("--claude-in-chrome-mcp")) phases.add(BootstrapPhase.CHROME_MCP_FAST_PATH);
        if (source.contains("--daemon-worker")) phases.add(BootstrapPhase.DAEMON_WORKER_FAST_PATH);
        if (source.contains("remote-control")) phases.add(BootstrapPhase.BRIDGE_FAST_PATH);
        if (source.contains("args[0] === 'daemon'")) phases.add(BootstrapPhase.DAEMON_FAST_PATH);
        if (source.contains("args[0] === 'ps'") || source.contains("args.includes('--bg')")) {
            phases.add(BootstrapPhase.BACKGROUND_SESSION_FAST_PATH);
        }
        if (source.contains("args[0] === 'new' || args[0] === 'list' || args[0] === 'reply'")) {
            phases.add(BootstrapPhase.TEMPLATE_FAST_PATH);
        }
        if (source.contains("environment-runner")) phases.add(BootstrapPhase.ENVIRONMENT_RUNNER_FAST_PATH);
        phases.add(BootstrapPhase.MAIN_RUNTIME);
        return BootstrapPlan.from_phases(phases);
    }

    static List<String> imported_symbols(String line) {
        if (!line.startsWith("import ")) {
            return List.of();
        }
        String after_import = line.substring("import ".length());
        int from_idx = after_import.indexOf(" from ");
        String before_from = (from_idx == -1 ? after_import : after_import.substring(0, from_idx)).trim();
        if (before_from.startsWith("{")) {
            String inner = before_from.replaceAll("[{}]", "");
            List<String> result = new ArrayList<>();
            for (String part : inner.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] words = trimmed.split("\\s+");
                if (words.length > 0) {
                    result.add(words[0]);
                }
            }
            return result;
        }
        String first = before_from.split(",")[0].trim();
        if (first.isEmpty()) {
            return List.of();
        }
        return List.of(first);
    }

    static String first_assignment_identifier(String line) {
        String trimmed = line.stripLeading();
        int eq = trimmed.indexOf('=');
        String candidate = eq == -1 ? trimmed : trimmed.substring(0, eq);
        return first_identifier(candidate.trim());
    }

    static String first_identifier(String line) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            boolean is_id = Character.isLetterOrDigit(ch) || ch == '_' || ch == '-';
            if (is_id) {
                out.append(ch);
            } else if (out.length() > 0) {
                break;
            }
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static List<CommandManifestEntry> dedupe_commands(List<CommandManifestEntry> entries) {
        List<CommandManifestEntry> deduped = new ArrayList<>();
        for (CommandManifestEntry entry : entries) {
            boolean exists = deduped.stream()
                    .anyMatch(seen -> seen.name().equals(entry.name()) && seen.source() == entry.source());
            if (!exists) {
                deduped.add(entry);
            }
        }
        return deduped;
    }

    private static List<ToolManifestEntry> dedupe_tools(List<ToolManifestEntry> entries) {
        List<ToolManifestEntry> deduped = new ArrayList<>();
        for (ToolManifestEntry entry : entries) {
            boolean exists = deduped.stream()
                    .anyMatch(seen -> seen.name().equals(entry.name()) && seen.source() == entry.source());
            if (!exists) {
                deduped.add(entry);
            }
        }
        return deduped;
    }
}
