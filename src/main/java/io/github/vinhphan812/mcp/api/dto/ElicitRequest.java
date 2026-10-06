package io.github.vinhphan812.mcp.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Immutable elicitation request model used by the server-side elicitation API.
 *
 * <p>This DTO captures the parameters for an {@code elicitation/create} server-initiated
 * request. Applications build an instance and pass it to
 * {@code McpProtocolHandler.elicit(...)} or its typed convenience methods.
 *
 * <p>Example wire shape (simplified):
 * <pre>{@code
 * {
 *   "method": "elicitation/create",
 *   "params": {
 *     "message": "Delete record #42?",
 *     "requestedSchema": {
 *       "type": "object",
 *       "properties": {
 *         "action": { "type": "string", "enum": ["confirm", "cancel"] }
 *       }
 *     }
 *   }
 * }
 * }</pre>
 *
 * @see io.github.vinhphan812.mcp.core.McpProtocolHandler#elicit(String, ElicitRequest)
 * @since 2026-07-28
 */
public final class ElicitRequest {

    /**
     * The human-readable prompt sent to the client.
     * Must not be blank.
     */
    private final String message;

    /**
     * Optional labelled actions the client can select.
     * When provided the response must contain one of these labels.
     */
    private final List<ElicitAction> actions;

    /**
     * Optional default value returned if the client declines to provide input.
     */
    private final String defaultValue;

    private ElicitRequest(Builder b) {
        this.message = b.message;
        this.actions = b.actions != null ? List.copyOf(b.actions) : null;
        this.defaultValue = b.defaultValue;
    }

    /** Returns the elicitation message. */
    public String getMessage() {
        return message;
    }

    /** Returns an unmodifiable list of actions, or {@code null}. */
    public List<ElicitAction> getActions() {
        return actions;
    }

    /** Returns the optional default value, or {@code null}. */
    public String getDefaultValue() {
        return defaultValue;
    }

    /**
     * Returns the wire-ready params map for serialisation.
     * Includes {@code _meta.progressToken} for correlation.
     *
     * @param progressToken the server-generated request token
     * @return unmodifiable params map ready for JSON-RPC serialisation
     */
    public Map<String, Object> toParams(String progressToken) {
        java.util.LinkedHashMap<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("message", message);
        if (actions != null && !actions.isEmpty()) {
            java.util.LinkedHashMap<String, Object> schema = new java.util.LinkedHashMap<>();
            schema.put("type", "object");
            java.util.LinkedHashMap<String, Object> props = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<String, Object> actionProp = new java.util.LinkedHashMap<>();
            actionProp.put("type", "string");
            actionProp.put("enum", actions.stream().map(ElicitAction::getLabel).toArray());
            props.put("action", actionProp);
            schema.put("properties", props);
            params.put("requestedSchema", schema);
        }
        if (defaultValue != null) {
            params.put("defaultValue", defaultValue);
        }
        if (progressToken != null) {
            java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("progressToken", progressToken);
            params.put("_meta", meta);
        }
        return java.util.Collections.unmodifiableMap(params);
    }

    @Override
    public String toString() {
        return "ElicitRequest{message='" + message + "', actions=" + actions + ", defaultValue=" + defaultValue + "}";
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String message;
        private List<ElicitAction> actions;
        private String defaultValue;

        private Builder() {}

        /**
         * Sets the elicitation message.
         * @param message non-blank message
         * @return this builder
         */
        public Builder message(String message) {
            if (message == null || message.trim().isEmpty()) {
                throw new IllegalArgumentException("message must not be blank");
            }
            this.message = message.trim();
            return this;
        }

        /**
         * Sets the labelled actions offered to the client.
         * @param actions list of actions, or {@code null}
         * @return this builder
         */
        public Builder actions(List<ElicitAction> actions) {
            this.actions = actions;
            return this;
        }

        /**
         * Sets the default value used when the client declines.
         * @param defaultValue default value string, or {@code null}
         * @return this builder
         */
        public Builder defaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        /** Builds an immutable {@link ElicitRequest}. */
        public ElicitRequest build() {
            if (message == null || message.isEmpty()) {
                throw new IllegalStateException("message is required");
            }
            return new ElicitRequest(this);
        }
    }
}
