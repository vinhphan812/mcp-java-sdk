package io.github.vinhphan812.mcp.api;

/** Immutable protocol metadata and capability configuration for an MCP server. */
public final class McpServerConfig {
    public final String protocolVersion;
    public final String serverName;
    public final String serverVersion;
    public final boolean tools;
    public final boolean resources;
    public final boolean resourceSubscriptions;
    public final boolean prompts;

    private McpServerConfig(Builder builder) {
        protocolVersion = builder.protocolVersion;
        serverName = builder.serverName;
        serverVersion = builder.serverVersion;
        tools = builder.tools;
        resources = builder.resources;
        resourceSubscriptions = builder.resourceSubscriptions;
        prompts = builder.prompts;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String protocolVersion = "2025-11-25";
        private String serverName = "mcp-server";
        private String serverVersion = "1.0.0";
        private boolean tools = true;
        private boolean resources = true;
        private boolean resourceSubscriptions = true;
        private boolean prompts = true;

        public Builder protocolVersion(String value) {
            protocolVersion = requireText(value, "protocolVersion");
            return this;
        }

        public Builder serverName(String value) {
            serverName = requireText(value, "serverName");
            return this;
        }

        public Builder serverVersion(String value) {
            serverVersion = requireText(value, "serverVersion");
            return this;
        }

        public Builder tools(boolean value) {
            tools = value;
            return this;
        }

        public Builder resources(boolean value) {
            resources = value;
            return this;
        }

        public Builder resourceSubscriptions(boolean value) {
            resourceSubscriptions = value;
            return this;
        }

        public Builder prompts(boolean value) {
            prompts = value;
            return this;
        }

        public McpServerConfig build() {
            if (!resources) resourceSubscriptions = false;
            return new McpServerConfig(this);
        }

        private static String requireText(String value, String field) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(field + " cannot be empty");
            }
            return value;
        }
    }
}
