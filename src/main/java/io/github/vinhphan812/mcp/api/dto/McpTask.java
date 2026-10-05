package io.github.vinhphan812.mcp.api.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable snapshot of a bounded server task. */
public final class McpTask {
    /** Task lifecycle states defined by the MCP Tasks capability. */
    public enum Status {
        /** Task is still running. */
        WORKING,
        /** Task is waiting for client input (SEP-2663 input_required). */
        INPUT_REQUIRED,
        /** Task completed successfully. */
        COMPLETED,
        /** Task failed with an error. */
        FAILED,
        /** Task was cancelled. */
        CANCELLED
    }

    private final String taskId;
    private final Status status;
    private final long createdAt;
    private final long lastUpdatedAt;
    private final Object result;
    private final String error;
    // --- task-producing fields (used when task is created via tasks/create) ---
    private final String name;
    private final String sessionId;
    private final String requestId;
    private final Map<String, Object> input;
    private final Map<String, Object> inputSchema;
    // --- SEP-2663 fields ---
    /** Human-readable message about the current status. */
    private final String statusMessage;
    /** Task time-to-live in milliseconds, or null for unlimited. */
    private final Long ttlMs;
    /** Suggested polling interval in milliseconds. */
    private final Integer pollIntervalMs;
    /** Pending server-to-client requests awaiting client input (SEP-2663). */
    private final Map<String, Object> inputRequests;

    /** Creates a validated task snapshot.
     * @param taskId task identifier
     * @param status lifecycle status
     * @param createdAt creation timestamp
     * @param lastUpdatedAt last update timestamp
     * @param result successful result, if any
     * @param error failure or cancellation description, if any
     */
    public McpTask(String taskId, Status status, long createdAt, long lastUpdatedAt,
                   Object result, String error) {
        if (taskId == null || taskId.trim().isEmpty())
            throw new IllegalArgumentException("taskId cannot be blank");
        if (status == null)
            throw new IllegalArgumentException("status cannot be null");
        if (createdAt < 0 || lastUpdatedAt < createdAt)
            throw new IllegalArgumentException("Task timestamps are invalid");
        if (status == Status.WORKING) {
            if (result != null || error != null)
                throw new IllegalArgumentException("Working task cannot have result or error");
        } else if (status == Status.COMPLETED) {
            if (error != null)
                throw new IllegalArgumentException("Completed task cannot have an error");
        } else {
            if (result != null)
                throw new IllegalArgumentException("Failed or cancelled task cannot have a result");
            if (error == null || error.trim().isEmpty())
                throw new IllegalArgumentException("Failed or cancelled task requires a nonblank error");
        }
        this.taskId = taskId;
        this.status = status;
        this.createdAt = createdAt;
        this.lastUpdatedAt = lastUpdatedAt;
        this.result = result;
        this.error = error;
        this.name = null;
        this.sessionId = null;
        this.requestId = null;
        this.input = null;
        this.inputSchema = null;
        this.statusMessage = null;
        this.ttlMs = null;
        this.pollIntervalMs = null;
        this.inputRequests = null;
    }

    /**
     * Creates a task snapshot with full task-producing metadata.
     * Used for tasks created via the {@code tasks/create} request.
     * @param taskId task identifier
     * @param status must be WORKING or INPUT_REQUIRED
     * @param name task name
     * @param sessionId owning session
     * @param requestId client request id for cancellation
     * @param input task input arguments
     * @param inputSchema optional JSON Schema for input
     * @param createdAt creation timestamp
     * @param lastUpdatedAt last update timestamp
     */
    public McpTask(String taskId, Status status, String name,
                   String sessionId, String requestId,
                   Map<String, Object> input, Map<String, Object> inputSchema,
                   long createdAt, long lastUpdatedAt) {
        this(taskId, status, name, sessionId, requestId, input, inputSchema,
                createdAt, lastUpdatedAt, null, null, null, null);
    }

    /**
     * Creates a task snapshot with full task-producing metadata and SEP-2663 fields.
     * Used for tasks created via the {@code tasks/create} request.
     * @param taskId task identifier
     * @param status must be WORKING or INPUT_REQUIRED
     * @param name task name
     * @param sessionId owning session
     * @param requestId client request id for cancellation
     * @param input task input arguments
     * @param inputSchema optional JSON Schema for input
     * @param createdAt creation timestamp
     * @param lastUpdatedAt last update timestamp
     * @param statusMessage human-readable status message (may be null)
     * @param ttlMs time-to-live in ms, or null for unlimited
     * @param pollIntervalMs suggested polling interval in ms, or null
     * @param inputRequests pending input requests map, or null
     */
    public McpTask(String taskId, Status status, String name,
                   String sessionId, String requestId,
                   Map<String, Object> input, Map<String, Object> inputSchema,
                   long createdAt, long lastUpdatedAt,
                   String statusMessage, Long ttlMs, Integer pollIntervalMs,
                   Map<String, Object> inputRequests) {
        if (taskId == null || taskId.trim().isEmpty())
            throw new IllegalArgumentException("taskId cannot be blank");
        if (status == null)
            throw new IllegalArgumentException("status cannot be null");
        if (createdAt < 0 || lastUpdatedAt < createdAt)
            throw new IllegalArgumentException("Task timestamps are invalid");
        if (status != Status.WORKING && status != Status.INPUT_REQUIRED)
            throw new IllegalArgumentException("Task-producing constructor only valid for WORKING or INPUT_REQUIRED status");
        this.taskId = taskId;
        this.status = status;
        this.createdAt = createdAt;
        this.lastUpdatedAt = lastUpdatedAt;
        this.result = null;
        this.error = null;
        this.name = name;
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.input = input;
        this.inputSchema = inputSchema;
        this.statusMessage = statusMessage;
        this.ttlMs = ttlMs;
        this.pollIntervalMs = pollIntervalMs;
        this.inputRequests = inputRequests;
    }

