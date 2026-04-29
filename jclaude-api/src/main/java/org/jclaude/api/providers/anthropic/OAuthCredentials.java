package org.jclaude.api.providers.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.jclaude.api.json.JclaudeMappers;

/**
 * Loader for saved Anthropic OAuth credentials.
 *
 * <p>Java port of the saved-token discovery in the Rust {@code runtime} crate
 * ({@code load_oauth_credentials}). The credentials live in
 * {@code <home>/.claude/credentials.json} (or wherever
 * {@code CLAUDE_CONFIG_HOME} / {@code CLAW_CONFIG_HOME} points). The file is a
 * JSON object whose {@code oauth.access_token} field carries the bearer token
 * we want to surface.
 */
public final class OAuthCredentials {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private OAuthCredentials() {}

    /**
     * Resolve the credentials home dir respecting {@code CLAUDE_CONFIG_HOME}
     * (or its legacy alias {@code CLAW_CONFIG_HOME}). Falls back to
     * {@code ~/.claude}.
     */
    public static Path credentials_home_dir() {
        String override = System.getenv("CLAUDE_CONFIG_HOME");
        if (override == null || override.isEmpty()) {
            override = System.getenv("CLAW_CONFIG_HOME");
        }
        if (override != null && !override.isEmpty()) {
            return Paths.get(override);
        }
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) {
            home = System.getenv("USERPROFILE");
        }
        if (home == null || home.isEmpty()) {
            home = System.getProperty("user.home", ".");
        }
        return Paths.get(home, ".claude");
    }

    /** Path to the credentials.json file under the resolved home dir. */
    public static Path credentials_path() {
        return credentials_home_dir().resolve("credentials.json");
    }

    /**
     * Read the saved OAuth bearer token, or {@link Optional#empty()} when no
     * credentials file exists or no {@code access_token} is recorded.
     */
    public static Optional<String> load_access_token() {
        Path path = credentials_path();
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(content);
            JsonNode oauth = root.get("oauth");
            if (oauth == null || oauth.isNull()) {
                return Optional.empty();
            }
            JsonNode access = oauth.get("access_token");
            if (access == null || !access.isTextual()) {
                return Optional.empty();
            }
            String token = access.asText();
            if (token.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(token);
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }
}
