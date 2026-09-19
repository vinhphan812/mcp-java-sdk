package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.annotations.McpResource;
import io.github.vinhphan812.mcp.annotations.Resources;

@Resources
public final class ResourceExample {
    @McpResource(uri = "demo://readme", name = "Demo README",
            description = "Static documentation resource", mimeType = "text/plain")
    public String readme(String ignoredUri) {
        return "MCP Java SDK demo\nTools, resources, templates, and prompts.";
    }

    @McpResource(uri = "demo://catalog", name = "Demo catalogue",
            description = "Static catalogue used by the example", mimeType = "application/json")
    public String catalog(String ignoredUri) {
        return "{\"items\":[{\"id\":\"coffee\",\"unitPrice\":3.5},{\"id\":\"tea\",\"unitPrice\":2.5}]}";
    }

    @McpResource(uri = "demo://policies", name = "Demo policies",
            description = "Example service policies", mimeType = "text/markdown")
    public String policies(String ignoredUri) {
        return "# Demo policies\n\n- Orders are validated before submission.\n- Prices are expressed in GBP.\n- This resource is static example data.";
    }
}
