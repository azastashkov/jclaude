package org.jclaude.runtime.bash.validation;

import java.util.List;
import org.jclaude.runtime.permissions.PermissionMode;

/** Validate that a command is allowed under read-only mode. */
public final class ReadOnlyValidation {
    /** Commands that perform write operations and should be blocked in read-only mode. */
    static final List<String> WRITE_COMMANDS = List.of(
            "cp",
            "mv",
            "rm",
            "mkdir",
            "rmdir",
            "touch",
            "chmod",
            "chown",
            "chgrp",
            "ln",
            "install",
            "tee",
            "truncate",
            "shred",
            "mkfifo",
            "mknod",
            "dd");

    /** Commands that modify system state and should be blocked in read-only mode. */
    static final List<String> STATE_MODIFYING_COMMANDS = List.of(
            "apt",
            "apt-get",
            "yum",
            "dnf",
            "pacman",
            "brew",
            "pip",
            "pip3",
            "npm",
            "yarn",
            "pnpm",
            "bun",
            "cargo",
            "gem",
            "go",
            "rustup",
            "docker",
            "systemctl",
            "service",
            "mount",
            "umount",
            "kill",
            "pkill",
            "killall",
            "reboot",
            "shutdown",
            "halt",
            "poweroff",
            "useradd",
            "userdel",
            "usermod",
            "groupadd",
            "groupdel",
            "crontab",
            "at");

    /** Shell redirection operators that indicate writes. */
    static final List<String> WRITE_REDIRECTIONS = List.of(">", ">>", ">&");

    /** Git subcommands that are read-only safe. */
    static final List<String> GIT_READ_ONLY_SUBCOMMANDS = List.of(
            "status",
            "log",
            "diff",
            "show",
            "branch",
            "tag",
            "stash",
            "remote",
            "fetch",
            "ls-files",
            "ls-tree",
            "cat-file",
            "rev-parse",
            "describe",
            "shortlog",
            "blame",
            "bisect",
            "reflog",
            "config");

    private ReadOnlyValidation() {}

    public static ValidationResult validate(String command, PermissionMode mode) {
        if (mode != PermissionMode.READ_ONLY) {
            return ValidationResult.allow();
        }

        String firstCommand = CommandHelpers.extract_first_command(command);

        for (String writeCmd : WRITE_COMMANDS) {
            if (firstCommand.equals(writeCmd)) {
                return ValidationResult.block(String.format(
                        "Command '%s' modifies the filesystem and is not allowed in read-only mode", writeCmd));
            }
        }

        for (String stateCmd : STATE_MODIFYING_COMMANDS) {
            if (firstCommand.equals(stateCmd)) {
                return ValidationResult.block(String.format(
                        "Command '%s' modifies system state and is not allowed in read-only mode", stateCmd));
            }
        }

        if (firstCommand.equals("sudo")) {
            String inner = CommandHelpers.extract_sudo_inner(command);
            if (!inner.isEmpty()) {
                ValidationResult innerResult = validate(inner, mode);
                if (!(innerResult instanceof ValidationResult.Allow)) {
                    return innerResult;
                }
            }
        }

        for (String redir : WRITE_REDIRECTIONS) {
            if (command.contains(redir)) {
                return ValidationResult.block(String.format(
                        "Command contains write redirection '%s' which is not allowed in read-only mode", redir));
            }
        }

        if (firstCommand.equals("git")) {
            return validate_git_read_only(command);
        }

        return ValidationResult.allow();
    }

    static ValidationResult validate_git_read_only(String command) {
        String[] parts = command.trim().split("\\s+");
        String subcommand = null;
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].startsWith("-")) {
                subcommand = parts[i];
                break;
            }
        }

        if (subcommand == null) {
            return ValidationResult.allow();
        }
        if (GIT_READ_ONLY_SUBCOMMANDS.contains(subcommand)) {
            return ValidationResult.allow();
        }
        return ValidationResult.block(String.format(
                "Git subcommand '%s' modifies repository state and is not allowed in read-only mode", subcommand));
    }
}
