package io.github.vinhphan812.mcp.api;

import java.util.Map;

/** Application-owned implementation for an MCP prompt. */
public interface McpPromptHandler {
    Map<String, Object> call(Map<String, Object> arguments) throws Exception;

    /** Backward-compatible alias for callers using prompt-specific wording. */
    default Map<String, Object> get(Map<String, Object> arguments) throws Exception {
        return call(arguments);
    }
}
