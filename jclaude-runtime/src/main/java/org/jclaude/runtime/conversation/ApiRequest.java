package org.jclaude.runtime.conversation;

import java.util.List;
import org.jclaude.runtime.session.ConversationMessage;

/** Fully assembled request payload sent to the upstream model client. */
public record ApiRequest(List<String> system_prompt, List<ConversationMessage> messages) {

    public ApiRequest {
        system_prompt = List.copyOf(system_prompt);
        messages = List.copyOf(messages);
    }
}
