package org.jclaude.runtime.oauth;

/** PKCE verifier/challenge pair. */
public record PkceCodePair(String verifier, String challenge, PkceChallengeMethod challenge_method) {}