    /** Returns the task identifier.
     * @return task identifier
     */
    public String getTaskId() {
        return taskId;
    }

    /** Returns the current lifecycle status.
     * @return task status
     */
    public Status getStatus() {
        return status;
    }

    /** Returns the creation timestamp.
     * @return creation timestamp in milliseconds
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /** Returns the last update timestamp.
     * @return last update timestamp in milliseconds
     */
    public long getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    /** Returns the task result, if completed.
     * @return task result or null
     */
    public Object getResult() {
        return result;
    }

    /** Returns the error description, if failed or cancelled.
     * @return error description or null
     */
    public String getError() {
        return error;
    }

    /** Returns the task name, if set during creation.
     * @return name or null
     */
    public String getName() {
        return name;
    }

    /** Returns the owning session ID, if set during creation.
     * @return sessionId or null
     */
    public String getSessionId() {
        return sessionId;
    }

    /** Returns the client request ID, if set during creation.
     * @return requestId or null
     */
    public String getRequestId() {
        return requestId;
    }

    /** Returns the task input arguments.
     * @return input map or null
     */
    public Map<String, Object> getInput() {
        return input;
    }

    /** Returns the input schema.
     * @return inputSchema or null
     */
    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    /** Returns the human-readable status message, if set.
     * @return status message or null
     */
    public String getStatusMessage() {
        return statusMessage;
    }

    /** Returns the task TTL in milliseconds, or null if unlimited.
     * @return TTL in ms, or null
     */
    public Long getTtlMs() {
        return ttlMs;
    }

    /** Returns the suggested polling interval in milliseconds, or null if not set.
     * @return poll interval in ms, or null
     */
    public Integer getPollIntervalMs() {
        return pollIntervalMs;
    }

    /** Returns the pending input requests map for INPUT_REQUIRED tasks.
     * @return input requests map, or null
     */
    public Map<String, Object> getInputRequests() {
        return inputRequests;
    }

    /** Creates a new bounded task in the working state.
     * @return new working task
     */
    public static McpTask create() {
        long now = System.currentTimeMillis();
        return new McpTask(UUID.randomUUID().toString(), Status.WORKING, now, now, null, null);
    }

    /**
     * Creates a named task for deferred execution.
     *  @param name task name
     *  @param sessionId owning session
     *  @param requestId client request id
     *  @param input task input arguments
     *  @param inputSchema optional input schema
     *  @return new working task
     */
    public static McpTask create(String name, String sessionId, String requestId,
                                 Map<String, Object> input, Map<String, Object> inputSchema) {
        long now = System.currentTimeMillis();
        String taskId = "task-" + UUID.randomUUID();
        return new McpTask(taskId, Status.WORKING, name, sessionId, requestId, input, inputSchema, now, now);
    }

    /**
     * Creates a named task with SEP-2663 metadata.
     * @param name task name
     * @param sessionId owning session
     * @param requestId client request id
     * @param input task input arguments
     * @param inputSchema optional input schema
     * @param statusMessage human-readable status message (may be null)
     * @param ttlMs time-to-live in ms, or null for unlimited
     * @param pollIntervalMs suggested polling interval in ms, or null
     * @return new working task
     */
    public static McpTask create(String name, String sessionId, String requestId,
                                 Map<String, Object> input, Map<String, Object> inputSchema,
                                 String statusMessage, Long ttlMs, Integer pollIntervalMs) {
        long now = System.currentTimeMillis();
        String taskId = "task-" + UUID.randomUUID();
        return new McpTask(taskId, Status.WORKING, name, sessionId, requestId,
                input, inputSchema, now, now, statusMessage, ttlMs, pollIntervalMs, null);
    }

    /**
     * Creates an input-required task snapshot.
     * @param name task name
     * @param sessionId owning session
     * @param requestId client request id
     * @param input task input arguments
     * @param inputSchema optional input schema
     * @param statusMessage human-readable status message
     * @param ttlMs time-to-live in ms, or null for unlimited
     * @param pollIntervalMs suggested polling interval in ms, or null
     * @param inputRequests pending input requests (SEP-2663 MRTR shape)
     * @return new INPUT_REQUIRED task
     */
    public static McpTask createInputRequired(String name, String sessionId, String requestId,
                                             Map<String, Object> input, Map<String, Object> inputSchema,
                                             String statusMessage, Long ttlMs, Integer pollIntervalMs,
                                             Map<String, Object> inputRequests) {
        long now = System.currentTimeMillis();
        String taskId = "task-" + UUID.randomUUID();
        return new McpTask(taskId, Status.INPUT_REQUIRED, name, sessionId, requestId,
                input, inputSchema, now, now, statusMessage, ttlMs, pollIntervalMs, inputRequests);
    }

