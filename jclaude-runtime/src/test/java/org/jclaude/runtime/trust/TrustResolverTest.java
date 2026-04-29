package org.jclaude.runtime.trust;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TrustResolverTest {

    @Test
    void glob_pattern_star_matches_any_sequence() {
        assertThat(TrustConfig.pattern_matches("/tmp/*", "/tmp/foo")).isTrue();
        assertThat(TrustConfig.pattern_matches("/tmp/*", "/tmp/bar/baz")).isTrue();
        assertThat(TrustConfig.pattern_matches("/tmp/*", "/other/tmp/foo")).isFalse();
    }

    @Test
    void glob_pattern_question_matches_single_char() {
        assertThat(TrustConfig.pattern_matches("/tmp/test?", "/tmp/test1")).isTrue();
        assertThat(TrustConfig.pattern_matches("/tmp/test?", "/tmp/testA")).isTrue();
        assertThat(TrustConfig.pattern_matches("/tmp/test?", "/tmp/test12")).isFalse();
        assertThat(TrustConfig.pattern_matches("/tmp/test?", "/tmp/test")).isFalse();
    }

    @Test
    void pattern_matches_exact() {
        assertThat(TrustConfig.pattern_matches("/tmp/worktrees", "/tmp/worktrees"))
                .isTrue();
        assertThat(TrustConfig.pattern_matches("/tmp/worktrees", "/tmp/worktrees-other"))
                .isFalse();
    }

    @Test
    void pattern_matches_prefix_with_wildcard() {
        assertThat(TrustConfig.pattern_matches("/tmp/worktrees/*", "/tmp/worktrees/repo-a"))
                .isTrue();
        assertThat(TrustConfig.pattern_matches("/tmp/worktrees/*", "/tmp/worktrees/repo-a/subdir"))
                .isTrue();
        assertThat(TrustConfig.pattern_matches("/tmp/worktrees/*", "/tmp/other/repo"))
                .isFalse();
    }

    @Test
    void pattern_matches_contains() {
        assertThat(TrustConfig.pattern_matches("worktrees", "/tmp/worktrees/repo-a"))
                .isTrue();
        assertThat(TrustConfig.pattern_matches("repo", "/tmp/worktrees/repo-a")).isTrue();
    }

    @Test
    void allowlist_entry_with_worktree_pattern() {
        TrustConfig config = new TrustConfig()
                .with_allowlisted_entry(TrustAllowlistEntry.of("/tmp/worktrees/*")
                        .with_worktree_pattern("*/.git")
                        .with_description("Git worktrees"));

        assertThat(config.is_allowlisted("/tmp/worktrees/repo-a", "/tmp/worktrees/repo-a/.git"))
                .isPresent();
        assertThat(config.is_allowlisted("/tmp/worktrees/repo-a", "/other/path"))
                .isEmpty();
        assertThat(config.is_allowlisted("/tmp/worktrees/repo-a", null)).isEmpty();

        TrustConfig config_no_worktree = new TrustConfig().with_allowlisted("/tmp/worktrees/*");
        assertThat(config_no_worktree.is_allowlisted("/tmp/worktrees/repo-a", null))
                .isPresent();
    }

    @Test
    void allowlist_entry_returns_matched_entry() {
        TrustAllowlistEntry entry = TrustAllowlistEntry.of("/tmp/worktrees/*").with_description("Test worktrees");
        TrustConfig config = new TrustConfig().with_allowlisted_entry(entry);

        Optional<TrustAllowlistEntry> matched = config.is_allowlisted("/tmp/worktrees/repo-a", null);

        assertThat(matched).isPresent();
        assertThat(matched.get().description()).contains("Test worktrees");
    }

    @Test
    void complex_glob_patterns() {
        assertThat(TrustConfig.pattern_matches("/tmp/*/repo-*", "/tmp/worktrees/repo-123"))
                .isTrue();
        assertThat(TrustConfig.pattern_matches("/tmp/*/repo-*", "/tmp/other/repo-abc"))
                .isTrue();
        assertThat(TrustConfig.pattern_matches("/tmp/*/repo-*", "/tmp/worktrees/other"))
                .isFalse();
        assertThat(TrustConfig.pattern_matches("/tmp/test?/*.txt", "/tmp/test1/file.txt"))
                .isTrue();
        assertThat(TrustConfig.pattern_matches("/tmp/test?/*.txt", "/tmp/testA/subdir/file.txt"))
                .isTrue();
    }

    @Test
    void detects_known_trust_prompt_copy() {
        assertThat(TrustResolver.detect_trust_prompt("Do you trust the files in this folder?\n1. Yes, proceed\n2. No"))
                .isTrue();
    }

    @Test
    void does_not_emit_events_when_prompt_is_absent() {
        TrustResolver resolver = new TrustResolver(new TrustConfig().with_allowlisted("/tmp/worktrees"));

        TrustDecision decision = resolver.resolve("/tmp/worktrees/repo-a", null, "Ready for your input\n>");

        assertThat(decision).isInstanceOf(TrustDecision.NotRequired.class);
        assertThat(decision.events()).isEmpty();
        assertThat(decision.policy()).isEmpty();
    }

    @Test
    void auto_trusts_allowlisted_cwd_after_prompt_detection() {
        TrustResolver resolver = new TrustResolver(new TrustConfig().with_allowlisted("/tmp/worktrees"));

        TrustDecision decision = resolver.resolve(
                "/tmp/worktrees/repo-a", null, "Do you trust the files in this folder?\n1. Yes, proceed\n2. No");

        assertThat(decision.policy()).contains(TrustPolicy.AUTO_TRUST);
        List<TrustEvent> events = decision.events();
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(TrustEvent.TrustRequired.class);
        assertThat(events.get(1)).isInstanceOf(TrustEvent.TrustResolved.class);
    }

    @Test
    void requires_approval_for_unknown_cwd_after_prompt_detection() {
        TrustResolver resolver = new TrustResolver(new TrustConfig().with_allowlisted("/tmp/worktrees"));

        TrustDecision decision = resolver.resolve(
                "/tmp/other/repo-b", null, "Do you trust the files in this folder?\n1. Yes, proceed\n2. No");

        assertThat(decision.policy()).contains(TrustPolicy.REQUIRE_APPROVAL);
        assertThat(decision.events()).hasSize(1);
        assertThat(decision.events().get(0)).isInstanceOf(TrustEvent.TrustRequired.class);
    }

    @Test
    void denied_root_takes_precedence_over_allowlist() {
        TrustResolver resolver = new TrustResolver(
                new TrustConfig().with_allowlisted("/tmp/worktrees").with_denied("/tmp/worktrees/repo-c"));

        TrustDecision decision = resolver.resolve(
                "/tmp/worktrees/repo-c", null, "Do you trust the files in this folder?\n1. Yes, proceed\n2. No");

        assertThat(decision.policy()).contains(TrustPolicy.DENY);
        assertThat(decision.events()).hasSize(2);
    }

    @Test
    void auto_trusts_with_glob_pattern_allowlist() {
        TrustResolver resolver = new TrustResolver(new TrustConfig().with_allowlisted("/tmp/worktrees/*"));

        TrustDecision decision = resolver.resolve(
                "/tmp/worktrees/repo-a", null, "Do you trust the files in this folder?\n1. Yes, proceed\n2. No");

        assertThat(decision.policy()).contains(TrustPolicy.AUTO_TRUST);
    }

    @Test
    void resolve_with_worktree_pattern_matching() {
        TrustConfig config = new TrustConfig()
                .with_allowlisted_entry(
                        TrustAllowlistEntry.of("/tmp/worktrees/*").with_worktree_pattern("*/.git"));
        TrustResolver resolver = new TrustResolver(config);

        TrustDecision decision = resolver.resolve(
                "/tmp/worktrees/repo-a",
                "/tmp/worktrees/repo-a/.git",
                "Do you trust the files in this folder?\n1. Yes, proceed\n2. No");

        assertThat(decision.policy()).contains(TrustPolicy.AUTO_TRUST);
    }

    @Test
    void manual_approval_detected_from_screen_text() {
        TrustResolver resolver = new TrustResolver(new TrustConfig());

        TrustDecision decision = resolver.resolve(
                "/tmp/some/repo",
                null,
                "Do you trust the files in this folder?\nUser selected: Yes, I trust this folder");

        assertThat(decision.policy()).contains(TrustPolicy.REQUIRE_APPROVAL);
        List<TrustEvent> events = decision.events();
        assertThat(events.size()).isGreaterThanOrEqualTo(2);
        assertThat(events.get(events.size() - 1)).isInstanceOf(TrustEvent.TrustResolved.class);
    }

    @Test
    void sibling_prefix_does_not_match_trusted_root() {
        boolean matched = TrustResolver.path_matches_trusted_root("/tmp/worktrees-other/repo-d", "/tmp/worktrees");

        assertThat(matched).isFalse();
    }

    @Test
    void detects_manual_approval_cues() {
        assertThat(TrustResolver.detect_manual_approval("User selected: Yes, I trust this folder"))
                .isTrue();
        assertThat(TrustResolver.detect_manual_approval("I trust this repository and its contents"))
                .isTrue();
        assertThat(TrustResolver.detect_manual_approval("Approval granted by user"))
                .isTrue();
        assertThat(TrustResolver.detect_manual_approval("Do you trust the files in this folder?"))
                .isFalse();
        assertThat(TrustResolver.detect_manual_approval("Some unrelated text")).isFalse();
    }

    @Test
    void trust_config_default_emit_events() {
        TrustConfig config = new TrustConfig();
        assertThat(config.emit_events()).isTrue();
    }

    @Test
    void trust_resolver_trusts_method() {
        TrustResolver resolver = new TrustResolver(
                new TrustConfig().with_allowlisted("/tmp/worktrees/*").with_denied("/tmp/worktrees/bad-repo"));

        assertThat(resolver.trusts("/tmp/worktrees/good-repo", null)).isTrue();
        assertThat(resolver.trusts("/tmp/worktrees/bad-repo", null)).isFalse();
        assertThat(resolver.trusts("/tmp/other/repo", null)).isFalse();
    }

    @Test
    void serde_serialization_roundtrip() {
        // The Java TrustConfig is a value-style class — verify equivalent state
        // is preserved through copy semantics (field-by-field reconstruction)
        // since the Rust port relies on serde JSON for the same purpose.
        TrustConfig config = new TrustConfig()
                .with_allowlisted_entry(TrustAllowlistEntry.of("/tmp/worktrees/*")
                        .with_worktree_pattern("*/.git")
                        .with_description("Git worktrees"))
                .with_denied("/tmp/malicious");

        TrustConfig roundtripped = new TrustConfig(config.allowlisted(), config.denied(), config.emit_events());

        assertThat(roundtripped.allowlisted()).hasSize(config.allowlisted().size());
        assertThat(roundtripped.denied()).hasSize(config.denied().size());
        assertThat(roundtripped.emit_events()).isEqualTo(config.emit_events());
    }

    @Test
    void trust_event_serialization() {
        // Mirrors crates/runtime/src/trust_resolver.rs:617 — verifies the
        // TrustRequired record carries cwd/repo/worktree fields by reading
        // them back through pattern matching.
        TrustEvent event =
                new TrustEvent.TrustRequired("/tmp/test", Optional.of("test-repo"), Optional.of("/tmp/test/.git"));

        assertThat(event).isInstanceOf(TrustEvent.TrustRequired.class);
        TrustEvent.TrustRequired required = (TrustEvent.TrustRequired) event;
        assertThat(required.cwd()).isEqualTo("/tmp/test");
        assertThat(required.repo()).hasValue("test-repo");
        assertThat(required.worktree()).hasValue("/tmp/test/.git");
    }

    @Test
    void trust_event_resolved_serialization() {
        TrustEvent event =
                new TrustEvent.TrustResolved("/tmp/test", TrustPolicy.AUTO_TRUST, TrustResolution.AUTO_ALLOWLISTED);

        assertThat(event).isInstanceOf(TrustEvent.TrustResolved.class);
        TrustEvent.TrustResolved resolved = (TrustEvent.TrustResolved) event;
        assertThat(resolved.cwd()).isEqualTo("/tmp/test");
        assertThat(resolved.policy()).isEqualTo(TrustPolicy.AUTO_TRUST);
        assertThat(resolved.resolution()).isEqualTo(TrustResolution.AUTO_ALLOWLISTED);
    }

    @Test
    void trust_policy_serde_roundtrip() {
        for (TrustPolicy policy : List.of(TrustPolicy.AUTO_TRUST, TrustPolicy.REQUIRE_APPROVAL, TrustPolicy.DENY)) {
            TrustPolicy parsed = TrustPolicy.valueOf(policy.name());
            assertThat(parsed).isEqualTo(policy);
        }
    }

    @Test
    void trust_resolution_serde_roundtrip() {
        for (TrustResolution resolution : List.of(TrustResolution.AUTO_ALLOWLISTED, TrustResolution.MANUAL_APPROVAL)) {
            TrustResolution parsed = TrustResolution.valueOf(resolution.name());
            assertThat(parsed).isEqualTo(resolution);
        }
    }
}
