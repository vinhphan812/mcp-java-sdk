/*
 * Copyright (c) 2025 Phan Thanh Vinh. All rights reserved.
 * This work is licensed for personal and educational use only unless otherwise stated.
 * Unauthorized copying, redistribution, or modification of any part of this project is strictly prohibited
 * without prior written permission from the author.
 *
 * For inquiries, please contact: vinhphan812@gmail.com
 */

package io.github.vinhphan812.mcp.core;

import java.util.logging.Level;
import java.util.logging.Logger;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.vinhphan812.mcp.api.McpPromptHandler;
import io.github.vinhphan812.mcp.api.McpRegistrar;
import io.github.vinhphan812.mcp.api.McpResourceHandler;
import io.github.vinhphan812.mcp.api.McpServerConfig;
import io.github.vinhphan812.mcp.api.McpToolHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * Lightweight MCP protocol handler that implements JSON-RPC 2.0 for MCP
 * without depending on the MCP SDK's server-side code (which uses Stream.toList(),
 * incompatible with Android API 22).
 *
 * This handler replaces McpAsyncServer/McpSyncServer while keeping the schema types.
 * Definitions are supplied by an application-owned {@link McpRegistry}; the
 * protocol layer does not construct project-specific tools or resources.
 */
public class McpProtocolHandler implements McpRegistrar {

    private static final Logger LOGGER = Logger.getLogger(McpProtocolHandler.class.getName());
    private static final String JSONRPC_VERSION = "2.0";
    private static final String SERVER_NAME = "cruzr_robot_mcp";
    private static final String SERVER_VERSION = "0.1.0";

    private final Gson mapper;
    private final McpRegistry registry;
    private final McpServerConfig config;

    // Session state
    private static class SessionState {
        final String sessionId;
        final long createdAt;
        Map<String, Object> clientInfo;
        Map<String, Object> clientCapabilities;
        String protocolVersion;
        final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        final ConcurrentLinkedQueue<String> pendingNotifications = new ConcurrentLinkedQueue<>();

