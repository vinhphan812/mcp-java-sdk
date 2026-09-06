package io.github.vinhphan812.mcp.api;

/** Application-owned implementation for an MCP resource or resource template. */
public interface McpResourceHandler {
    String read(String uri) throws Exception;
}
