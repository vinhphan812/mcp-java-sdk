package io.github.vinhphan812.mcp.api.dto;

import java.util.Map;

/**
 * Immutable result of an {@code elicitation/create} server-initiated request.
 *
 * <p>Returned by {@code McpProtocolHandler} elicitation methods after the client
 * responds. Applications check {@link #getAction()} to determine which action
 * the client selected, or {@link #getValue()} for a text input result.
 *
 * @since 2026-07-28
 */
public final class ElicitationResult {

    /** Special marker value used when the client explicitly declined the elicitation. */
    public static final String DECLINED = "__declined__";

    private final String action;
    private final String value;

    private ElicitationResult(String action, String value) {
        this.action = action;
        this.value = value;
    }

    /**
     * Returns the action label the client selected, or {@link #DECLINED} when
     * the client explicitly declined without selecting an action.
     * Returns {@code null} when the result is a raw text value.
     */
    public String getAction() {
        return action;
    }

    /**
     * Returns the raw text value the client entered.
     * Returns {@code null} when the result is an action selection.
     */
    public String getValue() {
        return value;
    }

    /**
     * Returns {@code true} when the client explicitly declined the elicitation.
     */
    public boolean isDeclined() {
        return DECLINED.equals(action);
    }

    /**
     * Creates a result from an action selection.
     *
     * @param action non-blank action label
     * @return a result representing the selected action
     */
    public static ElicitationResult ofAction(String action) {
        if (action == null || action.isEmpty()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        return new ElicitationResult(action, null);
    }

    /**
     * Creates a result from a text value.
     *
     * @param value the client's text input
     * @return a result representing the entered value
     */
    public static ElicitationResult ofValue(String value) {
        return new ElicitationResult(null, value);
    }

    /**
     * Creates a result representing an explicit decline.
     */
    public static ElicitationResult declined() {
        return new ElicitationResult(DECLINED, null);
    }

    /**
     * Constructs a result from the wire response map received from the client.
     * Handles both action-based and text-value response shapes.
     *
     * @param result raw result map from the JSON-RPC response
     * @return the parsed result
     * @throws IllegalArgumentException if the result shape is unrecognisable
     */
    @SuppressWarnings("unchecked")
    public static ElicitationResult fromResponse(Map<String, Object> result) {
        if (result == null) {
            return declined();
        }
        Object action = result.get("action");
        if (action instanceof String) {
            String a = ((String) action).trim();
            if (a.isEmpty()) return declined();
            if (a.equalsIgnoreCase("cancel") || a.equalsIgnoreCase("decline")
                    || a.equalsIgnoreCase("dismiss") || a.equalsIgnoreCase("reject")) {
                return declined();
            }
            return ofAction(a);
        }
        Object value = result.get("value");
        if (value instanceof String) {
            return ofValue((String) value);
        }
        Object contents = result.get("contents");
        if (contents instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) contents;
            if (!list.isEmpty() && list.get(0) instanceof Map) {
                Map<?, ?> first = (Map<?, ?>) list.get(0);
                Object text = first.get("text");
                if (text instanceof String) return ofValue((String) text);
            }
        }
        throw new IllegalArgumentException("Unrecognisable elicitation result shape: " + result);
    }

    @Override
    public String toString() {
        if (isDeclined()) return "ElicitationResult{declined}";
        if (action != null) return "ElicitationResult{action='" + action + "'}";
        return "ElicitationResult{value='" + value + "'}";
    }
}