    /** Returns a snapshot with a terminal lifecycle state.
     * @param nextStatus target terminal status
     * @param nextResult successful result, if applicable
     * @param nextError failure or cancellation description, if applicable
     * @return transitioned task snapshot
     */
    public McpTask transition(Status nextStatus, Object nextResult, String nextError) {
        if (nextStatus == null || nextStatus == Status.WORKING || nextStatus == Status.INPUT_REQUIRED)
            throw new IllegalArgumentException("Task transition must be terminal");
        if (status != Status.WORKING && status != Status.INPUT_REQUIRED)
            throw new IllegalStateException("Task is already terminal: " + status);
        if (nextStatus == Status.COMPLETED && nextError != null)
            throw new IllegalArgumentException("Completed task cannot have an error");
        if ((nextStatus == Status.FAILED || nextStatus == Status.CANCELLED) && nextError == null)
            throw new IllegalArgumentException("Failed or cancelled task requires an error");
        // Pass through task-producing fields if present, otherwise use legacy ctor.
        // Use the combined terminal constructor directly to avoid the task-producing
        // constructor's WORKING-only status guard (this task is already terminal here).
        if (name != null) {
            return new McpTask(taskId, nextStatus, nextResult, nextError,
                    name, sessionId, requestId, input, inputSchema,
                    statusMessage, ttlMs, pollIntervalMs, inputRequests,
                    createdAt, System.currentTimeMillis());
        }
        return new McpTask(taskId, nextStatus, createdAt, System.currentTimeMillis(), nextResult, nextError);
    }

    /**
     * Creates a task snapshot with both terminal fields and full task-producing metadata.
     * Used after {@link #transition(Status, Object, String)} to preserve the metadata
     * captured at creation time through the terminal state transition.
     *
     * @param taskId        task identifier
     * @param nextStatus    terminal lifecycle status
     * @param nextResult    successful result, if applicable
     * @param nextError     failure or cancellation description, if applicable
     */
    McpTask(String taskId, Status nextStatus,
            Object nextResult, String nextError,
            String name, String sessionId, String requestId,
            Map<String, Object> input, Map<String, Object> inputSchema,
            String statusMessage, Long ttlMs, Integer pollIntervalMs,
            Map<String, Object> inputRequests,
            long createdAt, long lastUpdatedAt) {
        if (taskId == null || taskId.trim().isEmpty())
            throw new IllegalArgumentException("taskId cannot be blank");
        if (nextStatus == null || nextStatus == Status.WORKING || nextStatus == Status.INPUT_REQUIRED)
            throw new IllegalArgumentException("Terminal constructor requires a terminal status");
        if (createdAt < 0 || lastUpdatedAt < createdAt)
            throw new IllegalArgumentException("Task timestamps are invalid");
        if (nextStatus == Status.COMPLETED) {
            if (nextError != null)
                throw new IllegalArgumentException("Completed task cannot have an error");
        } else {
            if (nextResult != null)
                throw new IllegalArgumentException("Failed or cancelled task cannot have a result");
            if (nextError == null || nextError.trim().isEmpty())
                throw new IllegalArgumentException("Failed or cancelled task requires a nonblank error");
        }
        this.taskId = taskId;
        this.status = nextStatus;
        this.createdAt = createdAt;
        this.lastUpdatedAt = lastUpdatedAt;
        this.result = nextResult;
        this.error = nextError;
        this.name = name;
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.input = input;
        this.inputSchema = inputSchema;
        this.statusMessage = statusMessage;
        this.ttlMs = ttlMs;
        this.pollIntervalMs = pollIntervalMs;
        this.inputRequests = inputRequests;
    }

    /**
     * Converts this snapshot to the MCP task metadata object.
     * Timestamps are formatted as ISO-8601 strings per SEP-2663.
     * @return JSON-compatible task metadata
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("status", status.name().toLowerCase());
        if (statusMessage != null) map.put("statusMessage", statusMessage);
        map.put("createdAt", iso8601(createdAt));
        map.put("lastUpdatedAt", iso8601(lastUpdatedAt));
        if (ttlMs != null) map.put("ttlMs", ttlMs);
        if (pollIntervalMs != null) map.put("pollIntervalMs", pollIntervalMs);
        // result is always present (null if not completed)
        map.put("result", result);
        if (name != null) map.put("name", name);
        if (sessionId != null) map.put("sessionId", sessionId);
        if (input != null) map.put("input", input);
        if (inputSchema != null) map.put("inputSchema", inputSchema);
        if (inputRequests != null) map.put("inputRequests", inputRequests);
        if (error != null) map.put("error", error);
        return map;
    }

    /** Formats epoch milliseconds as ISO-8601 UTC string. */
    private static String iso8601(long epochMs) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(epochMs);
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
