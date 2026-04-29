package org.jclaude.api.providers;

/**
 * Provider metadata used by the model registry — describes the provider kind,
 * the env var that holds API credentials, the env var that holds an optional
 * base-URL override, and the default base URL used when the env var is unset.
 */
public record ProviderMetadata(ProviderKind provider, String auth_env, String base_url_env, String default_base_url) {}
