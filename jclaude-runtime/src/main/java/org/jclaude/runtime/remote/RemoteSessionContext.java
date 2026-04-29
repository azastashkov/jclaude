package org.jclaude.runtime.remote;

import java.util.Map;
import java.util.Optional;

/** Remote session context derived from environment. */
public record RemoteSessionContext(boolean enabled, Optional<String> session_id, String base_url) {

    public RemoteSessionContext {
        session_id = session_id == null ? Optional.empty() : session_id;
    }

    public static RemoteSessionContext from_env() {
        return from_env_map(System.getenv());
    }

    public static RemoteSessionContext from_env_map(Map<String, String> env_map) {
        boolean enabled = Remote.env_truthy(env_map.get("CLAUDE_CODE_REMOTE"));
        String session_raw = env_map.get("CLAUDE_CODE_REMOTE_SESSION_ID");
        Optional<String> session_id =
                session_raw == null || session_raw.isEmpty() ? Optional.empty() : Optional.of(session_raw);
        String base = env_map.get("ANTHROPIC_BASE_URL");
        if (base == null || base.isEmpty()) {
            base = Remote.DEFAULT_REMOTE_BASE_URL;
        }
        return new RemoteSessionContext(enabled, session_id, base);
    }
}
