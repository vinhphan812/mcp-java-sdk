package io.github.vinhphan812.mcp.api.handler;

import java.util.Map;

/** Application-owned implementation for an MCP tool. */
public interface McpToolHandler {
    /**
     * Executes tool with supplied parameters.
     *
     * @param params tool parameters
     * @return tool result
     * @throws Exception if tool execution fails
     */
    Map<String, Object> call(Map<String, Object> params) throws Exception;

    /**
     * Returns the tool's output schema, or {@code null} when none is declared.
     * The schema is included in {@code tools/list} as {@code outputSchema}.
     *
     * @return output schema, or {@code null}
     */
    default Map<String, Object> getOutputSchema() {
        return null;
    }
}
