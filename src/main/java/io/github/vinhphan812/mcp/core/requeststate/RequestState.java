package io.github.vinhphan812.mcp.core.requeststate;

import java.util.Map;

/** Internal tracking state for an elicitation request round. */
public final class RequestState {
    private final String taskId;
    private final String requestStateToken;
    private final long createdAt;
    private final long expiresAt;
    private final int round;
    private final int maxRounds;
    private final Map<String, Object> context;

    public RequestState(String taskId, String requestStateToken, long createdAt, long expiresAt,
                        int round, int maxRounds, Map<String, Object> context) {
        this.taskId = taskId;
        this.requestStateToken = requestStateToken;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.round = round;
        this.maxRounds = maxRounds;
        this.context = context;
    }

    public String getTaskId() { return taskId; }
    public String getRequestStateToken() { return requestStateToken; }
    public long getCreatedAt() { return createdAt; }
    public long getExpiresAt() { return expiresAt; }
    public int getRound() { return round; }
    public int getMaxRounds() { return maxRounds; }
    public Map<String, Object> getContext() { return context; }
}
