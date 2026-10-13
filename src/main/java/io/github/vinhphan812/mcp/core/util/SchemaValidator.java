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
 * Lightweight JSON Schema 2020-12 validator using Gson and Guava.
 * <p>
 * Dialect: JSON Schema 2020-12 (§4.3). Supported: all structural keywords used by
 * MCP tool schemas — type, properties, required, additionalProperties, enum, const,
 * minimum/maximum/exclusiveMaximum/exclusiveMinimum, minLength/maxLength,
 * pattern, items, minItems/maxItems, uniqueItems, local {@code $ref}
 * (including {@code $defs}/$ref), combinators (oneOf, anyOf, allOf),
 * and conditional schemas (if/then/else).
 * <p>
 * Explicitly excluded / not enforced: format, contentEncoding, prefixItems,
 * dependentRequired, unevaluatedProperties, remote {@code $ref}. A schema containing
 * an unsupported keyword is <b>not rejected</b> — the keyword is silently ignored.
 * This is intentional: it allows schemas from broader tooling to pass through without
 * false negatives.
 * <p>
 * Dialect handling: the {@code $schema} keyword is accepted and ignored (not used for
 * validation). The validator targets JSON Schema 2020-12 behaviour for supported
 * vocabulary only.
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
    private static final String KW_DEFS = "$defs";
    // ── 2020-12 combinators ────────────────────────────────────────────────────
    private static final String KW_ONE_OF = "oneOf";
    private static final String KW_ANY_OF = "anyOf";
    private static final String KW_ALL_OF = "allOf";
    // ── 2020-12 conditionals ──────────────────────────────────────────────────
    private static final String KW_IF = "if";
    private static final String KW_THEN = "then";
    private static final String KW_ELSE = "else";

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
            // JSON Schema 2020-12 does not require 'type' at root.
            // Boolean schemas (true/false) are valid.
            if (schemaObj.size() == 0) return; // empty object is valid (equivalent to true)
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
        // JSON Schema 2020-12 §4.3: boolean schemas are valid (true = allow all, false = deny all)
        if (schema.isJsonPrimitive() && schema.getAsJsonPrimitive().isBoolean()) return;
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

        // Recurse into combinators
        if (schemaObj.has(KW_ONE_OF)) {
            if (!schemaObj.get(KW_ONE_OF).isJsonArray()) {
                throw new IllegalArgumentException("oneOf must be an array");
            }
            for (JsonElement s : schemaObj.getAsJsonArray(KW_ONE_OF)) {
                validateSchemaNode(s);
            }
        }
        if (schemaObj.has(KW_ANY_OF)) {
            if (!schemaObj.get(KW_ANY_OF).isJsonArray()) {
                throw new IllegalArgumentException("anyOf must be an array");
            }
            for (JsonElement s : schemaObj.getAsJsonArray(KW_ANY_OF)) {
                validateSchemaNode(s);
            }
        }
        if (schemaObj.has(KW_ALL_OF)) {
            if (!schemaObj.get(KW_ALL_OF).isJsonArray()) {
                throw new IllegalArgumentException("allOf must be an array");
            }
            for (JsonElement s : schemaObj.getAsJsonArray(KW_ALL_OF)) {
                validateSchemaNode(s);
            }
        }

        // Recurse into $defs
        if (schemaObj.has(KW_DEFS)) {
            if (!schemaObj.get(KW_DEFS).isJsonObject()) {
                throw new IllegalArgumentException("$defs must be an object");
            }
            JsonObject defs = schemaObj.getAsJsonObject(KW_DEFS);
            for (Map.Entry<String, JsonElement> entry : defs.entrySet()) {
                validateSchemaNode(entry.getValue());
            }
        }

        // Recurse into conditionals
        if (schemaObj.has(KW_IF)) {
            JsonElement ifEl = schemaObj.get(KW_IF);
            if (!ifEl.isJsonObject() && !ifEl.isJsonArray()) {
                throw new IllegalArgumentException("if must be a schema object or array");
            }
            validateSchemaNode(ifEl);
        }
        if (schemaObj.has(KW_THEN)) {
            validateSchemaNode(schemaObj.get(KW_THEN));
        }
        if (schemaObj.has(KW_ELSE)) {
            validateSchemaNode(schemaObj.get(KW_ELSE));
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
            JsonElement data = toJsonElement(dataMap);
            JsonElement schema = toJsonElement(schemaMap);
            validateNode(data, schema, schema, "");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Schema validator error: " + e.getMessage(), e);
        }
    }

    // ── Core validation dispatch ────────────────────────────────────────────────

    /**
     * Validates data against schema at the given JSON Pointer path.
     *
     * @param data  the instance data to validate
     * @param schema the current schema node
     * @param root  the root schema (used to resolve $ref and $defs)
     * @param path  the JSON Pointer path for error messages
     */
    private static void validateNode(JsonElement data, JsonElement schema,
                                     JsonElement root, String path) {
        if (schema == null || schema.isJsonNull()) return;

        JsonObject schemaObj = asObject(schema);
        JsonObject rootObj = asObject(root);

        // Resolve local $ref first (always resolves against root schema)
        if (schemaObj.has(KW_REF)) {
            String ref = schemaObj.get(KW_REF).getAsString();
            if (!ref.startsWith("#/")) {
                throw new IllegalArgumentException(
                        "Invalid JSON Schema: only local $ref (starting with '#/') is supported. " + ref);
            }
            JsonElement resolved = resolvePointer(rootObj, ref);
            if (resolved == null) {
                throw new IllegalArgumentException("Invalid JSON Schema: unresolved $ref: " + ref);
            }
            validateNode(data, resolved, root, path);
            return;
        }

        // ── Combinators (oneOf / anyOf / allOf) ─────────────────────────────────
        if (schemaObj.has(KW_ONE_OF)) {
            int passing = 0;
            List<String> errors = new ArrayList<>();
            for (JsonElement sub : schemaObj.getAsJsonArray(KW_ONE_OF)) {
                try {
                    validateNode(data, sub, root, path);
                    passing++;
                } catch (IllegalArgumentException e) {
                    errors.add(e.getMessage());
                }
            }
            if (passing != 1) {
                throw new IllegalArgumentException(atPath(path)
                        + "expected exactly one of [" + (errors.isEmpty() ? "" : String.join("; ", errors)) + "] to validate; "
                        + passing + " schemas matched");
            }
            return;
        }

        if (schemaObj.has(KW_ANY_OF)) {
            for (JsonElement sub : schemaObj.getAsJsonArray(KW_ANY_OF)) {
                try {
                    validateNode(data, sub, root, path);
                    return; // at least one matched
                } catch (IllegalArgumentException ignored) {
                }
            }
            throw new IllegalArgumentException(atPath(path)
                    + "expected data to match at least one anyOf schema");
        }

        if (schemaObj.has(KW_ALL_OF)) {
            for (JsonElement sub : schemaObj.getAsJsonArray(KW_ALL_OF)) {
                validateNode(data, sub, root, path); // all must pass
            }
            return;
        }

        // ── Conditional (if / then / else) ─────────────────────────────────────
        if (schemaObj.has(KW_IF)) {
            JsonElement ifSchema = schemaObj.get(KW_IF);
            boolean ifMatches;
            try {
                validateNode(data, ifSchema, root, path);
                ifMatches = true;
            } catch (IllegalArgumentException e) {
                ifMatches = false;
            }
            if (ifMatches) {
                if (schemaObj.has(KW_THEN)) {
                    validateNode(data, schemaObj.get(KW_THEN), root, path);
                }
            } else {
                if (schemaObj.has(KW_ELSE)) {
                    validateNode(data, schemaObj.get(KW_ELSE), root, path);
                }
            }
            return;
        }

        List<String> expectedTypes = getTypes(schemaObj);

        if (!expectedTypes.isEmpty()) {
            for (String expectedType : expectedTypes) {
                if (matchesType(data, expectedType)) {
                    validateByType(data, schemaObj, root, expectedType, path);
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

        // Schema has no type keyword — validate non-type constraints against the data's actual type.
        validateConstraints(data, schemaObj, path);
    }

    /**
     * Validates non-type constraints (minLength, pattern, enum, const, numeric ranges, etc.)
     * against the data's actual JSON type, when the schema has no explicit type keyword.
     */
    private static void validateConstraints(JsonElement data, JsonObject schemaObj, String path) {
        if (data == null || data.isJsonNull()) return;
        if (data.isJsonPrimitive() && data.getAsJsonPrimitive().isString()) {
            validateString(data, schemaObj, path);
        } else if (data.isJsonPrimitive() && data.getAsJsonPrimitive().isNumber()) {
            validateNumber(data, schemaObj, "number", path);
        } else if (data.isJsonObject()) {
            validateObject(data, schemaObj, schemaObj, path);
        } else if (data.isJsonArray()) {
            validateArray(data, schemaObj, schemaObj, path);
        }
        // boolean: no numeric/string/array/object constraints apply
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
                                       JsonElement root,
                                       String expectedType, String path) {
        switch (expectedType) {
            case "object":
                validateObject(data, schemaObj, root, path);
                break;
            case "array":
                validateArray(data, schemaObj, root, path);
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

    private static void validateObject(JsonElement data, JsonObject schemaObj,
                                       JsonElement root, String path) {
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
                    validateNode(obj.get(propName), entry.getValue(), root, childPath);
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

    private static void validateArray(JsonElement data, JsonObject schemaObj,
                                    JsonElement root, String path) {
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
                validateNode(arr.get(i), itemsSchema, root, path + "[" + i + "]");
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
     * Resolves a JSON Pointer fragment (e.g. "#/$defs/Foo" or "#/properties/name")
     * against a schema root.  Handles the "#/" prefix and unescapes "~0"/"~1".
     * Supports references into "$defs" and nested properties.
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
