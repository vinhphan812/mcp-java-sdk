package io.github.vinhphan812.mcp.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpGrizzlyResumabilityTest {
    @Test
    void parsesAbsentAndNonNegativeLastEventId() {
        assertNull(McpGrizzlyHandler.parseLastEventId(null));
        assertNull(McpGrizzlyHandler.parseLastEventId("  "));
        assertEquals(Long.valueOf(42L), McpGrizzlyHandler.parseLastEventId(" 42 "));
    }

    @Test
    void rejectsMalformedLastEventId() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> McpGrizzlyHandler.parseLastEventId("abc"));
        assertTrue(error.getMessage().contains("non-negative integer"));
        assertThrows(IllegalArgumentException.class,
                () -> McpGrizzlyHandler.parseLastEventId("-1"));
    }

    @Test
    void emitsSseEventIdBeforeEventAndData() {
        assertEquals("id: 7\nevent: message\ndata: {}\n\n",
                McpGrizzlyHandler.formatSseEvent(7L, "message", "{}"));
    }
}
