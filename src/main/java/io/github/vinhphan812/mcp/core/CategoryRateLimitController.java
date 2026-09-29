package io.github.vinhphan812.mcp.core;

import io.github.vinhphan812.mcp.api.config.RateLimits;
import io.github.vinhphan812.mcp.api.utils.McpMethodNames;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static io.github.vinhphan812.mcp.api.spi.McpAuthorization.ADMIN;
import static io.github.vinhphan812.mcp.api.spi.McpAuthorization.READ;
import static io.github.vinhphan812.mcp.api.spi.McpAuthorization.WRITE;

/**
 * Owns stateful per-session category admission, quotas, destructive-tool caps,
 * and abuse scoring.  Protocol handlers only ask this component to check or
 * reserve capacity; the mutable state never crosses this boundary.
 */
public final class CategoryRateLimitController {
    private final RateLimits rateLimits;
    private final Logger logger;
    private final ConcurrentHashMap<String, State> sessions = new ConcurrentHashMap<>();

    public CategoryRateLimitController(RateLimits rateLimits, Logger logger) {
        this.rateLimits = rateLimits;
        this.logger = logger;
    }

    public void registerSession(String sessionId) {
        if (sessionId != null) sessions.put(sessionId, new State());
    }

    public void removeSession(String sessionId) {
        if (sessionId != null) sessions.remove(sessionId);
    }

    public void clearSessions() {
        sessions.clear();
    }

    public boolean isBlocked(String sessionId) {
        State state = sessions.get(sessionId);
        return state != null && state.blocked;
    }

    public int concurrent(String sessionId, String category) {
        State state = sessions.get(sessionId);
        if (state == null) return -1;
        return counter(state, category).get();
    }

    public void setAbuseScore(String sessionId, int score) {
        State state = sessions.get(sessionId);
        if (state != null) state.abuseScore.set(score);
    }

    public void setBlocked(String sessionId, boolean blocked) {
        State state = sessions.get(sessionId);
        if (state != null) state.blocked = blocked;
    }

    public String checkMethod(String sessionId, String method, List<String> toolScopes) {
        State state = sessions.get(sessionId);
        if (state == null) return null;
        if (state.blocked) return "session blocked due to abuse: " + method;
        String category = categoryForMethod(method, toolScopes);
        return checkCategory(state, sessionId, category, method, false);
    }

    public String checkTool(String sessionId, String toolName, List<String> requiredScopes) {
        State state = sessions.get(sessionId);
        if (state == null) return null;
        if (state.blocked) return "session blocked due to abuse: " + toolName;
        long now = System.currentTimeMillis();
        if (rateLimits.destructiveTools.contains(toolName)) {
            String error = checkDestructiveCap(state, toolName, now, sessionId);
            if (error != null) return error;
        }
        return checkCategory(state, sessionId, toolCategory(requiredScopes), toolName, true);
    }

    public boolean reserve(String sessionId, String category) {
        State state = sessions.get(sessionId);
        if (state == null) return false;
        AtomicInteger counter = counter(state, category);
        int cap = cap(category);
        while (true) {
            int current = counter.get();
            if (current >= cap) return false;
            if (counter.compareAndSet(current, current + 1)) {
                logger.info("RESERVED " + category + " from=" + current + " to=" + (current + 1));
                return true;
            }
        }
    }

    public void release(String sessionId, String category) {
        State state = sessions.get(sessionId);
        if (state == null) return;
        int current = counter(state, category).decrementAndGet();
        logger.info("RELEASED " + category + " to=" + current);
    }

    public String categoryForMethod(String method, List<String> toolScopes) {
        return McpMethodNames.TOOLS_CALL.equals(method) && toolScopes != null
                ? toolCategory(toolScopes) : methodCategory(method);
    }

