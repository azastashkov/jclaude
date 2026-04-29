package org.jclaude.runtime.bash.validation;

import java.util.List;

/** Warn if a command looks destructive. */
public final class DestructiveCommandWarning {
    private record Pattern(String pattern, String warning) {}

    /** Patterns that indicate potentially destructive commands. */
    static final List<Pattern> DESTRUCTIVE_PATTERNS = List.of(
            new Pattern("rm -rf /", "Recursive forced deletion at root — this will destroy the system"),
            new Pattern("rm -rf ~", "Recursive forced deletion of home directory"),
            new Pattern("rm -rf *", "Recursive forced deletion of all files in current directory"),
            new Pattern("rm -rf .", "Recursive forced deletion of current directory"),
            new Pattern("mkfs", "Filesystem creation will destroy existing data on the device"),
            new Pattern("dd if=", "Direct disk write — can overwrite partitions or devices"),
            new Pattern("> /dev/sd", "Writing to raw disk device"),
            new Pattern("chmod -R 777", "Recursively setting world-writable permissions"),
            new Pattern("chmod -R 000", "Recursively removing all permissions"),
            new Pattern(":(){ :|:& };:", "Fork bomb — will crash the system"));

    /** Commands that are always destructive regardless of arguments. */
    static final List<String> ALWAYS_DESTRUCTIVE_COMMANDS = List.of("shred", "wipefs");

    private DestructiveCommandWarning() {}

    public static ValidationResult check(String command) {
        for (Pattern p : DESTRUCTIVE_PATTERNS) {
            if (command.contains(p.pattern())) {
                return ValidationResult.warn("Destructive command detected: " + p.warning());
            }
        }

        String first = CommandHelpers.extract_first_command(command);
        for (String cmd : ALWAYS_DESTRUCTIVE_COMMANDS) {
            if (first.equals(cmd)) {
                return ValidationResult.warn(
                        String.format("Command '%s' is inherently destructive and may cause data loss", cmd));
            }
        }

        if (command.contains("rm ") && command.contains("-r") && command.contains("-f")) {
            return ValidationResult.warn("Recursive forced deletion detected — verify the target path is correct");
        }

        return ValidationResult.allow();
    }
}
