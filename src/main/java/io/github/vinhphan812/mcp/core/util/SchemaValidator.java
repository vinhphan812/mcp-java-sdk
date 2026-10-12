package io.github.vinhphan812.mcp.core.util;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Lightweight JSON Schema validator using Gson and Guava.
 * Supports the subset of JSON Schema 2020-12 features actually used by the SDK:
 * type, properties, required, additionalProperties, enum, const,
 * minimum/maximum/exclusiveMaximum/exclusiveMinimum, minLength/maxLength,
 * pattern, items, minItems/maxItems, uniqueItems, and local {@code $ref}.
 * <p>
 * Excludes: remote {@code $ref}, {@code $defs}, format keywords,
 * contentEncoding, and other advanced features not exercised by tool schemas.
 */
public final class SchemaValidator {

    private static final Gson GSON = new Gson();

    private static final String KW_TYPE = "type";
    private static final String KW_PROPERTIES = "properties";
    private static final String KW_REQUIRED = "required";
    private static final String KW_ADDITIONAL_PROPERTIES = "additionalProperties";
    private static final String KW_ENUM = "enum";
    private static final String KW_CONST = "const";
    private static final String KW_MINIMUM = "minimum";
    private static final String KW_MAXIMUM = "maximum";
    private static final String KW_EXCLUSIVE_MAXIMUM = "exclusiveMaximum";
    private static final String KW_EXCLUSIVE_MINIMUM = "exclusiveMinimum";
    private static final String KW_MIN_LENGTH = "minLength";
    private static final String KW_MAX_LENGTH = "maxLength";
    private static final String KW_PATTERN = "pattern";
    private static final String KW_ITEMS = "items";
    private static final String KW_MIN_ITEMS = "minItems";
    private static final String KW_MAX_ITEMS = "maxItems";
    private static final String KW_UNIQUE_ITEMS = "uniqueItems";
    private static final String KW_REF = "$ref";

    private SchemaValidator() {}

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Checks that the given schema map is structurally valid.
     * Throws {@link IllegalArgumentException} on any structural error.
     */
    public static void validateSchema(Map<String, Object> schemaMap) {
        try {
            JsonElement schema = toJsonElement(schemaMap);
            // Basic structural checks on the root schema object
            JsonObject schemaObj = asObject(schema);
            if (!schemaObj.has(KW_TYPE)) {
                throw new IllegalArgumentException("schema is missing required 'type' keyword");
            }
            // Recurse to validate nested schemas recursively without data constraints
            validateSchemaNode(schemaObj);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid JSON Schema: " + e.getMessage(), e);
        }
    }

    /**
     * Recursively validates a schema node's structural keywords (type, properties, items, etc.)
     * without applying data-level constraints (enum ranges, minLength, etc.).
     * Throws on any structural error.
     */
    private static void validateSchemaNode(JsonElement schema) {
        if (schema == null || schema.isJsonNull()) return;
        JsonObject schemaObj = asObject(schema);

        // Check $ref is local only
        if (schemaObj.has(KW_REF)) {
            String ref = schemaObj.get(KW_REF).getAsString();
            if (!ref.startsWith("#/")) {
                throw new IllegalArgumentException(
                        "only local $ref (starting with '#/') is supported: " + ref);
            }
            return; // schema-level check doesn't resolve the ref
        }

        // Validate declared type is a known keyword
        if (schemaObj.has(KW_TYPE)) {
            JsonElement typeEl = schemaObj.get(KW_TYPE);
            if (typeEl.isJsonArray()) {
                for (JsonElement t : typeEl.getAsJsonArray()) {
                    validateTypeKeyword(t.getAsString());
                }
            } else {
                validateTypeKeyword(typeEl.getAsString());
            }
        }

        // Recurse into properties
        if (schemaObj.has(KW_PROPERTIES)) {
            JsonObject props = schemaObj.getAsJsonObject(KW_PROPERTIES);
            for (Map.Entry<String, JsonElement> entry : props.entrySet()) {
                validateSchemaNode(entry.getValue());
            }
        }

        // Recurse into items
        if (schemaObj.has(KW_ITEMS)) {
            validateSchemaNode(schemaObj.get(KW_ITEMS));
        }
    }

    private static void validateTypeKeyword(String type) {
        switch (type) {
            case "object": case "array": case "string":
            case "number": case "integer": case "boolean": case "null":
                break;
            default:
                throw new IllegalArgumentException("unsupported type: " + type);
        }
    }

