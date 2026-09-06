package io.github.vinhphan812.mcp.api;

import java.util.Map;

/** Application-owned implementation for an MCP tool. */
public interface McpToolHandler {
    Map<String, Object> call(Map<String, Object> params) throws Exception;
}
