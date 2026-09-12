package io.github.vinhphan812.mcp.api.handler;

/** Application-owned implementation for an MCP resource or resource template. */
public interface McpResourceHandler {
    /**
     * Reads resource identified by URI.
     *
     * @param uri resource URI
     * @return resource contents
     * @throws Exception if resource reading fails
     */
    String read(String uri) throws Exception;
}
