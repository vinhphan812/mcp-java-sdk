package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.annotations.McpResourceTemplate;
import io.github.vinhphan812.mcp.annotations.Resources;

@Resources
public final class ResourceTemplateExample {
    @McpResourceTemplate(uriTemplate = "demo://users/{userId}", name = "User profile",
            description = "Loads a demo user profile", mimeType = "application/json")
    public String userProfile(String uri) {
        String userId = uri.substring(uri.lastIndexOf('/') + 1);
        return "{\"userId\":\"" + userId + "\",\"displayName\":\"Demo User\"}";
    }

    @McpResourceTemplate(uriTemplate = "demo://orders/{orderId}", name = "Order receipt",
            description = "Loads a demo order receipt", mimeType = "application/json")
    public String orderReceipt(String uri) {
        String orderId = uri.substring(uri.lastIndexOf('/') + 1);
        return "{\"orderId\":\"" + orderId + "\",\"status\":\"ready\",\"currency\":\"GBP\"}";
    }

    @McpResourceTemplate(uriTemplate = "demo://products/{productId}", name = "Product detail",
            description = "Loads a demo product", mimeType = "application/json")
    public String productDetail(String uri) {
        String productId = uri.substring(uri.lastIndexOf('/') + 1);
        return "{\"productId\":\"" + productId + "\",\"name\":\"Demo " + productId + "\",\"available\":true}";
    }

    @McpResourceTemplate(uriTemplate = "demo://users/{userId}/preferences", name = "User preferences",
            description = "Loads preferences for a demo user", mimeType = "application/json")
    public String userPreferences(String uri) {
        String path = uri.substring(uri.indexOf("users/") + 6);
        String userId = path.substring(0, path.indexOf('/'));
        return "{\"userId\":\"" + userId + "\",\"language\":\"en-GB\",\"notifications\":true}";
    }
}
