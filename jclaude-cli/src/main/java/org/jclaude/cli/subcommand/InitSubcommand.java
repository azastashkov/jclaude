package org.jclaude.cli.subcommand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.cli.OutputFormat;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code jclaude init} — initialise a {@code .jclaude/} workspace alongside a {@code CLAUDE.md}
 * placeholder and append entries to {@code .gitignore}. Mirrors Rust {@code init.rs}.
 */
@Command(
        name = "init",
        mixinStandardHelpOptions = true,
        description = "Initialise a .jclaude/ workspace in the current directory.")
public final class InitSubcommand implements Callable<Integer> {

    private static final String DEFAULT_CLAUDE_MD = "# CLAUDE.md\n\n"
            + "Project context for jclaude. Document conventions, conventions to follow, and any\n"
            + "notes you want the assistant to honor by default.\n";

    private static final String DEFAULT_SETTINGS =
            "{\n  \"model\": \"claude-sonnet-4-6\",\n  \"permissionMode\": \"read-only\"\n}\n";

    private static final List<String> GITIGNORE_LINES =
            List.of("# jclaude — local workspace artifacts", ".jclaude/sessions/", ".jclaude/cache/", ".jclaude/logs/");

    @Option(
            names = {"--output-format"},
            description = "Output format: text or json (default: ${DEFAULT-VALUE}).",
            defaultValue = "text")
    private String output_format;

    @Option(
            names = {"--workspace"},
            description = "Workspace root (default: current working directory).")
    private Path workspace;

    @Override
    public Integer call() {
        OutputFormat fmt = OutputFormat.parse(output_format);
        Path root = workspace == null ? Paths.get("").toAbsolutePath() : workspace.toAbsolutePath();
        try {
            InitReport report = run_init(root);
            if (fmt == OutputFormat.JSON) {
                System.out.println(format_init_json(report));
            } else {
                System.out.println(format_init_text(report));
            }
            return 0;
        } catch (IOException error) {
            System.err.println("error: " + error.getMessage());
            return 1;
        }
    }

    static InitReport run_init(Path workspace) throws IOException {
        Path jclaude_dir = workspace.resolve(".jclaude");
        Path settings_path = jclaude_dir.resolve("settings.json");
        Path claude_md = workspace.resolve("CLAUDE.md");
        Path gitignore = workspace.resolve(".gitignore");

        boolean dir_created = !Files.isDirectory(jclaude_dir);
        Files.createDirectories(jclaude_dir);

        boolean settings_created = !Files.exists(settings_path);
        if (settings_created) {
            Files.writeString(settings_path, DEFAULT_SETTINGS, StandardCharsets.UTF_8);
        }

        boolean claude_md_created = !Files.exists(claude_md);
        if (claude_md_created) {
            Files.writeString(claude_md, DEFAULT_CLAUDE_MD, StandardCharsets.UTF_8);
        }

        boolean gitignore_updated = update_gitignore(gitignore);

        return new InitReport(
                workspace.toString(),
                jclaude_dir.toString(),
                settings_path.toString(),
                claude_md.toString(),
                gitignore.toString(),
                dir_created,
                settings_created,
                claude_md_created,
                gitignore_updated);
    }

    private static boolean update_gitignore(Path gitignore) throws IOException {
        String existing = "";
        if (Files.exists(gitignore)) {
            existing = Files.readString(gitignore, StandardCharsets.UTF_8);
        }
        List<String> existing_lines = new ArrayList<>();
        if (!existing.isEmpty()) {
            for (String line : existing.split("\n", -1)) {
                existing_lines.add(line.trim());
            }
        }
        List<String> to_append = new ArrayList<>();
        for (String entry : GITIGNORE_LINES) {
            if (!existing_lines.contains(entry.trim())) {
                to_append.add(entry);
            }
        }
        if (to_append.isEmpty()) {
            return false;
        }
        StringBuilder appended = new StringBuilder();
        if (!existing.isEmpty() && !existing.endsWith("\n")) {
            appended.append('\n');
        }
        if (!existing.isEmpty()) {
            appended.append('\n');
        }
        appended.append(String.join("\n", to_append));
        appended.append('\n');
        if (Files.exists(gitignore)) {
            Files.writeString(gitignore, appended.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } else {
            Files.writeString(gitignore, appended.toString(), StandardCharsets.UTF_8);
        }
        return true;
    }

    static String format_init_text(InitReport r) {
        List<String> lines = new ArrayList<>();
        lines.add("Initialised jclaude workspace");
        lines.add("  Workspace        " + r.workspace_root());
        lines.add("  .jclaude dir     " + r.jclaude_dir() + (r.created_dir() ? " (created)" : " (already present)"));
        lines.add("  settings.json    " + r.settings_path()
                + (r.created_settings() ? " (created)" : " (already present)"));
        lines.add("  CLAUDE.md        " + r.claude_md_path()
                + (r.created_claude_md() ? " (created)" : " (already present)"));
        lines.add("  .gitignore       " + r.gitignore_path()
                + (r.updated_gitignore() ? " (updated)" : " (already up to date)"));
        return String.join("\n", lines);
    }

    static String format_init_json(InitReport r) {
        ObjectMapper mapper = JclaudeMappers.standard();
        ObjectNode root = mapper.createObjectNode();
        root.put("kind", "init");
        root.put("workspace_root", r.workspace_root());
        root.put("jclaude_dir", r.jclaude_dir());
        root.put("settings_path", r.settings_path());
        root.put("claude_md_path", r.claude_md_path());
        root.put("gitignore_path", r.gitignore_path());

        ObjectNode created = root.putObject("created");
        created.put("jclaude_dir", r.created_dir());
        created.put("settings", r.created_settings());
        created.put("claude_md", r.created_claude_md());

        ObjectNode updated = root.putObject("updated");
        updated.put("gitignore", r.updated_gitignore());

        ArrayNode files = root.putArray("files");
        files.add(r.settings_path());
        files.add(r.claude_md_path());
        files.add(r.gitignore_path());
        return root.toPrettyString();
    }

    /** Internal report shape; exposed package-private for tests. */
    record InitReport(
            String workspace_root,
            String jclaude_dir,
            String settings_path,
            String claude_md_path,
            String gitignore_path,
            boolean created_dir,
            boolean created_settings,
            boolean created_claude_md,
            boolean updated_gitignore) {}
}
