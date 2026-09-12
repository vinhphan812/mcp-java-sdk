package io.github.vinhphan812.mcp.api.handler;

import java.util.Map;

/** Provides completion values for a completion reference and argument. */
public interface McpCompletionProvider {
    /**
     * @param reference completion reference supplied by client (maybe unused by some providers)
     * @param argument argument name/value supplied by client
     * @return completion result fields, normally values and optional total/hasMore
     */
    @SuppressWarnings("unused")
    Map<String, Object> complete(Map<String, Object> reference, Map<String, Object> argument);
}
