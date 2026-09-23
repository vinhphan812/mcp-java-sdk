package io.github.vinhphan812.mcp.examples.tools;

import io.github.vinhphan812.mcp.annotations.McpParam;
import io.github.vinhphan812.mcp.annotations.McpTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolExample {
    @McpTool(name = "greet", description = "Create a greeting for a person")
    public Map<String, Object> greet(
            @McpParam(name = "name", description = "Person name", required = true)
            String name) {
        return textResult("Hello, " + name + "!");
    }

    @McpTool(name = "user-summary", description = "Summarise a demo user profile")
    public Map<String, Object> userSummary(
            @McpParam(name = "userId", description = "User identifier", required = true)
            String userId) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("userId", userId);
        structured.put("profileUri", "demo://users/" + userId);
        structured.put("displayName", "Demo User " + userId);
        structured.put("summary", "Demo User " + userId + " is an example profile.");
        return structuredTextResult("User " + userId + " profile: demo://users/" + userId
                + ". Demo User " + userId + " is an example profile.", structured);
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
