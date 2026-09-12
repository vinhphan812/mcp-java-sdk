/**
 * Service Provider Interface and listener contracts for MCP server registration and lifecycle.
 *
 * <ul>
 *   <li>{@link io.github.vinhphan812.mcp.api.spi.McpRegistrar} — registration SPI; implemented by {@code McpServer} and {@code McpRegistry}
 *   <li>{@link io.github.vinhphan812.mcp.api.spi.McpResourceUpdateListener} — receives resource-change notifications from the application
 *   <li>{@link io.github.vinhphan812.mcp.api.spi.McpRegistryChangeListener} — receives registration-change notifications after
 *       tools, resources, or prompts are registered
 * </ul>
 *
 * @see io.github.vinhphan812.mcp.api.handler
 */
package io.github.vinhphan812.mcp.api.spi;
