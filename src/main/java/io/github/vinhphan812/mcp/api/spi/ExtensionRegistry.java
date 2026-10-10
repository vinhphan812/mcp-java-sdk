package io.github.vinhphan812.mcp.api.spi;

import java.util.Set;

/**
 * Minimal registry interface for MCP extensions.
 *
 * <p>Provides read access to registered extensions. Write operations
 * ({@link #register(String, McpExtension)} / {@link #unregister(String)})
 * are also exposed here so the protocol handler can manage extensions
 * without knowing the concrete implementation class.
 *
 * <p>Implementations are thread-safe.
 */
public interface ExtensionRegistry {

    /**
     * Returns the MCP extension registered for the given namespace,
     * or {@code null} if no extension is registered for that namespace.
     *
     * @param namespace extension namespace (e.g. {@code io.modelcontextprotocol/tasks})
     * @return the registered extension, or {@code null}
     */
    McpExtension getExtension(String namespace);

    /**
     * Returns all registered extension namespaces.
     *
     * @return immutable set of namespace strings
     */
    Set<String> getSupportedNamespaces();

    /**
     * Registers an extension, replacing any existing extension with the same namespace.
     *
     * <p>If a previous extension was registered under the same namespace,
     * its {@link McpExtension#unregister()} hook is called before replacement.
     *
     * @param namespace  extension namespace (e.g. {@code io.modelcontextprotocol/tasks})
     * @param extension the extension instance to register
     */
    void register(String namespace, McpExtension extension);

    /**
     * Unregisters the extension for the given namespace.
     *
     * <p>The removed extension's {@link McpExtension#unregister()} hook is called.
     *
     * @param namespace extension namespace
     */
    void unregister(String namespace);

    /**
     * Returns the server's task registry for use by the Tasks extension.
     *
     * @return the task registry bridge, never {@code null}
     */
    McpTaskExtension.TaskRegistry getTaskRegistry();
}
