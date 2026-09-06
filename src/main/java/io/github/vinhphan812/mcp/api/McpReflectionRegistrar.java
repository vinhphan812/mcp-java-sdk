package io.github.vinhphan812.mcp.api;

import io.github.vinhphan812.mcp.annotations.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small optional runtime registrar for tests and projects that cannot enable
 * annotation processing. Production Android applications should prefer a
 * generated registrar so discovery is deterministic and startup is cheaper.
 */
public final class McpReflectionRegistrar {
    private McpReflectionRegistrar() {
    }

    public static void register(Object provider, McpRegistrar registrar) {
        if (provider == null) throw new IllegalArgumentException("provider cannot be null");
        if (registrar == null) throw new IllegalArgumentException("registrar cannot be null");

        Class<?> type = provider instanceof Class ? (Class<?>) provider : provider.getClass();
        Object target = provider instanceof Class ? null : provider;
        boolean tools = type.isAnnotationPresent(Tools.class);
        boolean resources = type.isAnnotationPresent(Resources.class);
        boolean prompts = type.isAnnotationPresent(Prompts.class);

        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) method.setAccessible(true);
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
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        for (Annotation[] annotations : parameterAnnotations) {
            for (McpParam meta : findParams(annotations)) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", meta.type());
                schema.put("description", meta.description());
                properties.put(meta.name(), schema);
                if (meta.required()) required.add(meta.name());
            }
        }
        validateReturn(method, Map.class, "tool");
        registrar.registerTool(name, annotation.description(), properties, required,
                arguments -> invokeMap(target, method, arguments));
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
                for (McpParam param : ((McpParams) annotation).value()) {
                    result.add(param);
                }
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
            if (method.getParameterTypes().length == 0) return method.invoke(target);
            if (method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0].isAssignableFrom(value.getClass())) {
                return method.invoke(target, value);
            }
            throw new IllegalArgumentException(method + " must accept zero or one compatible argument");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
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