    /**
     * Validates the supplied data map against the given schema map.
     * Throws {@link IllegalArgumentException} if validation fails.
     */
    public static void validateData(Map<String, Object> dataMap, Map<String, Object> schemaMap) {
        try {
            validateNode(toJsonElement(dataMap), toJsonElement(schemaMap), "");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Schema validator error: " + e.getMessage(), e);
        }
    }

    // ── Core validation dispatch ────────────────────────────────────────────────

    private static void validateNode(JsonElement data, JsonElement schema, String path) {
        if (schema == null || schema.isJsonNull()) return;

        JsonObject schemaObj = asObject(schema);

        // Resolve local $ref first
        if (schemaObj.has(KW_REF)) {
            String ref = schemaObj.get(KW_REF).getAsString();
            if (!ref.startsWith("#/")) {
                throw new IllegalArgumentException(
                        "Invalid JSON Schema: only local $ref (starting with '#/') is supported. " + ref);
            }
            JsonElement resolved = resolvePointer(schemaObj, ref);
            if (resolved == null) {
                throw new IllegalArgumentException("Invalid JSON Schema: unresolved $ref: " + ref);
            }
            validateNode(data, resolved, path);
            return;
        }

        List<String> expectedTypes = getTypes(schemaObj);

        for (String expectedType : expectedTypes) {
            if (matchesType(data, expectedType)) {
                validateByType(data, schemaObj, expectedType, path);
                return;
            }
        }

        if (data == null || data.isJsonNull()) {
            throw new IllegalArgumentException(atPath(path) + "expected " + joinTypes(expectedTypes)
                    + " but got null");
        }
        throw new IllegalArgumentException(atPath(path) + "type schema mismatch: expected "
                + joinTypes(expectedTypes) + " but got " + jsonTypeOf(data));
    }

    private static List<String> getTypes(JsonObject schemaObj) {
        if (!schemaObj.has(KW_TYPE)) {
            return ImmutableList.of();
        }
        JsonElement typeEl = schemaObj.get(KW_TYPE);
        if (typeEl.isJsonArray()) {
            List<String> types = new ArrayList<>();
            for (JsonElement e : typeEl.getAsJsonArray()) {
                types.add(e.getAsString());
            }
            return types;
        }
        return ImmutableList.of(typeEl.getAsString());
    }

    private static String joinTypes(List<String> types) {
        return String.join(" or ", types);
    }

    private static void validateByType(JsonElement data, JsonObject schemaObj,
                                       String expectedType, String path) {
        switch (expectedType) {
            case "object":
                validateObject(data, schemaObj, path);
                break;
            case "array":
                validateArray(data, schemaObj, path);
                break;
            case "string":
                validateString(data, schemaObj, path);
                break;
            case "number":
            case "integer":
                validateNumber(data, schemaObj, expectedType, path);
                break;
            case "boolean":
                break;
            case "null":
                if (data != null && !data.isJsonNull()) {
                    throw new IllegalArgumentException(atPath(path) + "expected null but got " + jsonTypeOf(data));
                }
                break;
            default:
                throw new IllegalArgumentException(atPath(path) + "unsupported type: " + expectedType);
        }
    }

    // ── Object validation ───────────────────────────────────────────────────────

    private static void validateObject(JsonElement data, JsonObject schemaObj, String path) {
        if (data == null || !data.isJsonObject()) return;
        JsonObject obj = data.getAsJsonObject();

        if (schemaObj.has(KW_REQUIRED)) {
            for (JsonElement reqEl : schemaObj.getAsJsonArray(KW_REQUIRED)) {
                String req = reqEl.getAsString();
                if (!obj.has(req) || obj.get(req).isJsonNull()) {
                    throw new IllegalArgumentException(
                            atPath(path) + "required property '" + req + "' is missing");
                }
            }
        }

        if (schemaObj.has(KW_PROPERTIES)) {
            JsonObject props = schemaObj.getAsJsonObject(KW_PROPERTIES);
            for (Map.Entry<String, JsonElement> entry : props.entrySet()) {
                String propName = entry.getKey();
                if (obj.has(propName)) {
                    String childPath = path.isEmpty() ? propName : path + "." + propName;
                    validateNode(obj.get(propName), entry.getValue(), childPath);
                }
            }
        }

        if (schemaObj.has(KW_ADDITIONAL_PROPERTIES)) {
            JsonElement apEl = schemaObj.get(KW_ADDITIONAL_PROPERTIES);
            if (!apEl.getAsBoolean()) {
                Set<String> defined = new HashSet<>();
                if (schemaObj.has(KW_PROPERTIES)) {
                    for (String key : schemaObj.getAsJsonObject(KW_PROPERTIES).keySet()) {
                        defined.add(key);
                    }
                }
                for (String present : obj.keySet()) {
                    if (!defined.contains(present)) {
                        throw new IllegalArgumentException(
                                atPath(path) + "additional property '" + present + "' is not allowed");
                    }
                }
            }
        }
    }

