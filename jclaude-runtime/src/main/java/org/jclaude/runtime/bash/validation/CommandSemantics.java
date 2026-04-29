package org.jclaude.runtime.bash.validation;

import java.util.List;

/** Classify the semantic intent of a bash command. */
public final class CommandSemantics {
    /** Commands that are read-only (no filesystem or state modification). */
    static final List<String> SEMANTIC_READ_ONLY_COMMANDS = List.of(
            "ls",
            "cat",
            "head",
            "tail",
            "less",
            "more",
            "wc",
            "sort",
            "uniq",
            "grep",
            "egrep",
            "fgrep",
            "find",
            "which",
            "whereis",
            "whatis",
            "man",
            "info",
            "file",
            "stat",
            "du",
            "df",
            "free",
            "uptime",
            "uname",
            "hostname",
            "whoami",
            "id",
            "groups",
            "env",
            "printenv",
            "echo",
            "printf",
            "date",
            "cal",
            "bc",
            "expr",
            "test",
            "true",
            "false",
            "pwd",
            "tree",
            "diff",
            "cmp",
            "md5sum",
            "sha256sum",
            "sha1sum",
            "xxd",
            "od",
            "hexdump",
            "strings",
            "readlink",
            "realpath",
            "basename",
            "dirname",
            "seq",
            "yes",
            "tput",
            "column",
            "jq",
            "yq",
            "xargs",
            "tr",
            "cut",
            "paste",
            "awk",
            "sed");

    /** Commands that perform network operations. */
    static final List<String> NETWORK_COMMANDS = List.of(
            "curl",
            "wget",
            "ssh",
            "scp",
            "rsync",
            "ftp",
            "sftp",
            "nc",
            "ncat",
            "telnet",
            "ping",
            "traceroute",
            "dig",
            "nslookup",
            "host",
            "whois",
            "ifconfig",
            "ip",
            "netstat",
            "ss",
            "nmap");

    /** Commands that manage processes. */
    static final List<String> PROCESS_COMMANDS = List.of(
            "kill", "pkill", "killall", "ps", "top", "htop", "bg", "fg", "jobs", "nohup", "disown", "wait", "nice",
            "renice");

    /** Commands that manage packages. */
    static final List<String> PACKAGE_COMMANDS = List.of(
            "apt", "apt-get", "yum", "dnf", "pacman", "brew", "pip", "pip3", "npm", "yarn", "pnpm", "bun", "cargo",
            "gem", "go", "rustup", "snap", "flatpak");

    /** Commands that require system administrator privileges. */
    static final List<String> SYSTEM_ADMIN_COMMANDS = List.of(
            "sudo",
            "su",
            "chroot",
            "mount",
            "umount",
            "fdisk",
            "parted",
            "lsblk",
            "blkid",
            "systemctl",
            "service",
            "journalctl",
            "dmesg",
            "modprobe",
            "insmod",
            "rmmod",
            "iptables",
            "ufw",
            "firewall-cmd",
            "sysctl",
            "crontab",
            "at",
            "useradd",
            "userdel",
            "usermod",
            "groupadd",
            "groupdel",
            "passwd",
            "visudo");

    private CommandSemantics() {}

    public static CommandIntent classify(String command) {
        String first = CommandHelpers.extract_first_command(command);
        return classify_by_first_command(first, command);
    }

    private static CommandIntent classify_by_first_command(String first, String command) {
        if (SEMANTIC_READ_ONLY_COMMANDS.contains(first)) {
            if ("sed".equals(first) && command.contains(" -i")) {
                return CommandIntent.WRITE;
            }
            return CommandIntent.READ_ONLY;
        }

        if (DestructiveCommandWarning.ALWAYS_DESTRUCTIVE_COMMANDS.contains(first) || "rm".equals(first)) {
            return CommandIntent.DESTRUCTIVE;
        }

        if (ReadOnlyValidation.WRITE_COMMANDS.contains(first)) {
            return CommandIntent.WRITE;
        }

        if (NETWORK_COMMANDS.contains(first)) {
            return CommandIntent.NETWORK;
        }

        if (PROCESS_COMMANDS.contains(first)) {
            return CommandIntent.PROCESS_MANAGEMENT;
        }

        if (PACKAGE_COMMANDS.contains(first)) {
            return CommandIntent.PACKAGE_MANAGEMENT;
        }

        if (SYSTEM_ADMIN_COMMANDS.contains(first)) {
            return CommandIntent.SYSTEM_ADMIN;
        }

        if ("git".equals(first)) {
            return classify_git_command(command);
        }

        return CommandIntent.UNKNOWN;
    }

    private static CommandIntent classify_git_command(String command) {
        String[] parts = command.trim().split("\\s+");
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].startsWith("-")) {
                if (ReadOnlyValidation.GIT_READ_ONLY_SUBCOMMANDS.contains(parts[i])) {
                    return CommandIntent.READ_ONLY;
                }
                return CommandIntent.WRITE;
            }
        }
        return CommandIntent.WRITE;
    }
}
