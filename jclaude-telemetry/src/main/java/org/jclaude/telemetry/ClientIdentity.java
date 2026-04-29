package org.jclaude.telemetry;

/** Client metadata embedded in Anthropic-bound HTTP requests. Mirrors Rust's ClientIdentity. */
public record ClientIdentity(String app_name, String app_version, String runtime) {

    public static final String DEFAULT_RUNTIME = "java";
    public static final String DEFAULT_APP_NAME = "claude-code";

    public ClientIdentity {
        if (app_name == null) app_name = DEFAULT_APP_NAME;
        if (app_version == null) app_version = "0.0.0";
        if (runtime == null) runtime = DEFAULT_RUNTIME;
    }

    public static ClientIdentity of(String app_name, String app_version) {
        return new ClientIdentity(app_name, app_version, DEFAULT_RUNTIME);
    }

    public ClientIdentity with_runtime(String runtime) {
        return new ClientIdentity(app_name, app_version, runtime);
    }

    public String user_agent() {
        return app_name + "/" + app_version;
    }

    public static ClientIdentity defaults() {
        return new ClientIdentity(DEFAULT_APP_NAME, "0.0.0", DEFAULT_RUNTIME);
    }
}