    public String toolCategory(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) return READ;
        for (String scope : scopes) if (ADMIN.equals(scope)) return ADMIN;
        for (String scope : scopes) if (WRITE.equals(scope)) return WRITE;
        return READ;
    }

    private String methodCategory(String method) {
        if (method == null) return READ;
        switch (method) {
            case McpMethodNames.TOOLS_LIST: case McpMethodNames.TOOLS_CALL:
            case McpMethodNames.RESOURCES_LIST: case McpMethodNames.RESOURCES_READ:
            case "resources/templates/list": case "resources/templates/get":
            case McpMethodNames.PROMPTS_LIST: case McpMethodNames.PROMPTS_GET:
            case McpMethodNames.TASKS_GET: case McpMethodNames.TASKS_RESULT:
            case McpMethodNames.COMPLETION_COMPLETE: case McpMethodNames.LOGGING_SET_LEVEL:
            case McpMethodNames.PING: return READ;
            case McpMethodNames.RESOURCES_SUBSCRIBE: case McpMethodNames.RESOURCES_UNSUBSCRIBE:
            case McpMethodNames.TASKS_CANCEL: return WRITE;
            case McpMethodNames.TASKS_CREATE: return ADMIN;
            default: return READ;
        }
    }

    private String checkCategory(State state, String sessionId, String category,
                                 String operation, boolean tool) {
        RateLimitRecord burst;
        RateLimitRecord sustained;
        int burstLimit;
        int sustainedLimit;
        int abuseWeight = ADMIN.equals(category) ? 2 : 1;
        if (ADMIN.equals(category)) {
            burst = state.adminBurst; sustained = state.adminSustained;
            burstLimit = rateLimits.adminBurst; sustainedLimit = rateLimits.adminSustained;
        } else if (WRITE.equals(category)) {
            burst = state.writeBurst; sustained = state.writeSustained;
            burstLimit = rateLimits.writeBurst; sustainedLimit = rateLimits.writeSustained;
        } else {
            burst = state.readBurst; sustained = state.readSustained;
            burstLimit = rateLimits.readBurst; sustainedLimit = rateLimits.readSustained;
        }
        String label = ADMIN.equals(category) ? "admin" : WRITE.equals(category) ? "write" : "read";
        String kind = tool ? "tool" : "method";
        if (burst.denyRequest(burstLimit, rateLimits.rateLimitWindowMs)) {
            addAbuseScore(state, sessionId, operation, abuseWeight, label + " burst limit exceeded");
            return label + " " + kind + " burst limit exceeded" + (tool ? " for tool: " : ": ") + operation;
        }
        if (sustained.denyRequest(sustainedLimit, rateLimits.rateLimitSustainedWindowMs)) {
            addAbuseScore(state, sessionId, operation, abuseWeight, label + " sustained limit exceeded");
            return label + " " + kind + " sustained limit exceeded" + (tool ? " for tool: " : ": ") + operation;
        }
        return null;
    }

    private String checkDestructiveCap(State state, String tool, long now, String sessionId) {
        AtomicInteger count; long last; int cap; long cooldown; int weight; String label;
        if ("shutdown".equals(tool)) { count=state.shutdownCount; last=state.shutdownLastMs; cap=rateLimits.shutdownCap; cooldown=rateLimits.shutdownCooldownMs; weight=5; label="shutdown"; }
        else if ("delete_action".equals(tool) || "delete_prompt".equals(tool)) { count=state.deleteCount; last=state.deleteLastMs; cap=rateLimits.deleteCap; cooldown=rateLimits.deleteCooldownMs; weight=3; label=tool; }
        else { count=state.uploadCount; last=state.uploadLastMs; cap=rateLimits.uploadCap; cooldown=rateLimits.uploadCooldownMs; weight=2; label="upload_file"; }
        if (count.get() >= cap) { addAbuseScore(state, sessionId, tool, weight, label + " cap exceeded"); return label + " lifetime cap exceeded for session"; }
        long elapsed = now - last;
        if (last > 0 && elapsed < cooldown) return label + " on cool-down, retry in " + ((cooldown - elapsed) / 1000) + "s";
        count.incrementAndGet();
        if ("shutdown".equals(tool)) state.shutdownLastMs=now;
        else if ("upload_file".equals(tool)) state.uploadLastMs=now;
        else state.deleteLastMs=now;
        return null;
    }

    private void addAbuseScore(State state, String sessionId, String operation, int weight, String reason) {
        int score = state.abuseScore.addAndGet(weight);
        String level = score >= rateLimits.abuseScoreBlockThreshold ? "SEVERE" : score >= rateLimits.abuseScoreBlockThreshold / 2 ? "CRITICAL" : "WARN";
        logger.warning("[" + level + "] session=" + sessionId + " tool=" + operation + " abuseScore=" + score + " reason=" + reason);
        if (score >= rateLimits.abuseScoreBlockThreshold) { state.blocked = true; logger.severe("[SEVERE] Session blocked for abuse: " + sessionId); }
    }

    private AtomicInteger counter(State state, String category) { return ADMIN.equals(category) ? state.adminConcurrent : WRITE.equals(category) ? state.writeConcurrent : state.readConcurrent; }
    private int cap(String category) { return ADMIN.equals(category) ? rateLimits.adminConcurrent : WRITE.equals(category) ? rateLimits.writeConcurrent : rateLimits.readConcurrent; }

    private static final class State {
        final RateLimitRecord readBurst=new RateLimitRecord(), readSustained=new RateLimitRecord(), writeBurst=new RateLimitRecord(), writeSustained=new RateLimitRecord(), adminBurst=new RateLimitRecord(), adminSustained=new RateLimitRecord();
        final AtomicInteger readConcurrent=new AtomicInteger(), writeConcurrent=new AtomicInteger(), adminConcurrent=new AtomicInteger();
        final AtomicInteger shutdownCount=new AtomicInteger(), deleteCount=new AtomicInteger(), uploadCount=new AtomicInteger(), abuseScore=new AtomicInteger();
        volatile long shutdownLastMs, deleteLastMs, uploadLastMs;
        volatile boolean blocked;
    }
    private static final class RateLimitRecord {
        private final ConcurrentLinkedQueue<Long> timestamps=new ConcurrentLinkedQueue<>();
        synchronized boolean denyRequest(int max, long window) { long now=System.currentTimeMillis(); while(!timestamps.isEmpty() && now-timestamps.peek()>window) timestamps.poll(); if(timestamps.size()>=max)return true; timestamps.offer(now); return false; }
    }
}
