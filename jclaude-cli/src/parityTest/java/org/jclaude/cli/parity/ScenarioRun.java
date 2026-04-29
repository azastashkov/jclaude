package org.jclaude.cli.parity;

import com.fasterxml.jackson.databind.JsonNode;

/** Captured outcome of one scenario invocation. */
public record ScenarioRun(JsonNode response, String stdout, String stderr, int exit_code) {}