    // ── Array validation ────────────────────────────────────────────────────────

    private static void validateArray(JsonElement data, JsonObject schemaObj, String path) {
        if (data == null || !data.isJsonArray()) return;
        JsonArray arr = data.getAsJsonArray();

        if (schemaObj.has(KW_MIN_ITEMS)) {
            int min = schemaObj.get(KW_MIN_ITEMS).getAsInt();
            if (arr.size() < min) {
                throw new IllegalArgumentException(
                        atPath(path) + "array size is " + arr.size() + ", minimum is " + min);
            }
        }
        if (schemaObj.has(KW_MAX_ITEMS)) {
            int max = schemaObj.get(KW_MAX_ITEMS).getAsInt();
            if (arr.size() > max) {
                throw new IllegalArgumentException(
                        atPath(path) + "array size is " + arr.size() + ", maximum is " + max);
            }
        }
        if (schemaObj.has(KW_UNIQUE_ITEMS) && schemaObj.get(KW_UNIQUE_ITEMS).getAsBoolean()) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < arr.size(); i++) {
                String serialized = arr.get(i).toString();
                if (!seen.add(serialized)) {
                    throw new IllegalArgumentException(
                            atPath(path) + "array items are not unique at index " + i);
                }
            }
        }
        if (schemaObj.has(KW_ITEMS)) {
            JsonElement itemsSchema = schemaObj.get(KW_ITEMS);
            for (int i = 0; i < arr.size(); i++) {
                validateNode(arr.get(i), itemsSchema, path + "[" + i + "]");
            }
        }
    }

    // ── String validation ───────────────────────────────────────────────────────

    private static void validateString(JsonElement data, JsonObject schemaObj, String path) {
        if (data == null || !data.isJsonPrimitive()) return;
        String value = data.getAsString();

        if (schemaObj.has(KW_PATTERN)) {
            String regex = schemaObj.get(KW_PATTERN).getAsString();
            try {
                Pattern pattern = Pattern.compile(regex);
                if (!pattern.matcher(value).matches()) {
                    throw new IllegalArgumentException(
                            atPath(path) + "string does not match pattern '" + regex + "'");
                }
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        atPath(path) + "invalid regex pattern: " + regex);
            }
        }
        if (schemaObj.has(KW_MIN_LENGTH)) {
            int min = schemaObj.get(KW_MIN_LENGTH).getAsInt();
            if (value.length() < min) {
                throw new IllegalArgumentException(
                        atPath(path) + "string length is " + value.length() + ", minimum is " + min);
            }
        }
        if (schemaObj.has(KW_MAX_LENGTH)) {
            int max = schemaObj.get(KW_MAX_LENGTH).getAsInt();
            if (value.length() > max) {
                throw new IllegalArgumentException(
                        atPath(path) + "string length is " + value.length() + ", maximum is " + max);
            }
        }
        if (schemaObj.has(KW_ENUM)) {
            JsonArray enumValues = schemaObj.getAsJsonArray(KW_ENUM);
            boolean found = false;
            for (JsonElement e : enumValues) {
                if (elementsEqual(data, e)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException(
                        atPath(path) + "value is not one of the allowed enum values");
            }
        }
        if (schemaObj.has(KW_CONST)) {
            JsonElement constEl = schemaObj.get(KW_CONST);
            if (!elementsEqual(data, constEl)) {
                throw new IllegalArgumentException(
                        atPath(path) + "value must be " + constEl + " but got " + data);
            }
        }
    }

    // ── Numeric validation ──────────────────────────────────────────────────────

    private static void validateNumber(JsonElement data, JsonObject schemaObj,
                                        String expectedType, String path) {
        if (data == null || !data.isJsonPrimitive()) return;
        JsonPrimitive prim = data.getAsJsonPrimitive();

        if (!prim.isNumber()) {
            throw new IllegalArgumentException(
                    atPath(path) + "expected " + expectedType + " but got " + jsonTypeOf(data));
        }
        double value = prim.getAsDouble();

        if ("integer".equals(expectedType) && !isIntegerValue(prim)) {
            throw new IllegalArgumentException(
                    atPath(path) + "expected integer but got non-integer number " + value);
        }

        if (schemaObj.has(KW_MINIMUM)) {
            double min = schemaObj.get(KW_MINIMUM).getAsDouble();
            if (value < min) {
                throw new IllegalArgumentException(
                        atPath(path) + "value " + value + " is less than minimum " + min);
            }
        }
        if (schemaObj.has(KW_MAXIMUM)) {
            double max = schemaObj.get(KW_MAXIMUM).getAsDouble();
            if (value > max) {
                throw new IllegalArgumentException(
                        atPath(path) + "value " + value + " is greater than maximum " + max);
            }
        }
        if (schemaObj.has(KW_EXCLUSIVE_MINIMUM)) {
            double min = schemaObj.get(KW_EXCLUSIVE_MINIMUM).getAsDouble();
            if (value <= min) {
                throw new IllegalArgumentException(
                        atPath(path) + "value " + value + " must be strictly greater than " + min);
            }
        }
        if (schemaObj.has(KW_EXCLUSIVE_MAXIMUM)) {
            double max = schemaObj.get(KW_EXCLUSIVE_MAXIMUM).getAsDouble();
            if (value >= max) {
                throw new IllegalArgumentException(
                        atPath(path) + "value " + value + " must be strictly less than " + max);
            }
        }
        if (schemaObj.has(KW_ENUM)) {
            JsonArray enumValues = schemaObj.getAsJsonArray(KW_ENUM);
            boolean found = false;
            for (JsonElement e : enumValues) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                        && Double.compare(e.getAsDouble(), prim.getAsDouble()) == 0) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException(
                        atPath(path) + "value " + value + " is not one of the allowed enum values");
            }
        }
        if (schemaObj.has(KW_CONST)) {
            JsonElement constEl = schemaObj.get(KW_CONST);
            if (!constEl.isJsonPrimitive() || !constEl.getAsJsonPrimitive().isNumber()
                    || Double.compare(constEl.getAsDouble(), prim.getAsDouble()) != 0) {
                throw new IllegalArgumentException(
                        atPath(path) + "value must be " + constEl + " but got " + data);
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static boolean matchesType(JsonElement data, String expectedType) {
        if (data == null || data.isJsonNull()) {
            return "null".equals(expectedType);
        }
        switch (expectedType) {
            case "object":   return data.isJsonObject();
            case "array":    return data.isJsonArray();
            case "string":   return data.isJsonPrimitive() && data.getAsJsonPrimitive().isString();
            case "number":   return data.isJsonPrimitive() && data.getAsJsonPrimitive().isNumber();
            case "integer":  return data.isJsonPrimitive() && data.getAsJsonPrimitive().isNumber()
                    && isIntegerValue(data.getAsJsonPrimitive());
            case "boolean":  return data.isJsonPrimitive() && data.getAsJsonPrimitive().isBoolean();
            case "null":     return data.isJsonNull();
            default:         return false;
        }
    }

    private static boolean isIntegerValue(JsonPrimitive prim) {
        double d = prim.getAsDouble();
        return d == Math.rint(d) && !Double.isInfinite(d);
    }

    private static JsonElement toJsonElement(Object obj) {
        if (obj instanceof JsonElement) return (JsonElement) obj;
        return GSON.toJsonTree(obj);
    }

    private static JsonObject asObject(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return new JsonObject();
        }
        return el.getAsJsonObject();
    }

    private static String jsonTypeOf(JsonElement el) {
        if (el == null || el.isJsonNull()) return "null";
        if (el.isJsonObject()) return "object";
        if (el.isJsonArray()) return "array";
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isString()) return "string";
            if (p.isNumber()) return "number";
            if (p.isBoolean()) return "boolean";
        }
        return "unknown";
    }

    private static boolean elementsEqual(JsonElement a, JsonElement b) {
        return a.toString().equals(b.toString());
    }

    private static String atPath(String path) {
        return path.isEmpty() ? "" : "[" + path + "] ";
    }

    /**
     * Resolves a JSON Pointer fragment (e.g. "#/properties/name") against a schema root.
     * Only handles the "#/" prefix and absolute path segments.
     */
    private static JsonElement resolvePointer(JsonObject root, String ref) {
        if (!ref.startsWith("#/")) return null;
        String[] segments = ref.substring(2).split("/");
        JsonElement current = root;
        for (String seg : segments) {
            if (current == null || !current.isJsonObject()) return null;
            seg = seg.replace("~1", "/").replace("~0", "~");
            current = current.getAsJsonObject().get(seg);
        }
        return current;
    }
}
