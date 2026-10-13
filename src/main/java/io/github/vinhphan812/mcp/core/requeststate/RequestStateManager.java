package io.github.vinhphan812.mcp.core.requeststate;

import io.github.vinhphan812.mcp.api.utils.McpErrorCodes;
import io.github.vinhphan812.mcp.api.utils.McpException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Manages the lifecycle of in-band elicitation request states. */
public final class RequestStateManager {

    private final Map<String, RequestState> states = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final int defaultMaxRounds;

    public RequestStateManager(long ttlMs, int defaultMaxRounds) {
        this.ttlMs = ttlMs;
        this.defaultMaxRounds = defaultMaxRounds;
    }

    /** Creates a new state token for a pending elicitation request. */
    public String create(String taskId, Map<String, Object> context) {
        String token = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        RequestState state = new RequestState(
                taskId, token, now, now + ttlMs, 1, defaultMaxRounds, context);
        states.put(token, state);
        return token;
    }

    /** Validates and retrieves the state for a response. */
    public RequestState validateAndGet(String token) {
        RequestState state = states.get(token);

        if (state == null) {
            throw new McpException(McpErrorCodes.REQUEST_STATE_INVALID, "Invalid or unknown request state token");
        }

        if (System.currentTimeMillis() > state.getExpiresAt()) {
            states.remove(token);
            throw new McpException(McpErrorCodes.REQUEST_STATE_EXPIRED, "Request state expired");
        }

        // Check if round limit exceeded
        if (state.getRound() >= state.getMaxRounds()) {
            states.remove(token);
            throw new McpException(McpErrorCodes.MAX_ROUNDS_EXCEEDED, "Max rounds exceeded");
        }

        return state;
    }

    /** Removes a state after consumption. */
    public void remove(String token) {
        states.remove(token);
    }
}
