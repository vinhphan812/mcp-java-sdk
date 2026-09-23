package io.github.vinhphan812.mcp.examples.tools;

import io.github.vinhphan812.mcp.annotations.McpParam;
import io.github.vinhphan812.mcp.annotations.McpTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolWithInputSchemaExample {
    @McpTool(name = "calculate-total", description = "Calculate a line-item total")
    public Map<String, Object> calculateTotal(
            @McpParam(name = "item", description = "Item label", required = true)
            String item,
            @McpParam(name = "quantity", description = "Number of items", type = "integer", required = true)
            int quantity,
            @McpParam(name = "unitPrice", description = "Price of one item", type = "number", required = true)
            double unitPrice) {
        double total = quantity * unitPrice;
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("item", item);
        structured.put("quantity", quantity);
        structured.put("unitPrice", unitPrice);
        structured.put("total", total);
        return structuredTextResult("Item: " + item + ", quantity: " + quantity
                + ", unit price: " + unitPrice + ", total: " + total, structured);
    }

    @McpTool(name = "validate-order", description = "Validate a demo order quantity and unit price")
    public Map<String, Object> validateOrder(
            @McpParam(name = "quantity", description = "Number of items", type = "integer", required = true)
            int quantity,
            @McpParam(name = "unitPrice", description = "Price of one item", type = "number", required = true)
            double unitPrice) {
        boolean valid = quantity > 0 && unitPrice >= 0;
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("valid", valid);
        structured.put("quantity", quantity);
        structured.put("unitPrice", unitPrice);
        structured.put("message", valid ? "Order values are valid." : "Quantity must be positive and unit price must not be negative.");
        return structuredTextResult(valid ? "Order is valid." : "Order is invalid.", structured);
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