        SessionState(String sessionId) {
            this.sessionId = sessionId;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    /** Request-local protocol output used by transports to set response headers safely. */
    public static final class McpResponse {
        private final String body;
        private final String sessionId;

        public McpResponse(String body, String sessionId) {
            this.body = body;
            this.sessionId = sessionId;
        }

        public String getBody() {
            return body;
        }

        public String getSessionId() {
            return sessionId;
        }
    }

    /** Thrown to signal a JSON-RPC error from within a handler method without a full stack trace. */
    private static final class McpErrorException extends RuntimeException {
        private final int code;
        McpErrorException(int code, String message) { super(message); this.code = code; }
        int getCode() { return code; }
    }

    /** Create a protocol handler from application-owned MCP definitions. */
    public McpProtocolHandler(McpRegistry registry) {
        this(registry, defaultConfig());
    }

    /** Create a protocol handler with application-owned definitions and metadata. */
    public McpProtocolHandler(McpRegistry registry, McpServerConfig config) {
        if (registry == null) throw new IllegalArgumentException("registry cannot be null");
        if (config == null) throw new IllegalArgumentException("config cannot be null");
        this.mapper = new Gson();
        this.registry = registry;
        this.config = config;
        registry.setNotificationTarget(this);
    }

    private static McpServerConfig defaultConfig() {
        return McpServerConfig.builder()
                .serverName(SERVER_NAME)
                .serverVersion(SERVER_VERSION)
                .build();
    }

    /** Return whether server accepts a protocol version during HTTP negotiation. */
    public boolean supportsProtocolVersion(String version) {
        return version == null || config.protocolVersion.equals(version)
                || "2025-06-18".equals(version) || "2025-03-26".equals(version);
    }

    /** Return whether a session is currently active. */
    public boolean hasSession(String sessionId) {
        return sessionId != null && sessions.containsKey(sessionId);
    }

    /**
     * Handle a JSON-RPC request and return only its response body.
     * The sessionId parameter is the Mcp-Session-Id from the request header (maybe null).
     */
    public String handleRequest(String requestBody, String sessionId) {
        return handleRequestResponse(requestBody, sessionId).getBody();
    }

    /**
     * Handle a request and return request-local metadata for the transport layer.
     * Keeping the response session ID in this object avoids cross-request races.
     */
    @SuppressWarnings("unchecked")
    public McpResponse handleRequestResponse(String requestBody, String sessionId) {
        Object id = null;
        try {
            Map<String, Object> request = mapper.fromJson(requestBody, new TypeToken<Map<String, Object>>() {}.getType());
            if (request == null || !JSONRPC_VERSION.equals(request.get("jsonrpc"))) {
                return new McpResponse(errorResponse(null, -32600, "Invalid Request: jsonrpc must be 2.0"), sessionId);
            }
            Object methodValue = request.get("method");
            String method = methodValue instanceof String ? (String) methodValue : null;
            id = request.get("id");
            Object params = request.get("params");
            boolean notification = !request.containsKey("id");

            if (method == null) {
                return new McpResponse(errorResponse(id, -32600,
                        "Invalid Request: missing method"), sessionId);
            }

            LOGGER.info("Handling MCP request: " + method);

            if (!isSessionOptional(method) && !hasSession(sessionId)) {
                return new McpResponse(errorResponse(id, -32001,
                        "Missing or invalid MCP session"), sessionId);
            }

            String responseSessionId = sessionId;
            Map<String, Object> result;
            switch (method) {
                case "initialize":
                    Map<String, Object> initializeParams = params instanceof Map
                            ? (Map<String, Object>) params : null;
                    Object requestedVersion = initializeParams == null
                            ? null : initializeParams.get("protocolVersion");
                    if (requestedVersion != null && !(requestedVersion instanceof String)) {
                        return new McpResponse(errorResponse(id, -32602,
                                "Invalid params: protocolVersion must be a string"), sessionId);
                    }
                    if (requestedVersion instanceof String
                            && !supportsProtocolVersion((String) requestedVersion)) {
                        return new McpResponse(errorResponse(id, -32602,
                                "Unsupported protocol version: " + requestedVersion), sessionId);
                    }
                    responseSessionId = UUID.randomUUID().toString();
                    result = handleInitialize(initializeParams, responseSessionId);
                    break;
                case "notifications/initialized":
                    return new McpResponse(null, sessionId);
                case "tools/list":
                    if (!config.tools) {
                        return new McpResponse(capabilityError(id, "tools"), sessionId);
                    }
                    result = handleToolsList();
                    break;
                case "tools/call":
                    if (!config.tools) {
                        return new McpResponse(capabilityError(id, "tools"), sessionId);
                    }
                    result = handleToolsCall(params instanceof Map
                            ? (Map<String, Object>) params : null);
                    break;
                case "resources/list":
                    if (!config.resources) {
                        return new McpResponse(capabilityError(id, "resources"), sessionId);
                    }
                    result = handleResourcesList();
                    break;
                case "resources/read":
                    if (!config.resources) {
                        return new McpResponse(capabilityError(id, "resources"), sessionId);
                    }
                    result = handleResourcesRead(params instanceof Map
                            ? (Map<String, Object>) params : null);
                    break;
                case "resources/templates/list":
                    if (!config.resources) {
                        return new McpResponse(capabilityError(id, "resources"), sessionId);
                    }
                    result = handleResourceTemplatesList();
                    break;
                case "resources/templates/get":
                    return new McpResponse(errorResponse(id, -32601,
                            "Method not found: resources/templates/get — use resources/read with resolved URI"), sessionId);
                case "resources/subscribe":
                    if (!config.resources || !config.resourceSubscriptions) {
                        return new McpResponse(capabilityError(id, "resource subscriptions"), sessionId);
                    }
                    result = handleResourceSubscribe(sessionId, params instanceof Map
                            ? (Map<String, Object>) params : null);
                    break;
                case "resources/unsubscribe":
                    if (!config.resources || !config.resourceSubscriptions) {
                        return new McpResponse(capabilityError(id, "resource subscriptions"), sessionId);
                    }
                    result = handleResourceUnsubscribe(sessionId, params instanceof Map
                            ? (Map<String, Object>) params : null);
                    break;
                case "prompts/list":
                    if (!config.prompts) {
                        return new McpResponse(capabilityError(id, "prompts"), sessionId);
                    }
                    result = handlePromptsList();
                    break;
                case "prompts/get":
                    if (!config.prompts) {
                        return new McpResponse(capabilityError(id, "prompts"), sessionId);
                    }
                    result = handlePromptsGet(params instanceof Map
                            ? (Map<String, Object>) params : null);
                    break;
                case "ping":
                    result = new LinkedHashMap<>();
                    break;
                default:
                    return new McpResponse(errorResponse(id, -32601,
                            "Method not found: " + method), sessionId);
            }

            if (result == null || notification) {
                return new McpResponse(null, responseSessionId);
            }
            return new McpResponse(successResponse(id, result), responseSessionId);
        } catch (McpErrorException e) {
            return new McpResponse(errorResponse(id, e.getCode(), e.getMessage()), sessionId);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling MCP request", e);
            try {
                Map<String, Object> req = mapper.fromJson(requestBody, new TypeToken<Map<String, Object>>() {}.getType());
                if (req != null) id = req.get("id");
            } catch (Exception ignored) {
                // Keep the JSON-RPC error id null when the request cannot be parsed.
            }
            return new McpResponse(errorResponse(id, -32603, "Internal server error"), sessionId);
        }
    }

    private boolean isSessionOptional(String method) {
        return "initialize".equals(method)
                || "notifications/initialized".equals(method)
                || "ping".equals(method);
    }

    // Backward-compatible overload
    public String handleRequest(String requestBody) {
        return handleRequest(requestBody, null);
    }

    /**
     * Terminate a session.
     */
    public void terminateSession(String sessionId) {
        if (sessionId != null && sessions.remove(sessionId) != null) {
            LOGGER.info("Session terminated: " + sessionId);
        }
    }

    /** Terminate every active session and discard subscriptions and queued notifications. */
    public void closeAllSessions() {
        int count = sessions.size();
        sessions.clear();
        if (count > 0) {
            LOGGER.info("Closed " + count + " MCP sessions");
        }
    }

    // ==================== Initialize ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleInitialize(Map<String, Object> params,
                                                      String newSessionId) {
        SessionState state = new SessionState(newSessionId);
        if (params != null) {
            Object clientInfo = params.get("clientInfo");
            Object clientCapabilities = params.get("capabilities");
            state.clientInfo = clientInfo instanceof Map
                    ? (Map<String, Object>) clientInfo : null;
            state.clientCapabilities = clientCapabilities instanceof Map
                    ? (Map<String, Object>) clientCapabilities : null;
            Object requestedProtocolValue = params.get("protocolVersion");
            String requestedProtocolVersion = requestedProtocolValue instanceof String
                    ? (String) requestedProtocolValue : null;
            state.protocolVersion = requestedProtocolVersion;
        }
        sessions.put(newSessionId, state);

        LOGGER.info("Initialized session: " + newSessionId);

        String negotiatedVersion = state.protocolVersion != null
                && supportsProtocolVersion(state.protocolVersion)
                ? state.protocolVersion : config.protocolVersion;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", negotiatedVersion);

        Map<String, Object> capabilities = new LinkedHashMap<>();

        if (config.tools) {
            Map<String, Object> toolsCap = new LinkedHashMap<>();
            toolsCap.put("listChanged", true);
            capabilities.put("tools", toolsCap);
        }

        if (config.resources) {
            Map<String, Object> resourcesCap = new LinkedHashMap<>();
            resourcesCap.put("listChanged", true);
            if (config.resourceSubscriptions) resourcesCap.put("subscribe", true);
            capabilities.put("resources", resourcesCap);
        }

        if (config.prompts) {
            Map<String, Object> promptsCap = new LinkedHashMap<>();
            promptsCap.put("listChanged", true);
            capabilities.put("prompts", promptsCap);
        }

        result.put("capabilities", capabilities);

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", config.serverName);
        serverInfo.put("version", config.serverVersion);
        result.put("serverInfo", serverInfo);

        return result;
    }

