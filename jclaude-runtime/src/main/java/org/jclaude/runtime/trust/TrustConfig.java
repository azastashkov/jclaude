package org.jclaude.runtime.trust;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Trust resolver configuration. */
public final class TrustConfig {

    private final List<TrustAllowlistEntry> allowlisted;
    private final List<Path> denied;
    private final boolean emit_events;

    public TrustConfig() {
        this(new ArrayList<>(), new ArrayList<>(), true);
    }

    public TrustConfig(List<TrustAllowlistEntry> allowlisted, List<Path> denied, boolean emit_events) {
        this.allowlisted = new ArrayList<>(allowlisted);
        this.denied = new ArrayList<>(denied);
        this.emit_events = emit_events;
    }

    public List<TrustAllowlistEntry> allowlisted() {
        return List.copyOf(allowlisted);
    }

    public List<Path> denied() {
        return List.copyOf(denied);
    }

    public boolean emit_events() {
        return emit_events;
    }

    public TrustConfig with_allowlisted(String path) {
        TrustConfig copy = new TrustConfig(allowlisted, denied, emit_events);
        copy.allowlisted.add(TrustAllowlistEntry.of(path));
        return copy;
    }

    public TrustConfig with_allowlisted_entry(TrustAllowlistEntry entry) {
        TrustConfig copy = new TrustConfig(allowlisted, denied, emit_events);
        copy.allowlisted.add(entry);
        return copy;
    }

    public TrustConfig with_denied(String path) {
        TrustConfig copy = new TrustConfig(allowlisted, denied, emit_events);
        copy.denied.add(Paths.get(path));
        return copy;
    }

    public Optional<TrustAllowlistEntry> is_allowlisted(String cwd, String worktree) {
        for (TrustAllowlistEntry entry : allowlisted) {
            if (!pattern_matches(entry.pattern(), cwd)) {
                continue;
            }
            if (entry.worktree_pattern().isPresent()) {
                if (worktree == null) {
                    continue;
                }
                if (!pattern_matches(entry.worktree_pattern().get(), worktree)) {
                    continue;
                }
            }
            return Optional.of(entry);
        }
        return Optional.empty();
    }

    /** Visible for tests; matches the Rust pattern matcher. */
    public static boolean pattern_matches(String pattern, String path) {
        pattern = pattern.trim();
        path = path.trim();

        if (pattern.equals(path)) {
            return true;
        }

        String pattern_n = pattern.replace("//", "/");
        String path_n = path.replace("//", "/");

        if (!pattern_n.contains("*") && !pattern_n.contains("?")) {
            if (path_n.startsWith(pattern_n)) {
                String rest = path_n.substring(pattern_n.length());
                return rest.isEmpty() || rest.startsWith("/");
            }
        }

        if (pattern_n.endsWith("/*")) {
            String prefix = pattern_n.substring(0, pattern_n.length() - 2);
            if (path_n.startsWith(prefix)) {
                String rest = path_n.substring(prefix.length());
                return rest.isEmpty() || rest.startsWith("/");
            }
        } else if (pattern_n.endsWith("*") && !pattern_n.contains("/*/")) {
            String prefix = pattern_n.substring(0, pattern_n.length() - 1);
            if (path_n.startsWith(prefix)) {
                String rest = path_n.substring(prefix.length());
                return rest.isEmpty() || !rest.startsWith("/");
            }
        }

        for (String component : path_n.split("/")) {
            if (component.equals(pattern_n)) {
                return true;
            }
        }
        for (String component : path_n.split("/")) {
            if (component.contains(pattern_n)) {
                return true;
            }
        }

        if (pattern.contains("?") || pattern.contains("/*/") || pattern.startsWith("*/")) {
            return glob_matches(pattern_n, path_n);
        }
        return false;
    }

    private static boolean glob_matches(String pattern, String path) {
        return glob_match_recursive(pattern, path, 0, 0);
    }

    private static boolean glob_match_recursive(String pattern, String path, int p_idx, int s_idx) {
        char[] pc = pattern.toCharArray();
        char[] sc = path.toCharArray();
        int p = p_idx;
        int s = s_idx;
        while (p < pc.length) {
            if (pc[p] == '*') {
                p++;
                if (p >= pc.length) {
                    return true;
                }
                for (int skip = 0; skip <= sc.length - s; skip++) {
                    if (glob_match_recursive(pattern, path, p, s + skip)) {
                        return true;
                    }
                }
                return false;
            } else if (pc[p] == '?') {
                if (s >= sc.length) {
                    return false;
                }
                p++;
                s++;
            } else {
                if (s >= sc.length || sc[s] != pc[p]) {
                    return false;
                }
                p++;
                s++;
            }
        }
        return s >= sc.length;
    }
}
