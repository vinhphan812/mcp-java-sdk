package io.github.vinhphan812.mcp.api.logging;

import java.util.logging.Logger;

/** Default adapter from portable logger API to {@code java.util.logging}. */
public final class JulMcpLogger implements McpLogger {
    private final Logger logger;

    /** Creates logger adapter with supplied logger name.
     * @param name logger name; blank names use {@code mcp-server} */
    public JulMcpLogger(String name) {
        logger = Logger.getLogger(name == null || name.trim().isEmpty() ? "mcp-server" : name);
    }

    /** Emits verbose message.
     * @param message message to emit */
    @Override
    public void verbose(String message) {
        logger.log(java.util.logging.Level.FINER, message);
    }

    /** Emits debug message.
     * @param message message to emit */
    @Override
    public void debug(String message) {
        logger.log(java.util.logging.Level.FINE, message);
    }

    /** Emits informational message.
     * @param message message to emit */
    @Override
    public void info(String message) {
        logger.log(java.util.logging.Level.INFO, message);
    }

    /** Emits warning message.
     * @param message message to emit */
    @Override
    public void warn(String message) {
        logger.log(java.util.logging.Level.WARNING, message);
    }

    /** Emits error message.
     * @param message message to emit */
    @Override
    public void error(String message) {
        logger.log(java.util.logging.Level.SEVERE, message);
    }
}
