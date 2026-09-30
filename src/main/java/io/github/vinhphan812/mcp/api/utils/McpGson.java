package io.github.vinhphan812.mcp.api.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Provides a single, lazily-initialised, thread-safe {@link Gson} instance shared across
 * the SDK.  Every class that previously instantiated its own {@code new Gson()} now
 * delegates to {@link #get()} so that Gson configuration (future extensions) is in one place.
 *
 * <p>Gson is internally immutable once built, so sharing a single instance across threads
 * is safe.
 *
 * <p>Null values are serialised so that protocol fields with explicit null semantics
 * (e.g., {@code tasks/result} response always includes {@code result: null} for
 * empty-result tasks) are preserved in the JSON output.
 */
public final class McpGson {

    private static final Gson INSTANCE = new GsonBuilder()
            .serializeNulls()
            .create();

    /** Returns the shared Gson instance. */
    public static Gson get() {
        return INSTANCE;
    }

    private McpGson() {
        // utility class — prevent instantiation
    }
}
