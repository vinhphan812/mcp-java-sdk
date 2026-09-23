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

package io.github.vinhphan812.mcp.core;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.config.RateLimits;
import io.github.vinhphan812.mcp.api.handler.McpToolHandler;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Minimal debug test to isolate the makeContext failure.
 */
@SuppressWarnings("unused")
class McpCategoryDebugTest {

    @org.junit.jupiter.api.Test
    void debug_initOnly() throws Exception {
        RateLimits limits = RateLimits.builder()
                .maxConcurrentSessions(100)
                .sessionTimeoutMs(Long.MAX_VALUE)
                .sessionCleanupIntervalMs(Long.MAX_VALUE)
                .read(10_000, 100_000, 5)
                .write(10_000, 100_000, 3)
                .admin(10_000, 100_000, 1)
                .build();
        McpServerConfig config = McpServerConfig.builder()
                .rateLimits(limits)
                .tools(true).resources(false).prompts(false)
                .build();
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);

        String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        System.out.println("=== CALL 1: initialize ===");
        McpProtocolHandler.McpResponse initR = handler.handleRequestResponse(initBody, null, null);
        System.out.println("sessionId: " + initR.getSessionId());
        System.out.println("body: " + initR.getBody());
        assertNotNull(initR.getSessionId(), "Init must succeed; body: " + initR.getBody());

        String sessionId = initR.getSessionId();

        System.out.println("=== CALL 2: tools/list ===");
        String listBody = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";
        McpProtocolHandler.McpResponse listR = handler.handleRequestResponse(listBody, sessionId, null);
        System.out.println("body: " + listR.getBody());

        handler.shutdown();
    }

    @org.junit.jupiter.api.Test
    void debug_withToolRegistration() throws Exception {
        RateLimits limits = RateLimits.builder()
                .maxConcurrentSessions(100)
                .sessionTimeoutMs(Long.MAX_VALUE)
                .sessionCleanupIntervalMs(Long.MAX_VALUE)
                .read(10_000, 100_000, 5)
                .write(10_000, 100_000, 3)
                .admin(10_000, 100_000, 1)
                .build();
        McpServerConfig config = McpServerConfig.builder()
                .rateLimits(limits)
                .tools(true).resources(false).prompts(false)
                .build();
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);

        // Register a tool BEFORE initializing
        System.out.println("=== REGISTER TOOL ===");
        registry.registerTool("testTool", "A test tool",
                new java.util.LinkedHashMap<>(),
                new java.util.ArrayList<>(),
                (McpToolHandler) params -> new java.util.LinkedHashMap<>());
        System.out.println("Tool registered OK");

        String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        System.out.println("=== CALL 1: initialize ===");
        McpProtocolHandler.McpResponse initR = handler.handleRequestResponse(initBody, null, null);
        System.out.println("sessionId: " + initR.getSessionId());
        System.out.println("body: " + initR.getBody());

        handler.shutdown();
    }
}
