package io.github.vinhphan812.mcp.api.handler;

import java.util.Map;

/** Application-owned implementation for an MCP prompt. */
public interface McpPromptHandler {
    /**
     * Executes prompt with supplied arguments.
     *
     * @param arguments prompt arguments
     * @return prompt result
     * @throws Exception if prompt execution fails
     */
    Map<String, Object> call(Map<String, Object> arguments) throws Exception;

    /** Backward-compatible alias for callers using prompt-specific wording.
     * @param arguments prompt arguments
     * @return prompt result
     * @throws Exception if prompt execution fails
     */
    default Map<String, Object> get(Map<String, Object> arguments) throws Exception {
        return call(arguments);
    }
}
