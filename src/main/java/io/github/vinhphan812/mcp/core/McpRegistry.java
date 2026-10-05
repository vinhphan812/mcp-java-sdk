package io.github.vinhphan812.mcp.core;

import io.github.vinhphan812.mcp.api.dto.McpTask;
import io.github.vinhphan812.mcp.api.handler.*;
import io.github.vinhphan812.mcp.api.spi.McpRegistrar;
import io.github.vinhphan812.mcp.api.spi.McpRegistryChangeListener;
import io.github.vinhphan812.mcp.api.spi.McpResourceUpdateListener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Project-neutral MCP definition registry. Consumers register their own
 * tools, resources, templates, and prompts through this class before the
 * protocol handler is started.
 */
public class McpRegistry implements McpRegistrar {
    /**
     * Tool definitions keyed by name for O(1) lookup.
     * The {@code toolKeyOrder} list maintains insertion order for deterministic iteration.
     */
    private final ConcurrentHashMap<String, Map<String, Object>> toolDefinitions = new ConcurrentHashMap<>();
    /** Insertion-order key list for tools. */
    private final CopyOnWriteArrayList<String> toolKeyOrder = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Map<String, Object>> resourceDefinitions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> resourceKeyOrder = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Map<String, Object>> resourceTemplateDefinitions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> resourceTemplateKeyOrder = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Map<String, Object>> promptDefinitions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> promptKeyOrder = new CopyOnWriteArrayList<>();

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

    // ==================== List cache / versioning ====================
    /** Monotonically increasing version counter, incremented on every successful registration or removal. */
    private final AtomicLong catalogVersion = new AtomicLong(0);
    /** Millisecond timestamp of the last registry mutation (registration or removal). */
    private volatile long registrationTimestamp = 0L;
    /** Default TTL for cached list snapshots in milliseconds (1 hour). */
    public static final long DEFAULT_LIST_TTL_MS = 3_600_000L;

