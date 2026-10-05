package io.github.vinhphan812.mcp.api.spi;

/**
 * Receives notifications after a registry definition has been successfully added.
 * Implementations must not assume that a transport connection is available.
 */
public interface McpRegistryChangeListener {

    /** Topic name for tool-list changes. */
    String TOPIC_TOOLS = "tools";

    /** Topic name for resource-list changes. */
    String TOPIC_RESOURCES = "resources";

    /** Topic name for prompt-list changes. */
    String TOPIC_PROMPTS = "prompts";

    /** Topic name for individual resource-content updates (notifications/resources/updated). */
    String TOPIC_RESOURCES_UPDATED = "resources/updated";

    /**
     * Called after a tool, resource (including template), or prompt is registered.
     * @param listType one of {@link #TOPIC_TOOLS}, {@link #TOPIC_RESOURCES},
     *                 {@link #TOPIC_PROMPTS}, or a listType that may be unknown to this listener
     */
    void onRegistryChanged(String listType);
}
