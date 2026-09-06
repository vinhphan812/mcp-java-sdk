package io.github.vinhphan812.mcp.api;

/** Receives application-originated resource change events. */
public interface McpResourceUpdateListener {
    void notifyResourceUpdated(String uri);
}
