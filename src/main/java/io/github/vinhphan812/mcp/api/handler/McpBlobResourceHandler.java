package io.github.vinhphan812.mcp.api.handler;

import io.github.vinhphan812.mcp.api.dto.McpBlobContent;

/**
 * Application-owned implementation for an MCP resource that returns binary data.
 * Extends {@link McpResourceHandler} so that existing adapters that handle both
 * string and blob resources can implement this interface alone.
 *
 * <p>Return {@link McpBlobContent} from {@link #readBlob(String)} — the protocol
 * handler will serialise it as the MCP {@code type=blob} content shape
 * ({@code {"type":"blob","blob":"<base64>","mimeType":"<type>"}}).
 *
 * <p>The {@code throws Exception} declaration mirrors {@link McpResourceHandler}
 * and accommodates implementations that read from I/O, network, or storage
 * sources which may throw checked exceptions.
 */
//noinspection RedundantThrows
public interface McpBlobResourceHandler extends McpResourceHandler {

    /**
     * Reads a binary resource identified by URI.
     *
     * @param uri resource URI
     * @return blob content (never {@code null})
     * @throws Exception if resource reading fails
     */
    McpBlobContent readBlob(String uri) throws Exception;
}
