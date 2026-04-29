package org.jclaude.runtime.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Git-aware context gathered at startup for injection into the system prompt. */
public record GitContext(Optional<String> branch, List<GitCommitEntry> recent_commits, List<String> staged_files) {

    private static final int MAX_RECENT_COMMITS = 5;

    public GitContext {
        recent_commits = List.copyOf(recent_commits);
        staged_files = List.copyOf(staged_files);
    }

    public static Optional<GitContext> detect(Path cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree");
            pb.directory(cwd.toFile());
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            int exit = p.waitFor();
            if (exit != 0) {
                return Optional.empty();
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }

        return Optional.of(new GitContext(read_branch(cwd), read_recent_commits(cwd), read_staged_files(cwd)));
    }

    public String render() {
        List<String> lines = new ArrayList<>();
        branch.ifPresent(b -> lines.add("Git branch: " + b));

        if (!recent_commits.isEmpty()) {
            lines.add("");
            lines.add("Recent commits:");
            for (GitCommitEntry entry : recent_commits) {
                lines.add("  " + entry.hash() + " " + entry.subject());
            }
        }

        if (!staged_files.isEmpty()) {
            lines.add("");
            lines.add("Staged files:");
            for (String f : staged_files) {
                lines.add("  " + f);
            }
        }
        return String.join("\n", lines);
    }

    private static Optional<String> read_branch(Path cwd) {
        Optional<String> out = run(cwd, "rev-parse", "--abbrev-ref", "HEAD");
        return out.map(String::trim).filter(s -> !s.isEmpty() && !s.equals("HEAD"));
    }

    private static List<GitCommitEntry> read_recent_commits(Path cwd) {
        Optional<String> out = run(
                cwd,
                "--no-optional-locks",
                "log",
                "--oneline",
                "-n",
                String.valueOf(MAX_RECENT_COMMITS),
                "--no-decorate");
        if (out.isEmpty()) {
            return List.of();
        }
        List<GitCommitEntry> entries = new ArrayList<>();
        for (String raw : out.get().split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            int idx = line.indexOf(' ');
            if (idx < 0) {
                continue;
            }
            entries.add(new GitCommitEntry(line.substring(0, idx), line.substring(idx + 1)));
        }
        return entries;
    }

    private static List<String> read_staged_files(Path cwd) {
        Optional<String> out = run(cwd, "--no-optional-locks", "diff", "--cached", "--name-only");
        if (out.isEmpty()) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (String raw : out.get().split("\n")) {
            String line = raw.trim();
            if (!line.isEmpty()) {
                entries.add(line);
            }
        }
        return entries;
    }

    private static Optional<String> run(Path cwd, String... args) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        for (String a : args) {
            cmd.add(a);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            if (exit != 0) {
                return Optional.empty();
            }
            return Optional.of(stdout);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }
}
