package io.github.vinhphan812.mcp.api.events;

/**
 * Thrown when the notification queue for a session reaches the configured limit.
 */
public class QueueOverflowException extends RuntimeException {
    /**
     * @param sessionId session identifier where overflow occurred
     */
    public QueueOverflowException(String sessionId) {
        super("MCP notification queue is full for session: " + sessionId);
    }
}
