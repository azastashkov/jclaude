package org.jclaude.compat;

/** Bootstrap phase token recognized by {@link CompatHarness#extract_bootstrap_plan(String)}. */
public enum BootstrapPhase {
    CLI_ENTRY,
    FAST_PATH_VERSION,
    STARTUP_PROFILER,
    SYSTEM_PROMPT_FAST_PATH,
    CHROME_MCP_FAST_PATH,
    DAEMON_WORKER_FAST_PATH,
    BRIDGE_FAST_PATH,
    DAEMON_FAST_PATH,
    BACKGROUND_SESSION_FAST_PATH,
    TEMPLATE_FAST_PATH,
    ENVIRONMENT_RUNNER_FAST_PATH,
    MAIN_RUNTIME
}
