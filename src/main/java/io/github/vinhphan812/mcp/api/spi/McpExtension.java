package io.github.vinhphan812.mcp.api.spi;

import java.util.Set;

/**
 * Base interface for MCP protocol extensions.
 *
 * <p>Implementations advertise server capabilities and handle protocol methods
 * for a specific extension namespace (e.g. {@code io.modelcontextprotocol/tasks}).
 *
 * <p>All MCP extensions MUST implement this interface. The protocol handler
 * maintains an {@link ExtensionRegistry} containing registered extensions.
 * Capability advertisement and request dispatch are driven by the registry
 * rather than hard-coded slots.
 *
 * <p>Multiple extensions can coexist — each owns its own namespace. The protocol
 * handler safely ignores namespaces the server does not recognize.
 *
 * @see ExtensionRegistry
 * @see McpTaskExtension
 */
public interface McpExtension {

    /**
     * Returns the canonical namespace for this extension.
     * Must match the MCP specification name (e.g. {@code io.modelcontextprotocol/tasks}).
     *
     * @return extension namespace string, never {@code null}
     */
    String namespace();

    /**
     * Returns whether this extension handles requests for the given protocol version.
     *
     * <p>The server calls this before advertising the capability
     * and before dispatching any request for this namespace.
     *
     * @param protocolVersion protocol version from the {@code initialize} request
     *                       (never {@code null})
     * @return {@code true} when this extension claims the version
     */
    boolean supports(String protocolVersion);

    /**
     * Lifecycle hook: called once when the extension is registered with the server.
     *
     * <p>Subclasses can override to perform one-time initialisation
     * (e.g. opening connections, loading persisted state).
     *
     * @param registry the owning extension registry
     */
    default void register(ExtensionRegistry registry) {
    }

    /**
     * Lifecycle hook: called when the extension is deregistered
     * or the server is shutting down.
     *
     * <p>Subclasses can override to release resources.
     */
    default void unregister() {
    }
}
