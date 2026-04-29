package org.jclaude.runtime.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jclaude.runtime.git.GitContext;

/** Project-local context injected into the rendered system prompt. */
public record ProjectContext(
        Path cwd,
        String current_date,
        Optional<String> git_status,
        Optional<String> git_diff,
        Optional<GitContext> git_context,
        List<ContextFile> instruction_files) {

    public ProjectContext {
        git_status = git_status == null ? Optional.empty() : git_status;
        git_diff = git_diff == null ? Optional.empty() : git_diff;
        git_context = git_context == null ? Optional.empty() : git_context;
        instruction_files = List.copyOf(instruction_files);
    }

    public static ProjectContext discover(Path cwd, String current_date) throws IOException {
        return new ProjectContext(
                cwd,
                current_date,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                discover_instruction_files(cwd));
    }

    public static ProjectContext discover_with_git(Path cwd, String current_date) throws IOException {
        return new ProjectContext(
                cwd,
                current_date,
                Optional.ofNullable(read_git_status(cwd)),
                Optional.ofNullable(read_git_diff(cwd)),
                GitContext.detect(cwd),
                discover_instruction_files(cwd));
    }

    private static List<ContextFile> discover_instruction_files(Path cwd) throws IOException {
        List<ContextFile> files = new ArrayList<>();
        for (String name : new String[] {"CLAUDE.md", ".claude/CLAUDE.md", ".clauderules"}) {
            Path candidate = cwd.resolve(name);
            if (Files.isRegularFile(candidate)) {
                String content = Files.readString(candidate, StandardCharsets.UTF_8);
                files.add(new ContextFile(candidate, content));
            }
        }
        return files;
    }

    private static String read_git_status(Path cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "--no-optional-locks", "status", "--short");
            pb.directory(cwd.toFile());
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            if (exit != 0 || out.isBlank()) {
                return null;
            }
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static String read_git_diff(Path cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "--no-optional-locks", "diff", "--stat");
            pb.directory(cwd.toFile());
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            if (exit != 0 || out.isBlank()) {
                return null;
            }
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
