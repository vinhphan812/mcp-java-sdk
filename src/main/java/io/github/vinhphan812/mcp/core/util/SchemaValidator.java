package io.github.vinhphan812.mcp.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.networknt.schema.InvalidSchemaRefException;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class SchemaValidator {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Gson GSON = new Gson();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public static void validateSchema(Map<String, Object> schemaMap) {
        String jsonSchema = GSON.toJson(schemaMap);
        try {
            FACTORY.getSchema(jsonSchema);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON Schema 2020-12: " + e.getMessage(), e);
        }
    }

    public static void validateData(Map<String, Object> dataMap, Map<String, Object> schemaMap) {
        String jsonSchema = GSON.toJson(schemaMap);
        String jsonData = GSON.toJson(dataMap);
        try {
            JsonSchema schema = FACTORY.getSchema(jsonSchema);
            JsonNode node = MAPPER.readTree(jsonData);
            Set<ValidationMessage> errors = schema.validate(node);
            if (!errors.isEmpty()) {
                String errorMsg = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .collect(Collectors.joining(", "));
                throw new IllegalArgumentException("Schema validation failed: " + errorMsg);
            }
        } catch (IllegalArgumentException | InvalidSchemaRefException e) {
            // Re-throw IAE from validation; unwrap InvalidSchemaRefException (thrown when
            // a $ref target cannot be resolved at validation time).
            if (e instanceof InvalidSchemaRefException) {
                throw new IllegalArgumentException("Invalid JSON Schema 2020-12: " + e.getMessage(), e);
            }
            throw e;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON data: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Schema validator error: " + e.getMessage(), e);
        }
    }
}
