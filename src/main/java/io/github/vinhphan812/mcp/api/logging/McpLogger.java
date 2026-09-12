package io.github.vinhphan812.mcp.api.logging;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;

/**
 * Application logger used by the portable MCP protocol layer.
 *
 * <p>This is a local logging hook. It does not promise that messages reach an
 * MCP client; configure {@link McpServerConfig.Builder#logging(boolean)} to
 * also queue level-filtered {@code notifications/message} notifications.</p>
 */
public interface McpLogger {
    /**
     * Emits a verbose diagnostic message.
     *
     * @param message message to emit
     */
    void verbose(String message);

    /**
     * Emits a debug diagnostic message.
     *
     * @param message message to emit
     */
    void debug(String message);

    /**
     * Emits an informational message.
     *
     * @param message message to emit
     */
    void info(String message);

    /**
     * Emits a warning message.
     *
     * @param message message to emit
     */
    void warn(String message);

    /**
     * Emits an error message.
     *
     * @param message message to emit
     */
    void error(String message);

    /**
     * Built-in level names used by MCP {@code logging/setLevel} integration.
     */
    enum Level {
        /**
         * Most detailed diagnostic level.
         */
        VERBOSE,
        /**
         * Debug diagnostic level.
         */
        DEBUG,
        /**
         * Informational level.
         */
        INFO,
        /**
         * Warning level.
         */
        WARN,
        /**
         * Error level.
         */
        ERROR
    }
}