    // ==================== Tools ====================

    private Map<String, Object> handleToolsList() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", registry.getRegisteredTools());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolsCall(Map<String, Object> params) {
        if (params == null) throw new McpErrorException(-32602, "Invalid params: missing params");

        Object nameValue = params.get("name");
        if (!(nameValue instanceof String) || ((String) nameValue).trim().isEmpty()) {
            throw new McpErrorException(-32602, "Invalid params: tool name must be a non-empty string");
        }
        String name = (String) nameValue;

        Object argumentsValue = params.get("arguments");
        if (argumentsValue != null && !(argumentsValue instanceof Map)) {
            throw new McpErrorException(-32602, "Invalid params: arguments must be an object");
        }
        Map<String, Object> arguments = argumentsValue instanceof Map
                ? (Map<String, Object>) argumentsValue
                : new LinkedHashMap<>();

        McpToolHandler handler = registry.getToolHandler(name);
        if (handler == null) throw new McpErrorException(-32602, "Unknown tool: " + name);

        try {
            Map<String, Object> toolResult = handler.call(arguments);
            return toolResult;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Tool call error: " + name, e);
            return errorToolResult("Error calling " + name + ": " + e.getMessage());
        }
    }

    // ==================== Resources ====================

    private Map<String, Object> handleResourcesList() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resources", registry.getRegisteredResources());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleResourcesRead(Map<String, Object> params) {
        if (params == null) throw new McpErrorException(-32602, "Invalid params: missing params");
                Object uriValue = params.get("uri");
                if (!(uriValue instanceof String) || ((String) uriValue).trim().isEmpty()) {
                    throw new McpErrorException(-32602, "Invalid params: uri must be a non-empty string");
                }
                String uri = (String) uriValue;

