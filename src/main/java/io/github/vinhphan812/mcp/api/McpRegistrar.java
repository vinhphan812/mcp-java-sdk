package io.github.vinhphan812.mcp.api;

import java.util.List;
import java.util.Map;

/**
 * Registration SPI implemented by the reusable MCP server and used by
 * application-owned tool, resource, and prompt providers.
 */
public interface McpRegistrar extends McpResourceUpdateListener {
    void registerTool(String name, String description, Map<String, Object> inputSchema,
                      List<String> required, McpToolHandler handler);

    default void registerResource(String uri, String name, String description,
                                   McpResourceHandler handler) {
        registerResource(uri, name, description, "application/json", handler);
    }

    void registerResource(String uri, String name, String description, String mimeType,
                          McpResourceHandler handler);

    default void registerResourceTemplate(String uriTemplate, String name, String description,
                                           McpResourceHandler handler) {
        registerResourceTemplate(uriTemplate, name, description, "application/json", handler);
    }

    void registerResourceTemplate(String uriTemplate, String name, String description,
                                  String mimeType, McpResourceHandler handler);

    void registerPrompt(String name, String description, List<Map<String, Object>> arguments,
                        McpPromptHandler handler);

    void notifyResourceUpdated(String uri);
}
