package org.jclaude.runtime.sandbox;

import java.util.List;
import java.util.Map;

/** Built command for spawning under {@code unshare} on Linux. */
public record LinuxSandboxCommand(String program, List<String> args, List<Map.Entry<String, String>> env) {

    public LinuxSandboxCommand {
        args = List.copyOf(args);
        env = List.copyOf(env);
    }
}
