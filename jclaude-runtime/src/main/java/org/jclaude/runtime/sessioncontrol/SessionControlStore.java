package org.jclaude.runtime.sessioncontrol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Per-worktree session store namespacing files by workspace fingerprint. */
public final class SessionControlStore {

    public static final String PRIMARY_SESSION_EXTENSION = "json";

    private final Path sessions_root;
    private final Path workspace_root;

    private SessionControlStore(Path sessions_root, Path workspace_root) {
        this.sessions_root = sessions_root;
        this.workspace_root = workspace_root;
    }

    public static SessionControlStore from_cwd(Path cwd) {
        Path canonical;
        try {
            canonical = cwd.toRealPath();
        } catch (IOException e) {
            canonical = cwd.toAbsolutePath().normalize();
        }
        Path sessions_root = canonical.resolve(".claw").resolve("sessions").resolve(workspace_fingerprint(canonical));
        try {
            Files.createDirectories(sessions_root);
        } catch (IOException e) {
            throw new SessionControlError("failed to create sessions root", e);
        }
        return new SessionControlStore(sessions_root, canonical);
    }

    public static SessionControlStore from_data_dir(Path data_dir, Path workspace_root) {
        Path canonical;
        try {
            canonical = workspace_root.toRealPath();
        } catch (IOException e) {
            canonical = workspace_root.toAbsolutePath().normalize();
        }
        Path sessions_root = data_dir.resolve("sessions").resolve(workspace_fingerprint(canonical));
        try {
            Files.createDirectories(sessions_root);
        } catch (IOException e) {
            throw new SessionControlError("failed to create sessions root", e);
        }
        return new SessionControlStore(sessions_root, canonical);
    }

    public Path sessions_dir() {
        return sessions_root;
    }

    public Path workspace_root() {
        return workspace_root;
    }

    public SessionHandle create_handle(String session_id) {
        return new SessionHandle(session_id, sessions_root.resolve(session_id + "." + PRIMARY_SESSION_EXTENSION));
    }

    public SessionHandle resolve_reference(String reference) {
        if (is_session_reference_alias(reference)) {
            return latest_session();
        }
        return create_handle(reference);
    }

    public SessionHandle latest_session() {
        List<SessionHandle> all = list_sessions();
        if (all.isEmpty()) {
            throw new SessionControlError("no sessions available in store");
        }
        return all.get(0);
    }

    public List<SessionHandle> list_sessions() {
        List<SessionHandle> out = new ArrayList<>();
        if (!Files.isDirectory(sessions_root)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessions_root, "*." + PRIMARY_SESSION_EXTENSION)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String id = name.substring(0, name.length() - PRIMARY_SESSION_EXTENSION.length() - 1);
                out.add(new SessionHandle(id, p));
            }
        } catch (IOException e) {
            throw new SessionControlError("failed to list sessions", e);
        }
        out.sort(Comparator.comparingLong((SessionHandle h) -> last_modified(h.path()))
                .reversed());
        return out;
    }

    public static boolean is_session_reference_alias(String reference) {
        return reference.equals("@latest") || reference.equals("latest");
    }

    private static long last_modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String workspace_fingerprint(Path workspace) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(workspace.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
