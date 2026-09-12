package io.github.vinhphan812.mcp.api.config;

import com.google.gson.Gson;

import java.util.*;

/**
 * Immutable client capability metadata for an initialize request.
 *
 * <p>This type only builds the JSON-compatible {@code capabilities} object.
 * SDK has no client transport/session or response-correlation layer, so this
 * type deliberately does not send elicitation requests or experimental methods.</p>
 */
public final class McpClientCapabilities {

    private static final Gson GSON = new Gson();

    /**
     * Elicitation capability properties.
     */
    public final Map<String, Object> elicitation;
    /**
     * Experimental capability namespaces and properties.
     */
    public final Map<String, Object> experimental;
    private final boolean elicitationEnabled;

    private McpClientCapabilities(Builder builder) {
        elicitation = immutableCopy(builder.elicitation);
        elicitationEnabled = builder.elicitationEnabled;
        experimental = immutableCopy(builder.experimental);
    }

    /**
     * Creates capability metadata builder.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Serializes capability metadata as initialize.params.capabilities JSON.
     *
     * @return JSON representation
     */
    public String toJson() {
        return GSON.toJson(toMap());
    }

    /**
     * Returns JSON-compatible capability fields in stable insertion order.
     *
     * @return immutable capability map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (elicitationEnabled) result.put("elicitation", elicitation);
        if (!experimental.isEmpty()) result.put("experimental", experimental);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            copy.put(entry.getKey(), immutableCopy(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableCopy(Object value) {
        if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                copy.put(entry.getKey(), immutableCopy(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List) {
            List<?> source = (List<?>) value;
            List<Object> copy = new ArrayList<>(source.size());
            for (Object item : source) copy.add(immutableCopy(item));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set) {
            Set<?> source = (Set<?>) value;
            Set<Object> copy = new LinkedHashSet<>();
            for (Object item : source) copy.add(immutableCopy(item));
            return Collections.unmodifiableSet(copy);
        }
        return value;
    }

    /**
     * Builder for immutable client capability metadata.
     */
    public static final class Builder {
        private Map<String, Object> elicitation = new LinkedHashMap<>();
        private boolean elicitationEnabled;
        private Map<String, Object> experimental = new LinkedHashMap<>();

        /**
         * Enables elicitation capability with optional capability properties.
         *
         * @return this builder
         */
        public Builder elicitation() {
            return elicitation(Collections.emptyMap());
        }

        /**
         * Enables elicitation capability with supplied properties.
         *
         * @param value capability properties
         * @return this builder
         */
        public Builder elicitation(Map<String, Object> value) {
            elicitation = copy(value);
            elicitationEnabled = true;
            return this;
        }

        /**
         * Sets experimental capability namespaces without implying request support.
         *
         * @param value experimental capability properties
         * @return this builder
         */
        public Builder experimental(Map<String, Object> value) {
            experimental = copy(value);
            return this;
        }

        /**
         * Builds immutable client capability metadata.
         *
         * @return capability metadata
         */
        public McpClientCapabilities build() {
            return new McpClientCapabilities(this);
        }


        private static Map<String, Object> copy(Map<String, Object> value) {
            return value == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(value);
        }
    }
}
