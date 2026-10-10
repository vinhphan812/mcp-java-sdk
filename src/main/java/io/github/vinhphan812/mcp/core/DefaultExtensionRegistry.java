package io.github.vinhphan812.mcp.core;

import io.github.vinhphan812.mcp.api.spi.ExtensionRegistry;
import io.github.vinhphan812.mcp.api.spi.McpExtension;
import io.github.vinhphan812.mcp.api.spi.McpTaskExtension;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default thread-safe implementation of {@link ExtensionRegistry}.
 *
 * <p>Extensions are stored in a {@link ConcurrentHashMap} keyed by namespace.
 * All accessors are lock-free; the map handles concurrent reads and writes.
 *
 * <p>The task registry bridge is supplied at construction time.
 */
public final class DefaultExtensionRegistry implements ExtensionRegistry {

    private final Map<String, McpExtension> extensions = new ConcurrentHashMap<>();
    private final McpTaskExtension.TaskRegistry taskRegistry;

    /**
     * Creates an empty registry with the given task-registry bridge.
     *
     * @param taskRegistry the bridge to the server's task store; must not be {@code null}
     */
    public DefaultExtensionRegistry(McpTaskExtension.TaskRegistry taskRegistry) {
        if (taskRegistry == null) {
            throw new IllegalArgumentException("taskRegistry must not be null");
        }
        this.taskRegistry = taskRegistry;
    }

    @Override
    public McpExtension getExtension(String namespace) {
        return namespace == null ? null : extensions.get(namespace);
    }

    /**
     * Registers an extension, replacing any existing extension with the same namespace.
     *
     * <p>If a previous extension was registered under the same namespace,
     * its {@link McpExtension#unregister()} hook is called before replacement.
     *
     * @param namespace  extension namespace (e.g. {@code io.modelcontextprotocol/tasks"})
     * @param extension  the extension instance to register
     */
    @Override
    public void register(String namespace, McpExtension extension) {
        if (namespace == null || extension == null) {
            throw new IllegalArgumentException("namespace and extension must not be null");
        }
        McpExtension prev = extensions.put(namespace, extension);
        if (prev != null) {
            prev.unregister();
        }
    }

    /**
     * Unregisters the extension for the given namespace.
     *
     * <p>The removed extension's {@link McpExtension#unregister()} hook is called.
     *
     * @param namespace extension namespace
     */
    @Override
    public void unregister(String namespace) {
        if (namespace == null) return;
        McpExtension removed = extensions.remove(namespace);
        if (removed != null) {
            removed.unregister();
        }
    }

    @Override
    public Set<String> getSupportedNamespaces() {
        return Collections.unmodifiableSet(extensions.keySet());
    }

    @Override
    public McpTaskExtension.TaskRegistry getTaskRegistry() {
        return taskRegistry;
    }
}
