package org.jclaude.runtime.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Manages session file paths under a configurable root directory.
 *
 * <p>By default, sessions live under {@code ~/.jclaude/sessions/}.
 */
public final class SessionStore {

    private static final String DEFAULT_HOME_SUBDIR = ".jclaude";
    private static final String SESSIONS_SUBDIR = "sessions";
    private static final String SESSION_EXTENSION = ".jsonl";

    private final Path root;

    private SessionStore(Path root) {
        this.root = root;
    }

    /** Constructs a session store rooted at {@code <root>/.jclaude/sessions/}. */
    public static SessionStore in_root(Path root) {
        return new SessionStore(root.resolve(DEFAULT_HOME_SUBDIR).resolve(SESSIONS_SUBDIR));
    }

    /**
     * Constructs a session store rooted at the user's home directory
     * ({@code ~/.jclaude/sessions/}).
     */
    public static SessionStore in_home() {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            home = ".";
        }
        return in_root(Paths.get(home));
    }

    /** Returns the directory holding all session files. */
    public Path sessions_dir() {
        return root;
    }

    /** Returns the path the session with {@code session_id} should be persisted to. */
    public Path path_for(String session_id) {
        return root.resolve(session_id + SESSION_EXTENSION);
    }

    /** Lists every session file currently present in the store. */
    public List<Path> list_sessions() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> entries = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        Path name = path.getFileName();
                        return name != null && name.toString().endsWith(SESSION_EXTENSION);
                    })
                    .forEach(entries::add);
            entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return entries;
        } catch (IOException e) {
            throw SessionError.io(e);
        }
    }

    /** Returns the most-recently modified session in the store, if any. */
    public Optional<Path> latest_session() {
        return list_sessions().stream().max(Comparator.comparingLong(path -> {
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }));
    }
}
