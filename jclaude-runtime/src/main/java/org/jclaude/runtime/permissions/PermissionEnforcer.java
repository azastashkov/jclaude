package org.jclaude.runtime.permissions;

import java.util.Optional;
import java.util.Set;

/**
 * Permission enforcement layer that gates tool execution based on the active {@link
 * PermissionPolicy}.
 */
public final class PermissionEnforcer {
    private static final Set<String> READ_ONLY_TOKENS = Set.of(
            "cat",
            "head",
            "tail",
            "less",
            "more",
            "wc",
            "ls",
            "find",
            "grep",
            "rg",
            "awk",
            "sed",
            "echo",
            "printf",
            "which",
            "where",
            "whoami",
            "pwd",
            "env",
            "printenv",
            "date",
            "cal",
            "df",
            "du",
            "free",
            "uptime",
            "uname",
            "file",
            "stat",
            "diff",
            "sort",
            "uniq",
            "tr",
            "cut",
            "paste",
            "tee",
            "xargs",
            "test",
            "true",
            "false",
            "type",
            "readlink",
            "realpath",
            "basename",
            "dirname",
            "sha256sum",
            "md5sum",
            "b3sum",
            "xxd",
            "hexdump",
            "od",
            "strings",
            "tree",
            "jq",
            "yq",
            "python3",
            "python",
            "node",
            "ruby",
            "cargo",
            "rustc",
            "git",
            "gh");

    private final PermissionPolicy policy;

    public PermissionEnforcer(PermissionPolicy policy) {
        this.policy = policy;
    }

    /**
     * Check whether a tool can be executed under the current permission policy. Auto-denies when
     * prompting is required but no prompter is provided.
     */
    public EnforcementResult check(String tool_name, String input) {
        if (policy.active_mode() == PermissionMode.PROMPT) {
            return new EnforcementResult.Allowed();
        }

        PermissionOutcome outcome = policy.authorize(tool_name, input, Optional.empty());

        if (outcome instanceof PermissionOutcome.Allow) {
            return new EnforcementResult.Allowed();
        }
        if (outcome instanceof PermissionOutcome.Deny deny) {
            PermissionMode active = policy.active_mode();
            PermissionMode required = policy.required_mode_for(tool_name);
            return new EnforcementResult.Denied(tool_name, active.as_str(), required.as_str(), deny.reason());
        }
        throw new IllegalStateException("Unhandled permission outcome: " + outcome);
    }

    public boolean is_allowed(String tool_name, String input) {
        return check(tool_name, input) instanceof EnforcementResult.Allowed;
    }

    /**
     * Check permission with an explicitly provided required mode. Used when the required mode is
     * determined dynamically (e.g., bash command classification).
     */
    public EnforcementResult check_with_required_mode(String tool_name, String input, PermissionMode required_mode) {
        if (policy.active_mode() == PermissionMode.PROMPT) {
            return new EnforcementResult.Allowed();
        }

        PermissionMode active = policy.active_mode();
        if (active.compareTo(required_mode) >= 0) {
            return new EnforcementResult.Allowed();
        }

        return new EnforcementResult.Denied(
                tool_name,
                active.as_str(),
                required_mode.as_str(),
                String.format(
                        "'%s' with input '%s' requires '%s' permission, but current mode is '%s'",
                        tool_name, input, required_mode.as_str(), active.as_str()));
    }

    public PermissionMode active_mode() {
        return policy.active_mode();
    }

    /** Classify a file operation against workspace boundaries. */
    public EnforcementResult check_file_write(String path, String workspace_root) {
        PermissionMode mode = policy.active_mode();

        return switch (mode) {
            case READ_ONLY -> new EnforcementResult.Denied(
                    "write_file",
                    mode.as_str(),
                    PermissionMode.WORKSPACE_WRITE.as_str(),
                    String.format("file writes are not allowed in '%s' mode", mode.as_str()));
            case WORKSPACE_WRITE -> {
                if (is_within_workspace(path, workspace_root)) {
                    yield new EnforcementResult.Allowed();
                }
                yield new EnforcementResult.Denied(
                        "write_file",
                        mode.as_str(),
                        PermissionMode.DANGER_FULL_ACCESS.as_str(),
                        String.format("path '%s' is outside workspace root '%s'", path, workspace_root));
            }
            case ALLOW, DANGER_FULL_ACCESS -> new EnforcementResult.Allowed();
            case PROMPT -> new EnforcementResult.Denied(
                    "write_file",
                    mode.as_str(),
                    PermissionMode.WORKSPACE_WRITE.as_str(),
                    "file write requires confirmation in prompt mode");
        };
    }

    /** Check if a bash command should be allowed based on current mode. */
    public EnforcementResult check_bash(String command) {
        PermissionMode mode = policy.active_mode();

        return switch (mode) {
            case READ_ONLY -> {
                if (is_read_only_command(command)) {
                    yield new EnforcementResult.Allowed();
                }
                yield new EnforcementResult.Denied(
                        "bash",
                        mode.as_str(),
                        PermissionMode.WORKSPACE_WRITE.as_str(),
                        String.format("command may modify state; not allowed in '%s' mode", mode.as_str()));
            }
            case PROMPT -> new EnforcementResult.Denied(
                    "bash",
                    mode.as_str(),
                    PermissionMode.DANGER_FULL_ACCESS.as_str(),
                    "bash requires confirmation in prompt mode");
            case WORKSPACE_WRITE, ALLOW, DANGER_FULL_ACCESS -> new EnforcementResult.Allowed();
        };
    }

    /** Simple workspace boundary check via string prefix. */
    static boolean is_within_workspace(String path, String workspace_root) {
        String normalized = path.startsWith("/") ? path : workspace_root + "/" + path;
        String root = workspace_root.endsWith("/") ? workspace_root : workspace_root + "/";

        String trimmed = workspace_root;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return normalized.startsWith(root) || normalized.equals(trimmed);
    }

    /** Conservative heuristic: is this bash command read-only? */
    static boolean is_read_only_command(String command) {
        String[] tokens = command.trim().split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty()) {
            return false;
        }
        String first = tokens[0];
        int slash = first.lastIndexOf('/');
        String firstToken = slash >= 0 ? first.substring(slash + 1) : first;

        if (!READ_ONLY_TOKENS.contains(firstToken)) {
            return false;
        }
        return !command.contains("-i ")
                && !command.contains("--in-place")
                && !command.contains(" > ")
                && !command.contains(" >> ");
    }
}
