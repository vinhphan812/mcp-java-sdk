/**
 * Application-owned handler interfaces for MCP tools, resources, prompts, and completions.
 * Implement these to provide the actual behaviour behind registered definitions.
 *
 * <ul>
 *   <li>{@link io.github.vinhphan812.mcp.api.handler.McpToolHandler} — tool execution
 *   <li>{@link io.github.vinhphan812.mcp.api.handler.McpResourceHandler} — resource reading (text)
 *   <li>{@link io.github.vinhphan812.mcp.api.handler.McpBlobResourceHandler} — resource reading (binary)
 *   <li>{@link io.github.vinhphan812.mcp.api.handler.McpPromptHandler} — prompt generation
 *   <li>{@link io.github.vinhphan812.mcp.api.handler.McpCompletionProvider} — completion values
 * </ul>
 *
 * @see io.github.vinhphan812.mcp.api.spi
 */
package io.github.vinhphan812.mcp.api.handler;
