package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.annotations.McpParam;
import io.github.vinhphan812.mcp.annotations.McpPrompt;
import io.github.vinhphan812.mcp.annotations.Prompts;

import java.util.Map;

@Prompts
public final class PromptWithArgsExample {
    @McpPrompt(name = "review-order", description = "Ask a model to review an order")
    public Map<String, Object> reviewOrder(
            @McpParam(name = "orderId", description = "Order identifier", required = true)
            String orderId,
            @McpParam(name = "focus", description = "Review focus", required = false)
            String focus) {
        String requestedFocus = focus == null || focus.trim().isEmpty() ? "items and status" : focus;
        return MainExample.promptResult("Review demo://orders/" + orderId + " with focus on " + requestedFocus + ".");
    }

    @McpPrompt(name = "summarise-catalog", description = "Ask a model to summarise the catalogue")
    public Map<String, Object> summariseCatalog(
            @McpParam(name = "audience", description = "Intended audience", required = false)
            String audience) {
        String target = audience == null || audience.trim().isEmpty() ? "a general audience" : audience;
        return MainExample.promptResult("Summarise demo://catalog for " + target + ". Include prices and item categories.");
    }

    @McpPrompt(name = "troubleshoot-service", description = "Ask a model to troubleshoot a service issue")
    public Map<String, Object> troubleshootService(
            @McpParam(name = "symptom", description = "Observed symptom", required = true)
            String symptom) {
        return MainExample.promptResult("Help troubleshoot this service symptom: " + symptom
                + ". Check demo://policies and suggest safe next steps.");
    }
}
