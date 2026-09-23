package io.github.vinhphan812.mcp.examples.tools;

import com.google.gson.annotations.SerializedName;
import io.github.vinhphan812.mcp.annotations.McpParam;
import io.github.vinhphan812.mcp.annotations.McpTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolWithScopesExample {
    @McpTool(name = "format-address", description = "Format an address for display", scopes = {"format:address"})
    public Map<String, Object> formatAddress(
            @McpParam(name = "address", description = "Full address object", required = true)
            Address address) {
        String formatted = address.getStreet() + ", " + address.getCity() + ", "
                + address.getCountry();
        return textResult(formatted);
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

    public static class Address {
        @SerializedName("street")
        private final String street;
        @SerializedName("city")
        private final String city;
        @SerializedName("country")
        private final String country;

        public Address(String street, String city, String country) {
            this.street = street;
            this.city = city;
            this.country = country;
        }

        public String getStreet() {
            return street;
        }

        public String getCity() {
            return city;
        }

        public String getCountry() {
            return country;
        }
    }
}
