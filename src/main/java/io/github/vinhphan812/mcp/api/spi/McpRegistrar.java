package io.github.vinhphan812.mcp.api.spi;

import io.github.vinhphan812.mcp.api.handler.*;

import java.util.List;
import java.util.Map;


/**
 * Registration SPI implemented by the reusable MCP server and used by
 * application-owned tool, resource, and prompt providers.
 */
public interface McpRegistrar extends McpResourceUpdateListener {
    /**
     * Registers an MCP tool definition and its handler.
     *
     * @param name        tool name
     * @param description tool description
     * @param inputSchema tool input property definitions
     * @param required    required input names
     * @param handler     tool handler
     */
    void registerTool(String name, String description, Map<String, Object> inputSchema,
                      List<String> required, McpToolHandler handler);

    /**
     * Registers an MCP tool definition and its handler with optional outputSchema.
     *
     * @param name          tool name
     * @param description   tool description
     * @param inputSchema   tool input property definitions
     * @param required      required input names
     * @param outputSchema  tool output schema, or {@code null} to omit
     * @param handler       tool handler
     */
    void registerTool(String name, String description, Map<String, Object> inputSchema,
                      List<String> required, Map<String, Object> outputSchema,
                      McpToolHandler handler);

    /**
     * Registers an MCP resource using {@code application/json} as MIME type.
     *
     * @param uri         resource URI
     * @param name        resource name
     * @param description resource description
     * @param handler     resource handler
     */
    default void registerResource(String uri, String name, String description,
                                  McpResourceHandler handler) {
        registerResource(uri, name, description, "application/json", handler);
    }

    /**
     * Registers an MCP resource definition and its handler.
     *
     * @param uri         resource URI
     * @param name        resource name
     * @param description resource description
     * @param mimeType    resource MIME type
     * @param handler     resource handler
     */
    void registerResource(String uri, String name, String description, String mimeType,
                          McpResourceHandler handler);

    /**
     * Registers an MCP blob resource that returns binary data.
     * The handler's {@link McpBlobResourceHandler#readBlob(String)} result is
     * serialised as MCP {@code type=blob} content.
     *
     * @param uri         resource URI
     * @param name        resource name
     * @param description resource description
     * @param mimeType    resource MIME type
     * @param handler     blob resource handler
     */
    default void registerBlobResource(String uri, String name, String description,
                                      String mimeType, McpBlobResourceHandler handler) {
        registerResource(uri, name, description, mimeType, handler);
    }

    /**
     * Registers an MCP resource template using {@code application/json} as MIME type.
     *
     * @param uriTemplate resource URI template
     * @param name        resource template name
     * @param description resource template description
     * @param handler     resource handler
     */
    default void registerResourceTemplate(String uriTemplate, String name, String description,
                                          McpResourceHandler handler) {
        registerResourceTemplate(uriTemplate, name, description, "application/json", handler);
    }

    /**
     * Registers an MCP resource template definition and its handler.
     *
     * @param uriTemplate resource URI template
     * @param name        resource template name
     * @param description resource template description
     * @param mimeType    resource template MIME type
     * @param handler     resource handler
     */
    void registerResourceTemplate(String uriTemplate, String name, String description,
                                  String mimeType, McpResourceHandler handler);

    /**
     * Registers an MCP blob resource template that returns binary data.
     * The handler's {@link McpBlobResourceHandler#readBlob(String)} result is
     * serialised as MCP {@code type=blob} content.
     *
     * @param uriTemplate resource URI template
     * @param name        resource template name
     * @param description resource template description
     * @param mimeType    resource template MIME type
     * @param handler     blob resource template handler
     */
    default void registerBlobResourceTemplate(String uriTemplate, String name,
                                              String description, String mimeType,
                                              McpBlobResourceHandler handler) {
        registerResourceTemplate(uriTemplate, name, description, mimeType, handler);
    }

    /**
     * Registers an MCP prompt definition and its handler.
     *
     * @param name        prompt name
     * @param description prompt description
     * @param arguments   prompt argument definitions
     * @param handler     prompt handler
     */
    void registerPrompt(String name, String description, List<Map<String, Object>> arguments,
                        McpPromptHandler handler);

    /**
     * Registers a completion provider for a completion reference type.
     *
     * @param referenceType completion reference type
     * @param provider      completion provider
     */
    void registerCompletionProvider(String referenceType, McpCompletionProvider provider);

    /**
     * Notifies listener of an updated resource URI.
     *
     * @param uri updated resource URI
     */
    void notifyResourceUpdated(String uri);
}
