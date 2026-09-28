package io.github.vinhphan812.mcp.transport;

import java.util.regex.Pattern;

/**
 * Unified SSE (Server-Sent Events) formatter shared by {@link McpHttpHandler}
 * (and previously by the deprecated {@code McpGrizzlyHandler}).
 *
 * <p>Provides:
 * <ul>
 *   <li>Constants for the three event types used by MCP ({@code connected}, {@code ping},
 *       {@code message})</li>
 *   <li>Static factory methods that produce correctly-escaped SSE frames</li>
 *   <li>A public {@link #escapeSseData(String)} utility so callers that build frames
 *       manually can share the same escaping logic</li>
 * </ul>
 */
public final class SseEventFormatter {

    // SSE templates — double-newline terminates each event per the SSE spec
    private static final String PING_TEMPLATE      = "event: ping\ndata: {}\n\n";
    private static final String CONNECTED_TEMPLATE  = "event: connected\ndata: {\"sessionId\":\"%s\"}\n\n";
    private static final String MESSAGE_TEMPLATE    = "id: %d\nevent: message\ndata: %s\n\n";

    /** Pattern matching any line terminator sequence. */
    private static final Pattern CR_LF = Pattern.compile("\r\n|[\r\n]");

    // ── public API ──────────────────────────────────────────────────────────

    /** Returns a ping frame with an empty data field. */
    public static String ping() {
        return PING_TEMPLATE;
    }

    /**
     * Returns a connected frame whose {@code sessionId} field is escaped.
     *
     * @param sessionId the MCP session identifier
     * @return formatted SSE frame
     */
    public static String connected(String sessionId) {
        return String.format(CONNECTED_TEMPLATE, escapeSseData(sessionId));
    }

    /**
     * Returns a message frame whose data body is escaped and whose ID is parsed
     * from a string (throws {@link NumberFormatException} on invalid input).
     *
     * @param id   event ID as a string representation of a non-negative integer
     * @param body JSON body to send
     * @return formatted SSE frame
     */
    public static String message(String id, String body) {
        return String.format(MESSAGE_TEMPLATE, Long.parseLong(id), escapeSseData(body));
    }

    /**
     * Returns a generic SSE frame with a numeric ID, arbitrary event name, and data.
     *
     * @param id    event ID
     * @param event event type name
     * @param data  data payload
     * @return formatted SSE frame
     */
    public static String formatEvent(long id, String event, String data) {
        return "id: " + id + "\nevent: " + event + "\ndata: " + data + "\n\n";
    }

    // ── escaping ───────────────────────────────────────────────────────────

    /**
     * Escapes SSE data by stripping line terminators (CR, LF, CRLF) from a value.
     * This prevents a malicious or malformed payload from injecting additional
     * SSE frames into the stream.
     *
     * @param value raw value (may be null)
     * @return escaped value, or empty string if the input is null
     */
    public static String escapeSseData(String value) {
        return value == null ? "" : CR_LF.matcher(value).replaceAll("");
    }

    private SseEventFormatter() {
        // utility class — prevent instantiation
    }
}
