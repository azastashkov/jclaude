package org.jclaude.plugins;

import java.nio.file.Path;
import java.util.Optional;

/** Sealed family of plugin manifest validation failures, mirroring the Rust enum. */
public sealed interface PluginManifestValidationError {

    record EmptyField(String field) implements PluginManifestValidationError {
        @Override
        public String message() {
            return "plugin manifest " + field + " cannot be empty";
        }
    }

    record EmptyEntryField(String kind, String field, Optional<String> name) implements PluginManifestValidationError {
        public EmptyEntryField {
            name = name == null ? Optional.empty() : name;
        }

        @Override
        public String message() {
            if (name.isPresent() && !name.get().isEmpty()) {
                return "plugin " + kind + " `" + name.get() + "` " + field + " cannot be empty";
            }
            return "plugin " + kind + " " + field + " cannot be empty";
        }
    }

    record InvalidPermission(String permission) implements PluginManifestValidationError {
        @Override
        public String message() {
            return "plugin manifest permission `" + permission + "` must be one of read, write, or execute";
        }
    }

    record DuplicatePermission(String permission) implements PluginManifestValidationError {
        @Override
        public String message() {
            return "plugin manifest permission `" + permission + "` is duplicated";
        }
    }

    record DuplicateEntry(String kind, String name) implements PluginManifestValidationError {
        @Override
        public String message() {
            return "plugin " + kind + " `" + name + "` is duplicated";
        }
    }

    record MissingPath(String kind, Path path) implements PluginManifestValidationError {
        @Override
        public String message() {
            return kind + " path `" + path + "` does not exist";
        }
    }

    record PathIsDirectory(String kind, Path path) implements PluginManifestValidationError {
        @Override
        public String message() {
            return kind + " path `" + path + "` must point to a file";
        }
    }

    record InvalidToolInputSchema(String tool_name) implements PluginManifestValidationError {
        @Override
        public String message() {
            return "plugin tool `" + tool_name + "` inputSchema must be a JSON object";
        }
    }

    record InvalidToolRequiredPermission(String tool_name, String permission) implements PluginManifestValidationError {
        @Override
        public String message() {
            return "plugin tool `" + tool_name + "` requiredPermission `" + permission
                    + "` must be read-only, workspace-write, or danger-full-access";
        }
    }

    record UnsupportedManifestContract(String detail) implements PluginManifestValidationError {
        @Override
        public String message() {
            return detail;
        }
    }

    String message();
}
