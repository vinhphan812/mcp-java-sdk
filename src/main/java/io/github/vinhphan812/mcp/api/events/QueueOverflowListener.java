package io.github.vinhphan812.mcp.api.events;

/**
 * Listener for notification queue overflow events.
 */
public interface QueueOverflowListener {
    void onOverflow(String sessionId);
}