    /**
     * Returns cache metadata for a given protocol version.
     * For 2026-07-28+ the caller should include this in paginated list responses.
     *
     * @param protocolVersion the MCP protocol version string
     * @return unmodifiable metadata map with keys catalogVersion, totalCount, ttlMs, cacheScope, registrationTimestamp
     */
    public Map<String, Object> getCacheMetadata(String protocolVersion) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("catalogVersion", catalogVersion.get());
        meta.put("ttlMs", DEFAULT_LIST_TTL_MS);
        meta.put("cacheScope", "public");
        meta.put("registrationTimestamp", registrationTimestamp);
        meta.put("totalTools", toolKeyOrder.size());
        meta.put("totalResources", resourceKeyOrder.size());
        meta.put("totalResourceTemplates", resourceTemplateKeyOrder.size());
        meta.put("totalPrompts", promptKeyOrder.size());
        return Collections.unmodifiableMap(meta);
    }

    /**
     * Returns the current catalog version. Incremented on every registration or removal.
     *
     * @return monotonically increasing catalog version
     */
    public long getCatalogVersion() {
        return catalogVersion.get();
    }

    /**
     * Returns the UTC epoch-millisecond timestamp of the last registry mutation.
     *
     * @return registration timestamp, or 0 if the registry has never been mutated
     */
    public long getRegistrationTimestamp() {
        return registrationTimestamp;
    }

    /**
     * Bumps the catalog version and updates the registration timestamp.
     * Called after every successful registration or removal.
     */
    private void bumpCatalog() {
        registrationTimestamp = System.currentTimeMillis();
        catalogVersion.incrementAndGet();
    }

    /**
     * Registers a map-backed tool provider for lightweight integrations.
     * The provider must contain an optional description, inputSchema, and execute Function.
     */
    @SuppressWarnings("unchecked")
    public synchronized void registerToolProvider(String name, Map<String, Object> provider) {
        if (provider == null) throw new IllegalArgumentException("provider cannot be null");
        Object execute = provider.get("execute");
        if (!(execute instanceof java.util.function.Function)) {
            throw new IllegalArgumentException("provider.execute must be a Function");
        }
        Map<String, Object> schema = provider.get("inputSchema") instanceof Map
                ? (Map<String, Object>) provider.get("inputSchema") : new LinkedHashMap<>();
        registerTool(name, String.valueOf(provider.getOrDefault("description", "")), schema,
                Collections.emptyList(), params -> (Map<String, Object>) ((java.util.function.Function<Object, ?>) execute).apply(params));
    }

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
        registerToolDefinition(name, description, inputSchema, required, outputSchema, null, false, handler);
    }

    /**
     * Registers an MCP tool definition with authorisation metadata.
     * @param name tool name
     * @param description tool description
     * @param inputSchema tool input property definitions
     * @param required required input names
     * @param requiredScopes authorisation scopes for this tool (may be null)
     * @param confirmationRequired whether this tool requires user confirmation
     * @param handler tool handler
     */
    public synchronized void registerTool(String name, String description, Map<String, Object> inputSchema,
                                          List<String> required, List<String> requiredScopes,
                                          boolean confirmationRequired, McpToolHandler handler) {
        registerToolDefinition(name, description, inputSchema, required, null, requiredScopes, confirmationRequired, handler);
    }

    private void registerToolDefinition(String name, String description, Map<String, Object> inputSchema,
                                        List<String> required, Map<String, Object> outputSchema,
                                        List<String> requiredScopes, boolean confirmationRequired,
                                        McpToolHandler handler) {
        requireUnique(name, toolDefinitions, "tool");
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
        if (requiredScopes != null && !requiredScopes.isEmpty()) {
            tool.put("requiredScopes", new ArrayList<>(requiredScopes));
        }
        if (confirmationRequired) {
            tool.put("confirmationRequired", true);
        }
        toolDefinitions.put(name, Collections.unmodifiableMap(tool));
        toolKeyOrder.add(name);
        toolHandlers.put(name, handler);
        bumpCatalog();
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
        requireUnique(uri, resourceDefinitions, "resource");
        Map<String, Object> resource = baseResourceMap(uri, name, description, mimeType);
        resourceDefinitions.put(uri, Collections.unmodifiableMap(resource));
        resourceKeyOrder.add(uri);
        resourceHandlers.put(uri, handler);
        bumpCatalog();
        notifyRegistryChanged("resources");
    }

    @Override
    public synchronized void registerBlobResource(String uri, String name, String description, String mimeType, McpBlobResourceHandler handler) {
        requireUnique(uri, resourceDefinitions, "resource");
        Map<String, Object> resource = baseResourceMap(uri, name, description, mimeType);
        resourceDefinitions.put(uri, Collections.unmodifiableMap(resource));
        resourceKeyOrder.add(uri);
        resourceHandlers.put(uri, handler);
        bumpCatalog();
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
        requireUnique(uriTemplate, resourceTemplateDefinitions, "resource template");
        Map<String, Object> template = baseTemplateMap(uriTemplate, name, description, mimeType);
        resourceTemplateDefinitions.put(uriTemplate, Collections.unmodifiableMap(template));
        resourceTemplateKeyOrder.add(uriTemplate);
        resourceTemplateHandlers.put(uriTemplate, handler);
        if (handler instanceof McpBlobResourceHandler) {
            blobResourceTemplateHandlers.put(uriTemplate, (McpBlobResourceHandler) handler);
        }
        bumpCatalog();
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
        requireUnique(uriTemplate, resourceTemplateDefinitions, "resource template");
        Map<String, Object> template = baseTemplateMap(uriTemplate, name, description, mimeType);
        resourceTemplateDefinitions.put(uriTemplate, Collections.unmodifiableMap(template));
        resourceTemplateKeyOrder.add(uriTemplate);
        resourceTemplateHandlers.put(uriTemplate, handler);
        blobResourceTemplateHandlers.put(uriTemplate, handler);
        bumpCatalog();
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
        requireUnique(name, promptDefinitions, "prompt");
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("name", name);
        prompt.put("description", description);
        if (arguments != null) prompt.put("arguments", copyArgumentList(arguments));
        promptDefinitions.put(name, Collections.unmodifiableMap(prompt));
        promptKeyOrder.add(name);
        promptHandlers.put(name, handler);
        bumpCatalog();
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

    /**
     * Transitions a working task to the {@code INPUT_REQUIRED} state, preserving
     * SEP-2663 metadata (statusMessage, ttlMs, pollIntervalMs, inputRequests).
     *
     * @param taskId task identifier
     * @param statusMessage human-readable status message
     * @param ttlMs time-to-live in ms, or null for unlimited
     * @param pollIntervalMs suggested polling interval in ms, or null
     * @param inputRequests pending MRTR input requests map, or null
     * @return updated INPUT_REQUIRED task snapshot
     */
    public McpTask transitionToInputRequired(String taskId, String statusMessage,
                                            Long ttlMs, Integer pollIntervalMs,
                                            Map<String, Object> inputRequests) {
        if (taskId == null || taskId.trim().isEmpty())
            throw new IllegalArgumentException("taskId is required");
        for (; ; ) {
            McpTask current = tasks.get(taskId);
            if (current == null)
                throw new IllegalArgumentException("Unknown task: " + taskId);
            if (current.getStatus() != McpTask.Status.WORKING
                    && current.getStatus() != McpTask.Status.INPUT_REQUIRED)
                throw new IllegalStateException("Task is already terminal: " + current.getStatus());
            long now = System.currentTimeMillis();
            McpTask next = new McpTask(
                    taskId, McpTask.Status.INPUT_REQUIRED,
                    current.getName(),
                    current.getTaskId(), // reuse taskId as sessionId proxy (sessionId not stored in McpTask)
                    null, // requestId
                    current.getInput(),
                    null, // inputSchema
                    now, now,
                    statusMessage, ttlMs, pollIntervalMs, inputRequests);
            if (tasks.replace(taskId, current, next)) return next;
        }
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
        for (McpRegistryChangeListener listener : changeListeners) {
            try {
                listener.onRegistryChanged(listType);
            } catch (RuntimeException e) {
                // Listener failures should not propagate to the registrar.
            }
        }
    }

    /** Forwards resource update notification to configured target.
     * @param uri updated resource URI
     */
    @Override
    public void notifyResourceUpdated(String uri) {
        McpResourceUpdateListener target = notificationTarget;
        if (target != null) target.notifyResourceUpdated(uri);
    }

    /** Returns insertion-order defensive copies of registered tool definitions.
     * @return registered tool definitions
     */
    public List<Map<String, Object>> getRegisteredTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : toolKeyOrder) {
            Map<String, Object> def = toolDefinitions.get(key);
            if (def != null) result.add(copyMap(def));
        }
        return result;
    }

    /** Returns insertion-order defensive copies of registered resource definitions.
     * @return registered resource definitions
     */
    public List<Map<String, Object>> getRegisteredResources() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : resourceKeyOrder) {
            Map<String, Object> def = resourceDefinitions.get(key);
            if (def != null) result.add(copyMap(def));
        }
        return result;
    }

    /** Returns insertion-order defensive copies of registered resource template definitions.
     * @return registered resource template definitions
     */
    public List<Map<String, Object>> getRegisteredResourceTemplates() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : resourceTemplateKeyOrder) {
            Map<String, Object> def = resourceTemplateDefinitions.get(key);
            if (def != null) result.add(copyMap(def));
        }
        return result;
    }

    /** Returns insertion-order defensive copies of registered prompt definitions.
     * @return registered prompt definitions
     */
    public List<Map<String, Object>> getRegisteredPrompts() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : promptKeyOrder) {
            Map<String, Object> def = promptDefinitions.get(key);
            if (def != null) result.add(copyMap(def));
        }
        return result;
    }

    /** Finds handler registered for tool name.
     * @param name tool name
     * @return tool handler, or {@code null} when absent
     */
    public McpToolHandler getToolHandler(String name) {
        return toolHandlers.get(name);
    }

    /**
     * Finds the full tool definition registered for a tool name.
     * @param name tool name
     * @return tool definition Map (with name, description, inputSchema, requiredScopes, confirmationRequired)
     *         or {@code null} when absent
     */
    public synchronized Map<String, Object> getToolDefinition(String name) {
        Map<String, Object> def = toolDefinitions.get(name);
        return def != null ? copyMap(def) : null;
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
        Map<String, Object> def = resourceDefinitions.get(uri);
        return def != null ? normalizeMimeType((String) def.get("mimeType")) : null;
    }

    /** Finds MIME type registered for resource URI template.
     * @param uriTemplate resource URI template
     * @return resource template MIME type, or {@code null} when absent
     */
    public String getResourceTemplateMimeType(String uriTemplate) {
        Map<String, Object> def = resourceTemplateDefinitions.get(uriTemplate);
        return def != null ? normalizeMimeType((String) def.get("mimeType")) : null;
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

    // copyDefinitions removed — definitions are stored in maps and copied via copyMap()

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
