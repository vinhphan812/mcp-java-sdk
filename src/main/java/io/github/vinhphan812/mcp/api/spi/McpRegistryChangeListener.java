package io.github.vinhphan812.mcp.api.spi;

/**
 * Receives notifications after a registry definition has been successfully added.
 * Implementations must not assume that a transport connection is available.
 */
public interface McpRegistryChangeListener {
    /** Called after a tool, resource (including template), or prompt is registered.
     * @param listType one of {@code tools}, {@code resources}, or {@code prompts}
     */
    void onRegistryChanged(String listType);
}
