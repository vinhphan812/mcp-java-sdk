package io.github.vinhphan812.mcp.api;

import com.google.gson.Gson;
import io.github.vinhphan812.mcp.annotations.*;
import io.github.vinhphan812.mcp.api.handler.McpToolHandler;
import io.github.vinhphan812.mcp.api.spi.McpRegistrar;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Small optional runtime registrar for tests and projects that cannot enable
 * annotation processing. Production Android applications should prefer a
 * generated registrar so discovery is deterministic and startup is cheaper.
 */
public final class McpReflectionRegistrar {
    private static final Gson GSON = new Gson();

    private McpReflectionRegistrar() {
    }

    /** Registers annotated methods from provider.
     * @param provider provider instance or provider class
     * @param registrar registration target */
    public static void register(Object provider, McpRegistrar registrar) {
        if (provider == null) throw new IllegalArgumentException("provider cannot be null");
        if (registrar == null) throw new IllegalArgumentException("registrar cannot be null");

        Class<?> type = provider instanceof Class ? (Class<?>) provider : provider.getClass();
        Object target = provider instanceof Class ? null : provider;
        boolean tools = type.isAnnotationPresent(Tools.class);
        boolean resources = type.isAnnotationPresent(Resources.class);
        boolean prompts = type.isAnnotationPresent(Prompts.class);

        for (Method method : type.getDeclaredMethods()) {
            method.setAccessible(true);
            if (method.isAnnotationPresent(McpTool.class) && (tools || !resources && !prompts)) {
                registerTool(target, method, method.getAnnotation(McpTool.class), registrar);
            }
            if (method.isAnnotationPresent(McpResource.class) && (resources || !tools && !prompts)) {
                registerResource(target, method, method.getAnnotation(McpResource.class), registrar);
            }
            if (method.isAnnotationPresent(McpResourceTemplate.class) && (resources || !tools && !prompts)) {
                registerResourceTemplate(target, method, method.getAnnotation(McpResourceTemplate.class), registrar);
            }
            if (method.isAnnotationPresent(McpPrompt.class) && (prompts || !tools && !resources)) {
                registerPrompt(target, method, method.getAnnotation(McpPrompt.class), registrar);
            }
        }
    }

    private static void registerTool(Object target, Method method, McpTool annotation,
                                     McpRegistrar registrar) {
        String name = nameOrMethod(annotation.name(), method);
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        parameterMetadata(method, properties, required);
        validateReturn(method, Map.class, "tool");
        Map<String, Object> outputSchema = parseOutputSchema(annotation.outputSchema());
        McpToolHandler delegate = arguments -> invokeMap(target, method, arguments);
        McpToolHandler handler = outputSchema != null
                ? new ToolHandlerWithSchema(delegate, outputSchema) : delegate;
        // Use 6-param overload to pass outputSchema; null is safe when no schema was declared
        if (outputSchema != null) {
            registrar.registerTool(name, annotation.description(), properties, required,
                    outputSchema, handler);
        } else {
            registrar.registerTool(name, annotation.description(), properties, required, handler);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseOutputSchema(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            return GSON.fromJson(json, Map.class);
        } catch (Exception e) {
            return null; // invalid JSON — skip schema
        }
    }

    private static final class ToolHandlerWithSchema implements McpToolHandler {
        private final McpToolHandler delegate;
        private final Map<String, Object> outputSchema;

        ToolHandlerWithSchema(McpToolHandler delegate, Map<String, Object> outputSchema) {
            this.delegate = delegate;
            this.outputSchema = outputSchema;
        }

        @Override
        public Map<String, Object> call(Map<String, Object> params) throws Exception {
            return delegate.call(params);
        }

        @Override
        public Map<String, Object> getOutputSchema() {
            return outputSchema;
        }
    }

    private static void registerResource(Object target, Method method, McpResource annotation,
                                         McpRegistrar registrar) {
        validateReturn(method, String.class, "resource");
        registrar.registerResource(annotation.uri(), annotation.name(), annotation.description(),
                annotation.mimeType(), uri -> invokeString(target, method, uri));
    }

    private static void registerResourceTemplate(Object target, Method method,
                                                 McpResourceTemplate annotation,
                                                 McpRegistrar registrar) {
        validateReturn(method, String.class, "resource template");
        registrar.registerResourceTemplate(annotation.uriTemplate(), annotation.name(),
                annotation.description(), annotation.mimeType(),
                uri -> invokeString(target, method, uri));
    }

    private static void registerPrompt(Object target, Method method, McpPrompt annotation,
                                       McpRegistrar registrar) {
        validateReturn(method, Map.class, "prompt");
        registrar.registerPrompt(nameOrMethod(annotation.name(), method), annotation.description(),
                promptArguments(method), arguments -> invokeMap(target, method, arguments));
    }

    private static void parameterMetadata(Method method, Map<String, Object> properties,
                                          List<String> required) {
        Annotation[][] allParamAnnotations = method.getParameterAnnotations();
        int paramCount = allParamAnnotations.length;
        int documentedCount = 0;
        for (Annotation[] annotations : allParamAnnotations) {
            for (McpParam meta : findParams(annotations)) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", meta.type());
                schema.put("description", meta.description());
                properties.put(meta.name(), schema);
                if (meta.required()) required.add(meta.name());
                documentedCount++;
            }
        }
        // Reject mixed: some params annotated, some not.
        // This creates a schema mismatch (annotated params appear in schema but
        // non-annotated params don't, yet both are positional method parameters).
        // A method with zero annotated params (e.g. a single Map<String,Object>
        // param passed through as-is) is valid — parameterMetadata skips it cleanly.
        if (documentedCount > 0 && documentedCount != paramCount) {
            throw new IllegalArgumentException(
                    "Method " + method + " has " + paramCount
                    + " parameters but only " + documentedCount
                    + " are annotated with @McpParam. "
                    + "Either annotate all parameters or use a single Map<String,Object> argument.");
        }
    }

