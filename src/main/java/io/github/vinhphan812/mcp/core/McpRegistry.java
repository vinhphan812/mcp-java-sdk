package io.github.vinhphan812.mcp.core;

import io.github.vinhphan812.mcp.api.dto.McpTask;
import io.github.vinhphan812.mcp.api.handler.*;
import io.github.vinhphan812.mcp.api.spi.McpRegistrar;
import io.github.vinhphan812.mcp.api.spi.McpRegistryChangeListener;
import io.github.vinhphan812.mcp.api.spi.McpResourceUpdateListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

    private final ConcurrentHashMap<String, McpToolHandler> toolHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, McpResourceHandler> resourceHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, McpResourceHandler> resourceTemplateHandlers = new ConcurrentHashMap<>();
    /** Blob handlers keyed by URI template — superset of resourceTemplateHandlers. */
    private final ConcurrentHashMap<String, McpBlobResourceHandler> blobResourceTemplateHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, McpPromptHandler> promptHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, McpTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, McpCompletionProvider> completionProviders = new ConcurrentHashMap<>();
    /** Per-session set of cancelled MCP request ids (JSON id value as String). */
    private final ConcurrentHashMap<String, Set<String>> cancelledRequests = new ConcurrentHashMap<>();
    private volatile McpResourceUpdateListener notificationTarget;
    private final CopyOnWriteArrayList<McpRegistryChangeListener> changeListeners = new CopyOnWriteArrayList<>();

    /** Registers an MCP tool definition and handler.
     * @param name tool name
     * @param description tool description
     * @param inputSchema tool input property definitions
     * @param required required input names
     * @param handler tool handler
     */
    @Override
    public synchronized void registerTool(String name, String description, Map<String, Object> inputSchema, List<String> required, McpToolHandler handler) {
        registerTool(name, description, inputSchema, required, null, handler);
    }

    /**
     * Registers an MCP tool definition and handler with optional outputSchema.
     * @param name tool name
     * @param description tool description
     * @param inputSchema tool input property definitions
     * @param required required input names
     * @param outputSchema tool output schema, or null to omit
     * @param handler tool handler
     */
    public synchronized void registerTool(String name, String description, Map<String, Object> inputSchema, List<String> required, Map<String, Object> outputSchema, McpToolHandler handler) {
        requireUnique(name, toolHandlers, "tool");
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", inputSchema != null ? copyMap(inputSchema) : new LinkedHashMap<>());
        schema.put("required", required != null ? new ArrayList<>(required) : new ArrayList<>());
        tool.put("inputSchema", schema);
        if (outputSchema != null && !outputSchema.isEmpty()) {
            tool.put("outputSchema", copyMap(outputSchema));
        }
        registeredTools.add(tool);
        toolHandlers.put(name, handler);
        notifyRegistryChanged("tools");
    }

    /** Registers an MCP resource definition and handler.
     * @param uri resource URI
     * @param name resource name
     * @param description resource description
     * @param mimeType resource MIME type
     * @param handler resource handler
     */
    @Override
    public synchronized void registerResource(String uri, String name, String description, String mimeType, McpResourceHandler handler) {
        requireUnique(uri, resourceHandlers, "resource");
        Map<String, Object> resource = baseResourceMap(uri, name, description, mimeType);
        registeredResources.add(resource);
        resourceHandlers.put(uri, handler);
        notifyRegistryChanged("resources");
    }

    @Override
    public synchronized void registerBlobResource(String uri, String name, String description, String mimeType, McpBlobResourceHandler handler) {
        requireUnique(uri, resourceHandlers, "resource");
        Map<String, Object> resource = baseResourceMap(uri, name, description, mimeType);
        registeredResources.add(resource);
        resourceHandlers.put(uri, handler);
        notifyRegistryChanged("resources");
    }

    /** Registers an MCP resource template definition and handler.
     * @param uriTemplate resource URI template
     * @param name resource template name
     * @param description resource template description
     * @param mimeType resource template MIME type
     * @param handler resource handler
     */
    @Override
    public synchronized void registerResourceTemplate(String uriTemplate, String name, String description, String mimeType, McpResourceHandler handler) {
        requireUnique(uriTemplate, resourceTemplateHandlers, "resource template");
        Map<String, Object> template = baseTemplateMap(uriTemplate, name, description, mimeType);
        registeredResourceTemplates.add(template);
        resourceTemplateHandlers.put(uriTemplate, handler);
        if (handler instanceof McpBlobResourceHandler) {
            blobResourceTemplateHandlers.put(uriTemplate, (McpBlobResourceHandler) handler);
        }
        notifyRegistryChanged("resources");
    }

    /** Registers an MCP blob resource template that returns binary data.
     * @param uriTemplate resource URI template
     * @param name resource template name
     * @param description resource template description
     * @param mimeType resource template MIME type
     * @param handler blob resource template handler
     */
    @Override
    public synchronized void registerBlobResourceTemplate(String uriTemplate, String name, String description, String mimeType, McpBlobResourceHandler handler) {
        requireUnique(uriTemplate, resourceTemplateHandlers, "resource template");
        Map<String, Object> template = baseTemplateMap(uriTemplate, name, description, mimeType);
        registeredResourceTemplates.add(template);
        resourceTemplateHandlers.put(uriTemplate, handler);
        blobResourceTemplateHandlers.put(uriTemplate, handler);
        notifyRegistryChanged("resources");
    }

    /** Registers an MCP prompt definition and handler.
     * @param name prompt name
     * @param description prompt description
     * @param arguments prompt argument definitions
     * @param handler prompt handler
     */
    @Override
    public synchronized void registerPrompt(String name, String description, List<Map<String, Object>> arguments, McpPromptHandler handler) {
        requireUnique(name, promptHandlers, "prompt");
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("name", name);
        prompt.put("description", description);
        if (arguments != null) prompt.put("arguments", copyArgumentList(arguments));
        registeredPrompts.add(prompt);
        promptHandlers.put(name, handler);
        notifyRegistryChanged("prompts");
    }

    /** Creates and stores a bounded task.
     * @return newly created working task
     */
    public McpTask createTask() {
        McpTask task = McpTask.create();
        registerTask(task);
        return task;
    }

    /**
     * Creates a named task for deferred execution with client-supplied metadata.
     *
     * @param name task name (required)
     * @param sessionId session that owns the task
     * @param requestId client request id for cancellation tracking
     * @param input task input arguments
     * @param inputSchema optional JSON Schema for the input
     * @return newly created working task
     */
    public McpTask createTask(String name, String sessionId, Object requestId,
                              Map<String, Object> input, Map<String, Object> inputSchema) {
        if (name == null || name.trim().isEmpty())
            throw new IllegalArgumentException("name is required");
        if (sessionId == null)
            throw new IllegalArgumentException("sessionId is required");
        if (input == null) input = new LinkedHashMap<>();
        McpTask task = McpTask.create(name, sessionId,
                requestId != null ? requestId.toString() : null, input, inputSchema);
        registerTask(task);
        return task;
    }

    /** Registers an existing task snapshot.
     * @param task task snapshot to register
     */
    public void registerTask(McpTask task) {
        if (task == null) throw new IllegalArgumentException("task is required");
        if (task.getTaskId() == null || task.getTaskId().trim().isEmpty())
            throw new IllegalArgumentException("taskId cannot be blank");
        if (task.getStatus() == null) throw new IllegalArgumentException("status cannot be null");
        if (task.getCreatedAt() < 0 || task.getLastUpdatedAt() < task.getCreatedAt())
            throw new IllegalArgumentException("Task timestamps are invalid");
        if (tasks.putIfAbsent(task.getTaskId(), task) != null)
            throw new IllegalArgumentException("Duplicate task: " + task.getTaskId());
    }

    /** Finds task by identifier.
     * @param taskId task identifier
     * @return task snapshot, or {@code null} when absent
     */
    public McpTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /** Completes a working task and returns its new snapshot.
     * @param taskId task identifier
     * @param result successful result
     * @return completed task snapshot
     */
    public McpTask completeTask(String taskId, Object result) {
        return transitionTask(taskId, McpTask.Status.COMPLETED, result, null);
    }

    /** Fails a working task and returns its new snapshot.
     * @param taskId task identifier
     * @param error failure description
     * @return failed task snapshot
     */
    public McpTask failTask(String taskId, String error) {
        return transitionTask(taskId, McpTask.Status.FAILED, null, error);
    }

    /** Cancels a working task and returns its new snapshot.
     * @param taskId task identifier
     * @return cancelled task snapshot
     */
    public McpTask cancelTask(String taskId) {
        return transitionTask(taskId, McpTask.Status.CANCELLED, null, "Task cancelled");
    }

    private McpTask transitionTask(String taskId, McpTask.Status status, Object result, String error) {
        if (taskId == null || taskId.trim().isEmpty()) throw new IllegalArgumentException("taskId is required");
        for (; ; ) {
            McpTask current = tasks.get(taskId);
            if (current == null) throw new IllegalArgumentException("Unknown task: " + taskId);
            McpTask next = current.transition(status, result, error);
            if (tasks.replace(taskId, current, next)) return next;
        }
    }

    /** Registers completion provider by reference type. */
    @Override
    public void registerCompletionProvider(String referenceType, McpCompletionProvider provider) {
        if (referenceType == null || referenceType.trim().isEmpty())
            throw new IllegalArgumentException("completion reference type cannot be empty");
        if (provider == null) throw new IllegalArgumentException("completion provider cannot be null");
        if (completionProviders.putIfAbsent(referenceType, provider) != null)
            throw new IllegalArgumentException("Duplicate completion provider: " + referenceType);
    }

    /** Finds completion provider by reference type.
     * @param referenceType completion reference type
     * @return provider, or {@code null} when absent
     */
    public McpCompletionProvider getCompletionProvider(String referenceType) {
        return completionProviders.get(referenceType);
    }

    /** Records a cancelled MCP request id for the given session.
     * @param sessionId session identifier
     * @param requestId JSON id value of the cancelled request
     */
    public void cancelRequest(String sessionId, String requestId) {
        if (sessionId == null || requestId == null) return;
        Set<String> ids = cancelledRequests.get(sessionId);
        if (ids == null) {
            cancelledRequests.putIfAbsent(sessionId, ConcurrentHashMap.newKeySet());
            ids = cancelledRequests.get(sessionId);
        }
        ids.add(requestId);
    }

    /** Returns whether the given MCP request was cancelled for the session.
     * @param sessionId session identifier
     * @param requestId JSON id value
     * @return true when the request has been marked cancelled
     */
    public boolean isCancelled(String sessionId, String requestId) {
        if (sessionId == null || requestId == null) return false;
        Set<String> ids = cancelledRequests.get(sessionId);
        return ids != null && ids.contains(requestId);
    }

    /** Clears all per-session cancellation state. Called when a session ends.
     * @param sessionId session whose cancellation records should be cleared */
    public void clearCancellations(String sessionId) {
        if (sessionId != null) cancelledRequests.remove(sessionId);
    }

    /** Sets target receiving resource update notifications.
     * @param target notification target
     */
    public void setNotificationTarget(McpResourceUpdateListener target) {
        this.notificationTarget = target;
    }

    /** Adds a listener notified after a successful registry definition change.
     * @param listener listener to add; null is ignored */
    public void addRegistryChangeListener(McpRegistryChangeListener listener) {
        if (listener != null) changeListeners.addIfAbsent(listener);
    }

    /** Removes a previously registered change listener.
     * @param listener listener to remove; null is ignored */
    public void removeRegistryChangeListener(McpRegistryChangeListener listener) {
        if (listener != null) changeListeners.remove(listener);
    }

    private void notifyRegistryChanged(String listType) {
        for (McpRegistryChangeListener listener : changeListeners) listener.onRegistryChanged(listType);
    }

    /** Forwards resource update notification to configured target.
     * @param uri updated resource URI
     */
    @Override
    public void notifyResourceUpdated(String uri) {
        McpResourceUpdateListener target = notificationTarget;
        if (target != null) target.notifyResourceUpdated(uri);
    }

    /** Returns defensive copies of registered tool definitions.
     * @return registered tool definitions
     */
    public List<Map<String, Object>> getRegisteredTools() {
        return copyDefinitions(registeredTools);
    }

    /** Returns defensive copies of registered resource definitions.
     * @return registered resource definitions
     */
    public List<Map<String, Object>> getRegisteredResources() {
        return copyDefinitions(registeredResources);
    }

    /** Returns defensive copies of registered resource template definitions.
     * @return registered resource template definitions
     */
    public List<Map<String, Object>> getRegisteredResourceTemplates() {
        return copyDefinitions(registeredResourceTemplates);
    }

    /** Returns defensive copies of registered prompt definitions.
     * @return registered prompt definitions
     */
    public List<Map<String, Object>> getRegisteredPrompts() {
        return copyDefinitions(registeredPrompts);
    }

    /** Finds handler registered for tool name.
     * @param name tool name
     * @return tool handler, or {@code null} when absent
     */
    public McpToolHandler getToolHandler(String name) {
        return toolHandlers.get(name);
    }

    /** Finds handler registered for resource URI.
     * @param uri resource URI
     * @return resource handler, or {@code null} when absent
     */
    public McpResourceHandler getResourceHandler(String uri) {
        return resourceHandlers.get(uri);
    }

    /** Finds handler registered for resource URI template.
     * @param uriTemplate resource URI template
     * @return resource handler, or {@code null} when absent
     */
    public McpResourceHandler getResourceTemplateHandler(String uriTemplate) {
        return resourceTemplateHandlers.get(uriTemplate);
    }

    /** Returns handlers registered for resource URI templates.
     * @return resource template handlers
     */
    public Map<String, McpResourceHandler> getResourceTemplateHandlers() {
        return new LinkedHashMap<>(resourceTemplateHandlers);
    }

    /** Returns handlers registered for blob resource URI templates.
     * @return blob resource template handlers
     */
    public Map<String, McpBlobResourceHandler> getBlobResourceTemplateHandlers() {
        return new LinkedHashMap<>(blobResourceTemplateHandlers);
    }

    /** Finds handler registered for prompt name.
     * @param name prompt name
     * @return prompt handler, or {@code null} when absent
     */
    public McpPromptHandler getPromptHandler(String name) {
        return promptHandlers.get(name);
    }

    /** Finds MIME type registered for resource URI.
     * @param uri resource URI
     * @return resource MIME type, or {@code null} when absent
     */
    public String getResourceMimeType(String uri) {
        return findMimeType(registeredResources, "uri", uri);
    }

    /** Finds MIME type registered for resource URI template.
     * @param uriTemplate resource URI template
     * @return resource template MIME type, or {@code null} when absent
     */
    public String getResourceTemplateMimeType(String uriTemplate) {
        return findMimeType(registeredResourceTemplates, "uriTemplate", uriTemplate);
    }

    private static String findMimeType(List<Map<String, Object>> definitions, String key, String value) {
        // Lock on a stable class-level monitor rather than the parameter reference.
        // Writers synchronize on `this`, so reading without a monitor would be racy
        // under concurrent registration; locking on the class object provides a
        // happens-before relation across the synchronized writer paths.
        synchronized (McpRegistry.class) {
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

    private static List<Map<String, Object>> copyArgumentList(List<Map<String, Object>> source) {
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

    /** Builds a resource metadata map with normalized MIME type. */
    private static Map<String, Object> baseResourceMap(String uri, String name, String description, String mimeType) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("uri", uri);
        resource.put("name", name);
        resource.put("description", description);
        resource.put("mimeType", normalizeMimeType(mimeType));
        return resource;
    }

    /** Builds a resource-template metadata map with normalized MIME type. */
    private static Map<String, Object> baseTemplateMap(String uriTemplate, String name, String description, String mimeType) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("uriTemplate", uriTemplate);
        template.put("name", name);
        template.put("description", description);
        template.put("mimeType", normalizeMimeType(mimeType));
        return template;
    }
}
