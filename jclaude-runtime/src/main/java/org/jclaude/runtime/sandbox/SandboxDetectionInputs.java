package org.jclaude.runtime.sandbox;

import java.util.List;
import java.util.Map;

/** Detection inputs for the container environment detector. */
public record SandboxDetectionInputs(
        List<Map.Entry<String, String>> env_pairs,
        boolean dockerenv_exists,
        boolean containerenv_exists,
        String proc_1_cgroup) {

    public SandboxDetectionInputs {
        env_pairs = List.copyOf(env_pairs);
    }
}
