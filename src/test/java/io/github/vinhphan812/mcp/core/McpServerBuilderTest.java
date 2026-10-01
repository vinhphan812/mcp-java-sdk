package io.github.vinhphan812.mcp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class McpServerBuilderTest {
    @Test
    void allowedOriginsRejectsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> McpServer.builder().allowedOrigins(null));
    }
}
