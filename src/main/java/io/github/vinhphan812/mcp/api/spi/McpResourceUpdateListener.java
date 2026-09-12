package io.github.vinhphan812.mcp.api.spi;

/** Receives application-originated resource change events. */
public interface McpResourceUpdateListener {
    /** Notifies listener that resource identified by URI changed.
     * @param uri updated resource URI
     */
    void notifyResourceUpdated(String uri);
}
