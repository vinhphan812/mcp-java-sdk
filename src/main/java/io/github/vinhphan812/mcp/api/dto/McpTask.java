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
    }

    /**
     * Creates a task snapshot with full task-producing metadata.
     * Used for tasks created via the {@code tasks/create} request.
     * @param taskId task identifier
     * @param status must be WORKING
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
        if (taskId == null || taskId.trim().isEmpty())
            throw new IllegalArgumentException("taskId cannot be blank");
        if (status == null)
            throw new IllegalArgumentException("status cannot be null");
        if (createdAt < 0 || lastUpdatedAt < createdAt)
            throw new IllegalArgumentException("Task timestamps are invalid");
        if (status != Status.WORKING)
            throw new IllegalArgumentException("Task-producing constructor only valid for WORKING status");
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

    /** Creates a new bounded task in the working state.
     * @return new working task
     */
    public static McpTask create() {
        long now = System.currentTimeMillis();
        return new McpTask(UUID.randomUUID().toString(), Status.WORKING, now, now, null, null);
    }

    /** Creates a named task for deferred execution.
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

    /** Returns a snapshot with a terminal lifecycle state.
     * @param nextStatus target terminal status
     * @param nextResult successful result, if applicable
     * @param nextError failure or cancellation description, if applicable
     * @return transitioned task snapshot
     */
    public McpTask transition(Status nextStatus, Object nextResult, String nextError) {
        if (nextStatus == null || nextStatus == Status.WORKING)
            throw new IllegalArgumentException("Task transition must be terminal");
        if (status != Status.WORKING)
            throw new IllegalStateException("Task is already terminal: " + status);
        if (nextStatus == Status.COMPLETED && nextError != null)
            throw new IllegalArgumentException("Completed task cannot have an error");
        if ((nextStatus == Status.FAILED || nextStatus == Status.CANCELLED) && nextError == null)
            throw new IllegalArgumentException("Failed or cancelled task requires an error");
        // Pass through task-producing fields if present, otherwise use legacy ctor
        if (name != null) {
            return new McpTask(taskId, nextStatus, name, sessionId, requestId,
                    input, inputSchema, createdAt, System.currentTimeMillis())
                    .withTerminalResult(nextStatus, nextResult, nextError);
        }
        return new McpTask(taskId, nextStatus, createdAt, System.currentTimeMillis(), nextResult, nextError);
    }

    /** Sets terminal result on a task-producing task (used after transition). */
    private McpTask withTerminalResult(Status nextStatus, Object nextResult, String nextError) {
        return new McpTask(taskId, nextStatus, createdAt, System.currentTimeMillis(), nextResult, nextError);
    }

    /** Converts this snapshot to the MCP task metadata object.
     * @return JSON-compatible task metadata
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("status", status.name().toLowerCase());
        map.put("createdAt", createdAt);
        map.put("lastUpdatedAt", lastUpdatedAt);
        map.put("result", result);  // always present; null if not completed
        if (name != null) map.put("name", name);
        if (sessionId != null) map.put("sessionId", sessionId);
        if (input != null) map.put("input", input);
        if (inputSchema != null) map.put("inputSchema", inputSchema);
        if (error != null) map.put("error", error);
        return map;
    }
}
