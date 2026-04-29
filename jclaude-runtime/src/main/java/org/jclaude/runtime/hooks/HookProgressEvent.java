package org.jclaude.runtime.hooks;

/** Hook progress event sealed interface. */
public sealed interface HookProgressEvent {

    HookEvent event();

    String tool_name();

    String command();

    record Started(HookEvent event, String tool_name, String command) implements HookProgressEvent {}

    record Completed(HookEvent event, String tool_name, String command) implements HookProgressEvent {}

    record Cancelled(HookEvent event, String tool_name, String command) implements HookProgressEvent {}
}