    private static List<Map<String, Object>> promptArguments(Method method) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Annotation[] annotations : method.getParameterAnnotations()) {
            for (McpParam meta : findParams(annotations)) {
                Map<String, Object> argument = new LinkedHashMap<>();
                argument.put("name", meta.name());
                argument.put("description", meta.description());
                argument.put("required", meta.required());
                result.add(argument);
            }
        }
        return result;
    }

    private static List<McpParam> findParams(Annotation[] annotations) {
        List<McpParam> result = new ArrayList<>();
        for (Annotation annotation : annotations) {
            if (annotation instanceof McpParam) {
                result.add((McpParam) annotation);
            } else if (annotation instanceof McpParams) {
                result.addAll(Arrays.asList(((McpParams) annotation).value()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeMap(Object target, Method method,
                                                 Map<String, Object> arguments) throws Exception {
        Object result = invoke(target, method, arguments);
        if (!(result instanceof Map)) {
            throw new IllegalStateException(method + " must return Map<String,Object>");
        }
        return (Map<String, Object>) result;
    }

    private static String invokeString(Object target, Method method, String uri) throws Exception {
        Object result = invoke(target, method, uri);
        if (!(result instanceof String)) {
            throw new IllegalStateException(method + " must return String");
        }
        return (String) result;
    }

    private static Object invoke(Object target, Method method, Object value) throws Exception {
        try {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 0) return method.invoke(target);
            if (parameterTypes.length == 1 && parameterTypes[0].isAssignableFrom(value.getClass())) {
                return method.invoke(target, value);
            }
            return method.invoke(target, bindParameters(method, value));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }

    private static Object[] bindParameters(Method method, Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(method + " requires parameter object for @McpParam binding");
        }
        Map<?, ?> arguments = (Map<?, ?>) value;
        Class<?>[] types = method.getParameterTypes();
        Annotation[][] annotations = method.getParameterAnnotations();
        Object[] bound = new Object[types.length];
        int annotatedCount = 0;
        for (int i = 0; i < types.length; i++) {
            List<McpParam> params = findParams(annotations[i]);
            if (params.size() != 1) {
                throw new IllegalArgumentException(method + " parameter " + i
                        + " must have exactly one @McpParam for direct binding");
            }
            annotatedCount++;
            McpParam meta = params.get(0);
            Object raw = arguments.get(meta.name());
            if (raw == null) {
                if (meta.required() || types[i].isPrimitive()) {
                    throw new IllegalArgumentException("Missing required parameter '" + meta.name()
                            + "' for " + method);
                }
                bound[i] = null;
            } else {
                bound[i] = convert(raw, types[i], meta.name(), method);
            }
        }
        if (annotatedCount != types.length) {
            throw new IllegalArgumentException(method + " has unannotated parameters; annotate every direct parameter");
        }
        return bound;
    }

    private static Object convert(Object raw, Class<?> target, String name, Method method) {
        if (target == Object.class || target.isInstance(raw)) return raw;
        if (target == String.class) {
            if (raw instanceof String) return raw;
            throw invalidType(name, target, raw, method);
        }
        if (target == Map.class && raw instanceof Map) return raw;
        if (target == Boolean.class || target == Boolean.TYPE) {
            if (raw instanceof Boolean) return raw;
            throw invalidType(name, target, raw, method);
        }
        if (isNumeric(target) && raw instanceof Number) {
            Number n = (Number) raw;
            if (target == Byte.class || target == Byte.TYPE) {
                long v = n.longValue();
                if (v < Byte.MIN_VALUE || v > Byte.MAX_VALUE)
                    throw invalidOverflow(name, target, raw, method);
                return (byte) v;
            }
            if (target == Short.class || target == Short.TYPE) {
                long v = n.longValue();
                if (v < Short.MIN_VALUE || v > Short.MAX_VALUE)
                    throw invalidOverflow(name, target, raw, method);
                return (short) v;
            }
            if (target == Integer.class || target == Integer.TYPE) {
                long v = n.longValue();
                if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)
                    throw invalidOverflow(name, target, raw, method);
                return (int) v;
            }
            if (target == Long.class || target == Long.TYPE) return n.longValue();
            // float/double: no range check needed
            if (target == Float.class || target == Float.TYPE) return n.floatValue();
            if (target == Double.class || target == Double.TYPE) return n.doubleValue();
        }
        // List support: JSON array or Collection -> typed List
        if (List.class.isAssignableFrom(target) && raw instanceof Iterable) {
            @SuppressWarnings("unchecked")
            List<Object> list = new ArrayList<>();
            for (Object item : (Iterable<?>) raw) { list.add(item); }
            return list;
        }
        // Array support: JSON array -> typed array (serialise to JSON then parse to target element type)
        if (target.isArray()) {
            String json = GSON.toJson(raw);
            return GSON.fromJson(json, target);
        }
        // Complex type support: deserialise any remaining JSON value to a POJO via Gson.
        // This handles @McpParam on user-defined classes (e.g. Address, Order, Config).
        // The raw value must be a Map or JSON-primitive; otherwise Gson throws which we let surface.
        try {
            String json = GSON.toJson(raw);
            return GSON.fromJson(json, target);
        } catch (Exception e) {
            // Gson failed — fall through to the typed error below.
        }
        throw invalidType(name, target, raw, method);
    }

    private static boolean isNumeric(Class<?> type) {
        return type == Byte.class || type == Byte.TYPE || type == Short.class || type == Short.TYPE
                || type == Integer.class || type == Integer.TYPE || type == Long.class || type == Long.TYPE
                || type == Float.class || type == Float.TYPE || type == Double.class || type == Double.TYPE;
    }

    private static IllegalArgumentException invalidType(String name, Class<?> target, Object raw, Method method) {
        return new IllegalArgumentException("Invalid parameter '" + name + "' for " + method
                + ": expected " + target.getSimpleName() + " but got " + raw.getClass().getSimpleName());
    }

    private static IllegalArgumentException invalidOverflow(String name, Class<?> target, Object raw, Method method) {
        return new IllegalArgumentException("Parameter '" + name + "' for " + method
                + ": value " + raw + " overflows " + target.getSimpleName());
    }

    private static void validateReturn(Method method, Class<?> expected, String kind) {
        if (!expected.isAssignableFrom(method.getReturnType())) {
            throw new IllegalArgumentException(kind + " method must return " + expected.getSimpleName()
                    + ": " + method);
        }
    }

    private static String nameOrMethod(String name, Method method) {
        return name == null || name.trim().isEmpty() ? method.getName() : name;
    }
}
