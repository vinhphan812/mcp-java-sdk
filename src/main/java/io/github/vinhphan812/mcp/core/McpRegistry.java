package io.github.vinhphan812.mcp.core;

import io.github.vinhphan812.mcp.api.McpPromptHandler;
import io.github.vinhphan812.mcp.api.McpRegistrar;
import io.github.vinhphan812.mcp.api.McpResourceUpdateListener;
import io.github.vinhphan812.mcp.api.McpResourceHandler;
import io.github.vinhphan812.mcp.api.McpToolHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project-neutral MCP definition registry. Consumers register their own
 * tools, resources, templates, and prompts through this class before the
 * protocol handler is started.
 */
public class McpRegistry implements McpRegistrar {
    private final List<Map<String, Object>> registeredTools = new ArrayList<>();
    private final List<Map<String, Object>> registeredResources = new ArrayList<>();
    private final List<Map<String, Object>> registeredResourceTemplates = new ArrayList<>();
    private final List<Map<String, Object>> registeredPrompts = new ArrayList<>();

    private final ConcurrentHashMap<String, McpToolHandler> toolHandlers =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, McpResourceHandler> resourceHandlers =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, McpResourceHandler> resourceTemplateHandlers =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, McpPromptHandler> promptHandlers =
            new ConcurrentHashMap<>();
    private volatile McpResourceUpdateListener notificationTarget;

    @Override
    public synchronized void registerTool(String name, String description,
                                           Map<String, Object> inputSchema,
                                           List<String> required,
                                           McpToolHandler handler) {
        requireUnique(name, toolHandlers, "tool");
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", inputSchema != null ? copyMap(inputSchema) : new LinkedHashMap<>());
        schema.put("required", required != null ? new ArrayList<>(required) : new ArrayList<>());
        tool.put("inputSchema", schema);
        registeredTools.add(tool);
        toolHandlers.put(name, handler);
    }

    @Override
    public synchronized void registerResource(String uri, String name, String description,
                                               String mimeType, McpResourceHandler handler) {
        requireUnique(uri, resourceHandlers, "resource");
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("uri", uri);
        resource.put("name", name);
        resource.put("description", description);
        resource.put("mimeType", normalizeMimeType(mimeType));
        registeredResources.add(resource);
        resourceHandlers.put(uri, handler);
    }

    @Override
    public synchronized void registerResourceTemplate(String uriTemplate, String name,
                                                       String description, String mimeType,
                                                       McpResourceHandler handler) {
        requireUnique(uriTemplate, resourceTemplateHandlers, "resource template");
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("uriTemplate", uriTemplate);
        template.put("name", name);
        template.put("description", description);
        template.put("mimeType", normalizeMimeType(mimeType));
        registeredResourceTemplates.add(template);
        resourceTemplateHandlers.put(uriTemplate, handler);
    }

    @Override
    public synchronized void registerPrompt(String name, String description,
                                             List<Map<String, Object>> arguments,
                                             McpPromptHandler handler) {
        requireUnique(name, promptHandlers, "prompt");
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("name", name);
        prompt.put("description", description);
        if (arguments != null) prompt.put("arguments", copyArgumentList(arguments));
        registeredPrompts.add(prompt);
        promptHandlers.put(name, handler);
    }

    public void setNotificationTarget(McpResourceUpdateListener target) {
        this.notificationTarget = target;
    }

    @Override
    public void notifyResourceUpdated(String uri) {
        McpResourceUpdateListener target = notificationTarget;
        if (target != null) target.notifyResourceUpdated(uri);
    }

    public List<Map<String, Object>> getRegisteredTools() {
        return copyDefinitions(registeredTools);
    }

    public List<Map<String, Object>> getRegisteredResources() {
        return copyDefinitions(registeredResources);
    }

    public List<Map<String, Object>> getRegisteredResourceTemplates() {
        return copyDefinitions(registeredResourceTemplates);
    }

    public List<Map<String, Object>> getRegisteredPrompts() {
        return copyDefinitions(registeredPrompts);
    }

    public McpToolHandler getToolHandler(String name) {
        return toolHandlers.get(name);
    }

    public McpResourceHandler getResourceHandler(String uri) {
        return resourceHandlers.get(uri);
    }

    public McpResourceHandler getResourceTemplateHandler(String uriTemplate) {
        return resourceTemplateHandlers.get(uriTemplate);
    }

    public Map<String, McpResourceHandler> getResourceTemplateHandlers() {
        return new LinkedHashMap<>(resourceTemplateHandlers);
    }

    public McpPromptHandler getPromptHandler(String name) {
        return promptHandlers.get(name);
    }

    public String getResourceMimeType(String uri) {
        return findMimeType(registeredResources, "uri", uri);
    }

    public String getResourceTemplateMimeType(String uriTemplate) {
        return findMimeType(registeredResourceTemplates, "uriTemplate", uriTemplate);
    }

    private static String findMimeType(List<Map<String, Object>> definitions, String key, String value) {
        synchronized (definitions) {
            for (Map<String, Object> definition : definitions) {
                if (value != null && value.equals(definition.get(key))) {
                    return (String) definition.get("mimeType");
                }
            }
        }
        return null;
    }

    private static String normalizeMimeType(String mimeType) {
        return mimeType == null || mimeType.trim().isEmpty() ? "application/json" : mimeType;
    }

    private static void requireUnique(String key, Map<?, ?> values, String type) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException(type + " name cannot be empty");
        }
        if (values.containsKey(key)) {
            throw new IllegalArgumentException("Duplicate " + type + ": " + key);
        }
    }

    private static List<Map<String, Object>> copyDefinitions(List<Map<String, Object>> source) {
        synchronized (source) {
            List<Map<String, Object>> copy = new ArrayList<>();
            for (Map<String, Object> definition : source) {
                copy.add(copyMap(definition));
            }
            return copy;
        }
    }

    private static List<Map<String, Object>> copyArgumentList(
            List<Map<String, Object>> source) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> argument : source) {
            copy.add(copyMap(argument));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                value = copyMap((Map<String, Object>) value);
            } else if (value instanceof List) {
                value = copyList((List<Object>) value);
            }
            copy.put(entry.getKey(), value);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> copyList(List<Object> source) {
        List<Object> copy = new ArrayList<>();
        for (Object value : source) {
            if (value instanceof Map) {
                value = copyMap((Map<String, Object>) value);
            } else if (value instanceof List) {
                value = copyList((List<Object>) value);
            }
            copy.add(value);
        }
        return copy;
    }
}
