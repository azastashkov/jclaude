package org.jclaude.plugins;

import java.util.List;

/** Checked exception family for plugin loading / lifecycle failures. */
public class PluginError extends Exception {

    public enum Kind {
        IO,
        JSON,
        MANIFEST_VALIDATION,
        LOAD_FAILURES,
        INVALID_MANIFEST,
        NOT_FOUND,
        COMMAND_FAILED
    }

    private final Kind kind;
    private final List<PluginManifestValidationError> manifest_errors;
    private final List<PluginLoadFailure> load_failures;

    private PluginError(
            Kind kind,
            String message,
            Throwable cause,
            List<PluginManifestValidationError> manifest_errors,
            List<PluginLoadFailure> load_failures) {
        super(message, cause);
        this.kind = kind;
        this.manifest_errors = manifest_errors == null ? List.of() : List.copyOf(manifest_errors);
        this.load_failures = load_failures == null ? List.of() : List.copyOf(load_failures);
    }

    public static PluginError io(Throwable cause) {
        return new PluginError(Kind.IO, cause.getMessage(), cause, null, null);
    }

    public static PluginError json(Throwable cause) {
        return new PluginError(Kind.JSON, cause.getMessage(), cause, null, null);
    }

    public static PluginError manifest_validation(List<PluginManifestValidationError> errors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(errors.get(i).message());
        }
        return new PluginError(Kind.MANIFEST_VALIDATION, sb.toString(), null, errors, null);
    }

    public static PluginError load_failures(List<PluginLoadFailure> failures) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < failures.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(failures.get(i).message());
        }
        return new PluginError(Kind.LOAD_FAILURES, sb.toString(), null, null, failures);
    }

    public static PluginError invalid_manifest(String message) {
        return new PluginError(Kind.INVALID_MANIFEST, message, null, null, null);
    }

    public static PluginError not_found(String message) {
        return new PluginError(Kind.NOT_FOUND, message, null, null, null);
    }

    public static PluginError command_failed(String message) {
        return new PluginError(Kind.COMMAND_FAILED, message, null, null, null);
    }

    public Kind kind() {
        return kind;
    }

    public List<PluginManifestValidationError> manifest_errors() {
        return manifest_errors;
    }

    public List<PluginLoadFailure> load_failures_list() {
        return load_failures;
    }
}