                McpResourceHandler handler = registry.getResourceHandler(uri);
                if (handler == null) handler = findTemplateHandler(uri);
                if (handler == null) throw new McpErrorException(-32602, "Unknown resource: " + uri);

                try {
                    String content = handler.read(uri);
                    Map<String, Object> result = new LinkedHashMap<>();
                    List<Map<String, Object>> contents = new ArrayList<>();
                    Map<String, Object> contentItem = new LinkedHashMap<>();
                    contentItem.put("uri", uri);
                    contentItem.put("mimeType", mimeTypeForUri(uri));
                    contentItem.put("text", content);
                    contents.add(contentItem);
                    result.put("contents", contents);
                    return result;
                } catch (McpErrorException e) { throw e; }
                catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Resource read error: " + uri, e);
                    throw new McpErrorException(-32603, "Error reading " + uri + ": " + e.getMessage());
                }
    }

    // ==================== Resource Templates ====================

    private Map<String, Object> handleResourceTemplatesList() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceTemplates", registry.getRegisteredResourceTemplates());
        return result;
    }

    private Map<String, Object> handleResourceTemplatesGet(Map<String, Object> params) {
        if (params == null) return errorResourceResult("Missing params");
        String uri = (String) params.get("uri");
        if (uri == null) return errorResourceResult("Missing resource URI");

        McpResourceHandler handler = registry.getResourceHandler(uri);
        if (handler == null) handler = findTemplateHandler(uri);
        if (handler == null) return errorResourceResult("Unknown resource template URI: " + uri);

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            List<Map<String, Object>> contents = new ArrayList<>();
            Map<String, Object> contentItem = new LinkedHashMap<>();
            contentItem.put("uri", uri);
            contentItem.put("mimeType", mimeTypeForUri(uri));
            contentItem.put("text", handler.read(uri));
            contents.add(contentItem);
            result.put("contents", contents);
            return result;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Resource template read error: " + uri, e);
            return errorResourceResult("Error reading " + uri + ": " + e.getMessage());
        }
    }

    private McpResourceHandler findTemplateHandler(String uri) {
        if (uri == null) return null;
        for (Map.Entry<String, McpResourceHandler> entry : registry.getResourceTemplateHandlers().entrySet()) {
            if (templateMatches(entry.getKey(), uri)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String mimeTypeForUri(String uri) {
        String mimeType = registry.getResourceMimeType(uri);
        if (mimeType != null) return mimeType;
        if (uri != null) {
            for (String template : registry.getResourceTemplateHandlers().keySet()) {
                if (templateMatches(template, uri)) {
                    String templateMimeType = registry.getResourceTemplateMimeType(template);
                    return templateMimeType == null ? "application/json" : templateMimeType;
                }
            }
        }
        return "application/json";
    }

    /** Match URI templates using exact path boundaries and every placeholder. */
    private boolean templateMatches(String template, String uri) {
        if (template == null || uri == null) return false;
        StringBuilder regex = new StringBuilder("^");
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf('{', cursor);
            if (open < 0) {
                regex.append(Pattern.quote(template.substring(cursor)));
                cursor = template.length();
                break;
            }
            int close = template.indexOf('}', open + 1);
            if (close < 0 || close == open + 1) return false;
            regex.append(Pattern.quote(template.substring(cursor, open)));
            regex.append("[^/]+");
            cursor = close + 1;
        }
        regex.append('$');
        return Pattern.matches(regex.toString(), uri);
    }

    private Map<String, Object> handleResourceSubscribe(String sessionId, Map<String, Object> params) {
        if (sessionId == null) return errorResourceResult("Missing session ID");
        SessionState state = sessions.get(sessionId);
        if (state == null) return errorResourceResult("Unknown session: " + sessionId);
        if (params == null || params.get("uri") == null) return errorResourceResult("Missing resource URI");
        String uri = (String) params.get("uri");
        if (registry.getResourceHandler(uri) == null && findTemplateHandler(uri) == null) {
            return errorResourceResult("Unknown resource URI: " + uri);
        }
        state.subscriptions.add(uri);
        return new LinkedHashMap<>();
    }

    private Map<String, Object> handleResourceUnsubscribe(String sessionId, Map<String, Object> params) {
        if (sessionId == null) return errorResourceResult("Missing session ID");
        SessionState state = sessions.get(sessionId);
        if (state == null) return errorResourceResult("Unknown session: " + sessionId);
        if (params == null || params.get("uri") == null) return errorResourceResult("Missing resource URI");
        state.subscriptions.remove((String) params.get("uri"));
        return new LinkedHashMap<>();
    }

    // ==================== Prompts ====================

    private Map<String, Object> handlePromptsList() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prompts", registry.getRegisteredPrompts());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handlePromptsGet(Map<String, Object> params) {
        if (params == null) return errorPromptResult("Missing params");

        Object nameValue = params.get("name");
        if (!(nameValue instanceof String) || ((String) nameValue).trim().isEmpty()) {
            throw new McpErrorException(-32602, "Invalid params: prompt name must be a non-empty string");
        }
        String name = (String) nameValue;

        Object argumentsValue = params.get("arguments");
        if (argumentsValue != null && !(argumentsValue instanceof Map)) {
            throw new McpErrorException(-32602, "Invalid params: arguments must be an object");
        }
        Map<String, Object> arguments = argumentsValue instanceof Map
                ? (Map<String, Object>) argumentsValue
                : new LinkedHashMap<>();

        McpPromptHandler handler = registry.getPromptHandler(name);
        if (handler == null) return errorPromptResult("Unknown prompt: " + name);

        try {
            return handler.get(arguments);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Prompt get error: " + name, e);
            return errorPromptResult("Error getting prompt " + name + ": " + e.getMessage());
        }
    }

    // ==================== Registration (called by Managers) ====================

    /**
     * Register a tool schema and handler.
     * Called by ToolManager.registerToolDefinitions() during initialization.
     */
    public void registerTool(String name, String description, Map<String, Object> inputSchema,
                              List<String> required, McpToolHandler handler) {
        registry.registerTool(name, description, inputSchema, required, handler);
    }

    /** Register a resource schema and handler using the default JSON MIME type. */
    @Override
    public void registerResource(String uri, String name, String description, McpResourceHandler handler) {
        registerResource(uri, name, description, "application/json", handler);
    }

    /** Register a resource schema, MIME type, and handler. */
    @Override
    public void registerResource(String uri, String name, String description, String mimeType,
                                 McpResourceHandler handler) {
        registry.registerResource(uri, name, description, mimeType, handler);
    }

    /** Register a URI template using the default JSON MIME type. */
    @Override
    public void registerResourceTemplate(String uriTemplate, String name, String description,
                                         McpResourceHandler handler) {
        registerResourceTemplate(uriTemplate, name, description, "application/json", handler);
    }

    /** Register a URI template, MIME type, and dynamic resource handler. */
    @Override
    public void registerResourceTemplate(String uriTemplate, String name, String description,
                                         String mimeType, McpResourceHandler handler) {
        registry.registerResourceTemplate(uriTemplate, name, description, mimeType, handler);
    }

    /** Queue a resource update for every session subscribed to the URI. */
    public void notifyResourceUpdated(String uri) {
        if (uri == null) return;
        for (SessionState state : sessions.values()) {
            if (state.subscriptions.contains(uri)) {
                state.pendingNotifications.offer(uri);
            }
        }
    }

    /** Return the next queued notification as a JSON-RPC message for SSE. */
    public String pollPendingNotification(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return null;
        String uri = state.pendingNotifications.poll();
        if (uri == null) return null;
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", JSONRPC_VERSION);
            message.put("method", "notifications/resources/updated");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("uri", uri);
            message.put("params", params);
            return mapper.toJson(message);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unable to serialize resource update notification", e);
            return null;
        }
    }

    /**
     * Register a prompt schema and handler.
     * Called by PromptManager.registerPromptDefinitions() during initialization.
     */
    public void registerPrompt(String name, String description, List<Map<String, Object>> arguments,
                                McpPromptHandler handler) {
        registry.registerPrompt(name, description, arguments, handler);
    }

    // ==================== JSON-RPC Response Helpers ====================

    private String capabilityError(Object id, String capability) {
        return errorResponse(id, -32601, "MCP capability is disabled: " + capability);
    }

    private String successResponse(Object id, Object result) throws Exception {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", JSONRPC_VERSION);
        resp.put("id", id);
        resp.put("result", result);
        return mapper.toJson(resp);
    }

    private String errorResponse(Object id, int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", JSONRPC_VERSION);
        resp.put("id", id);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message == null ? "Unknown MCP error" : message);
        resp.put("error", error);
        try {
            return mapper.toJson(resp);
        } catch (Exception ignored) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal server error\"}}";
        }
    }

    private Map<String, Object> errorToolResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, String>> content = new ArrayList<>();
        Map<String, String> textContent = new LinkedHashMap<>();
        textContent.put("type", "text");
        textContent.put("text", "Error: " + message);
        content.add(textContent);
        result.put("content", content);
        result.put("isError", true);
        return result;
    }

    private Map<String, Object> errorResourceResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> contentItem = new LinkedHashMap<>();
        contentItem.put("uri", "error");
        contentItem.put("mimeType", "application/json");

        Map<String, String> errorPayload = new LinkedHashMap<>();
        errorPayload.put("error", message == null ? "Unknown resource error" : message);
        try {
            contentItem.put("text", mapper.toJson(errorPayload));
        } catch (Exception ignored) {
            contentItem.put("text", "{\"error\":\"Unable to serialize resource error\"}");
        }
        contents.add(contentItem);
        result.put("contents", contents);
        return result;
    }

    private Map<String, Object> errorPromptResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("description", "Error: " + message);
        result.put("messages", new ArrayList<>());
        return result;
    }
}
