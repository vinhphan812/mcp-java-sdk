package io.github.vinhphan812.mcp.api.events;

/**
 * Defines the policy for handling notification queue overflow.
 */
public enum QueueOverflowPolicy {
    /** Throw {@link QueueOverflowException}. */
    THROW_EXCEPTION,
    /** Drop the oldest notification from the queue. */
    DROP_OLDEST,
    /** Notify the listener, and if no listener — throw exception. */
    NOTIFY_LISTENER
}
