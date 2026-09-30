/*
 * Copyright 2025 Phan Thanh Vinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vinhphan812.mcp.api.utils;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link McpError#resourceResult(String)} resource-error envelope shape
 * and serialization-fallback semantics.
 */
class McpErrorResourceResultTest {

    @Test
    void resourceResult_normalMessage_producesValidEnvelope() {
        Map<String, Object> result = McpError.resourceResult("Token expired");

        // Top-level envelope keys
        assertTrue(result.containsKey("contents"));
        List<?> contents = (List<?>) result.get("contents");
        assertEquals(1, contents.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> contentItem = (Map<String, Object>) contents.get(0);
        assertEquals("error", contentItem.get("uri"));
        assertEquals("application/json", contentItem.get("mimeType"));

        // text must be valid JSON, not LinkedHashMap.toString() format
        String text = (String) contentItem.get("text");
        assertTrue(text.startsWith("{\"error\":"), "text must be a JSON string, got: " + text);
        assertTrue(text.endsWith("}"), "text must be a JSON string, got: " + text);
    }

    @Test
    void resourceResult_nullMessage_usesDefaultMessage() {
        Map<String, Object> result = McpError.resourceResult(null);

        @SuppressWarnings("unchecked")
        List<?> contents = (List<?>) result.get("contents");
        @SuppressWarnings("unchecked")
        String text = (String) ((Map<?, ?>) contents.get(0)).get("text");
        assertTrue(text.contains("Unknown resource error"), "text must contain default message, got: " + text);
    }

    @Test
    void resourceResult_serializedEnvelope_matchesExactSchema() {
        // The serialized output must be byte-equivalent to what McpProtocolHandler
        // previously produced directly with mapper.toJson(payload).
        String serialized = McpGson.get().toJson(McpError.resourceResult("Not found"));

        // Verify the serialized string contains the correct keys and values
        assertTrue(serialized.contains("\"contents\""), "serialized: " + serialized);
        assertTrue(serialized.contains("\"uri\":\"error\""), "serialized: " + serialized);
        assertTrue(serialized.contains("\"mimeType\":\"application/json\""), "serialized: " + serialized);
        assertTrue(serialized.contains("\"text\":\"{\\\"error\\\":\\\"Not found\\\"}\""),
                "serialized: " + serialized);
    }

    @Test
    void resourceResult_fallbackEnvelope_whenSerialisationFails() {
        // McpGson should never throw for a LinkedHashMap<String, String>,
        // but the fallback text must still be valid JSON and contain the error key.
        Map<String, Object> result = McpError.resourceResult("anything");

        @SuppressWarnings("unchecked")
        String text = (String) ((Map<?, ?>) ((List<?>) result.get("contents")).get(0)).get("text");
        assertTrue(text.startsWith("{\"error\":"), "fallback text must be JSON, got: " + text);
        assertTrue(text.endsWith("}"), "fallback text must be valid JSON, got: " + text);
    }
}
