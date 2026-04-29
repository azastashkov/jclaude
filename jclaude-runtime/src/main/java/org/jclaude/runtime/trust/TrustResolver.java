package org.jclaude.runtime.trust;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Trust resolver port. */
public final class TrustResolver {

    private static final List<String> TRUST_PROMPT_CUES = List.of(
            "do you trust the files in this folder",
            "trust the files in this folder",
            "trust this folder",
            "allow and continue",
            "yes, proceed");

    private static final List<String> MANUAL_APPROVAL_CUES =
            List.of("yes, i trust", "i trust this", "trusted manually", "approval granted");

    private final TrustConfig config;

    public TrustResolver(TrustConfig config) {
        this.config = config;
    }

    public TrustDecision resolve(String cwd, String worktree, String screen_text) {
        if (!detect_trust_prompt(screen_text)) {
            return new TrustDecision.NotRequired();
        }

        Optional<String> repo = extract_repo_name(cwd);
        List<TrustEvent> events = new ArrayList<>();
        events.add(
                new TrustEvent.TrustRequired(cwd, repo, worktree == null ? Optional.empty() : Optional.of(worktree)));

        for (Path root : config.denied()) {
            if (path_matches(cwd, root)) {
                events.add(new TrustEvent.TrustDenied(cwd, "cwd matches denied trust root: " + root));
                return new TrustDecision.Required(TrustPolicy.DENY, events);
            }
        }

        if (config.is_allowlisted(cwd, worktree).isPresent()) {
            events.add(new TrustEvent.TrustResolved(cwd, TrustPolicy.AUTO_TRUST, TrustResolution.AUTO_ALLOWLISTED));
            return new TrustDecision.Required(TrustPolicy.AUTO_TRUST, events);
        }

        if (detect_manual_approval(screen_text)) {
            events.add(
                    new TrustEvent.TrustResolved(cwd, TrustPolicy.REQUIRE_APPROVAL, TrustResolution.MANUAL_APPROVAL));
            return new TrustDecision.Required(TrustPolicy.REQUIRE_APPROVAL, events);
        }

        return new TrustDecision.Required(TrustPolicy.REQUIRE_APPROVAL, events);
    }

    public boolean trusts(String cwd, String worktree) {
        for (Path root : config.denied()) {
            if (path_matches(cwd, root)) {
                return false;
            }
        }
        return config.is_allowlisted(cwd, worktree).isPresent();
    }

    public static boolean detect_trust_prompt(String screen_text) {
        String lower = screen_text.toLowerCase(Locale.ROOT);
        for (String cue : TRUST_PROMPT_CUES) {
            if (lower.contains(cue)) {
                return true;
            }
        }
        return false;
    }

    public static boolean detect_manual_approval(String screen_text) {
        String lower = screen_text.toLowerCase(Locale.ROOT);
        for (String cue : MANUAL_APPROVAL_CUES) {
            if (lower.contains(cue)) {
                return true;
            }
        }
        return false;
    }

    public static boolean path_matches_trusted_root(String cwd, String trusted_root) {
        return path_matches(cwd, normalize(Paths.get(trusted_root)));
    }

    private static boolean path_matches(String candidate, Path root) {
        Path c = normalize(Paths.get(candidate));
        Path r = normalize(root);
        if (c.equals(r)) {
            return true;
        }
        return c.startsWith(r);
    }

    private static Path normalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    private static Optional<String> extract_repo_name(String cwd) {
        Path path = Paths.get(cwd);
        Path current = path;
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                Path name = current.getFileName();
                return name == null ? Optional.empty() : Optional.of(name.toString());
            }
            current = current.getParent();
        }
        Path name = path.getFileName();
        return name == null ? Optional.empty() : Optional.of(name.toString());
    }
}
