package io.github.vinhphan812.mcp.api.utils;

import com.google.gson.Gson;

/**
 * Provides a single, lazily-initialised, thread-safe {@link Gson} instance shared across
 * the SDK.  Every class that previously instantiated its own {@code new Gson()} now
 * delegates to {@link #get()} so that Gson configuration (future extensions) is in one place.
 *
 * <p>Gson is internally immutable once built, so sharing a single instance across threads
 * is safe.
 */
public final class McpGson {

    private static final Gson INSTANCE = new Gson();

    /** Returns the shared Gson instance. */
    public static Gson get() {
        return INSTANCE;
    }

    private McpGson() {
        // utility class — prevent instantiation
    }
}
