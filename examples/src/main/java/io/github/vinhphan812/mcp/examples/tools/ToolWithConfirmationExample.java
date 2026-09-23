package io.github.vinhphan812.mcp.examples.tools;

import io.github.vinhphan812.mcp.annotations.McpParam;
import io.github.vinhphan812.mcp.annotations.McpTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolWithConfirmationExample {
    @McpTool(name = "search-catalog", description = "Search the demo catalogue by item name", confirmationRequired = true)
    public Map<String, Object> searchCatalog(
            @McpParam(name = "query", description = "Search text", required = true)
            String query) {
        String term = query.toLowerCase();
        List<Map<String, Object>> matches = new ArrayList<>();
        if ("coffee".contains(term)) matches.add(catalogItem("coffee", 3.5, "beverage"));
        if ("tea".contains(term)) matches.add(catalogItem("tea", 2.5, "beverage"));
        if ("sandwich".contains(term)) matches.add(catalogItem("sandwich", 6.0, "food"));
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("query", query);
        structured.put("matches", matches);
        return structuredTextResult("Found " + matches.size() + " catalogue item(s) for '" + query + "'.", structured);
    }

    private static Map<String, Object> catalogItem(String id, double unitPrice, String category) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("unitPrice", unitPrice);
        item.put("category", category);
        return item;
    }

    private static Map<String, Object> textResult(String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text);
        List<Map<String, Object>> contents = new ArrayList<>();
        contents.add(content);
        result.put("content", contents);
        return result;
    }

    private static Map<String, Object> structuredTextResult(String text, Map<String, Object> structuredContent) {
        Map<String, Object> result = textResult(text);
        result.put("structuredContent", structuredContent);
        return result;
    }
}
