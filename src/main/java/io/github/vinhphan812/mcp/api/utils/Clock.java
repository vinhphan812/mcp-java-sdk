package io.github.vinhphan812.mcp.api.utils;

/**
 * Interface to abstract time-based operations, allowing for deterministic testing.
 */
public interface Clock {
    long currentTimeMillis();
    
    /** Default implementation using System clock. */
    Clock SYSTEM = System::currentTimeMillis;
}
