package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.annotations.McpParam;
import io.github.vinhphan812.mcp.annotations.McpPrompt;
import io.github.vinhphan812.mcp.annotations.Prompts;

import java.util.Map;

@Prompts
public final class PromptExample {
    @McpPrompt(name = "explain-user", description = "Ask a model to explain a user profile")
    public Map<String, Object> explainUser(
            @McpParam(name = "userId", description = "User identifier", required = true)
            String userId) {
        return MainExample.promptResult("Explain the profile in demo://users/" + userId + " in plain language.");
    }
}
