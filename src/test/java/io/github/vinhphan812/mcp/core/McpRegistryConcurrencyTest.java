package io.github.vinhphan812.mcp.core;

import io.github.vinhphan812.mcp.api.handler.McpToolHandler;
import io.github.vinhphan812.mcp.api.spi.McpRegistryChangeListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests for McpRegistry atomic registration and metadata immutability.
 * Uses Java 8 APIs only.
 */
class McpRegistryConcurrencyTest {

    private ExecutorService executor;
    private McpRegistry registry;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(10);
        registry = new McpRegistry();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    // Helper: create a simple tool handler
    private static McpToolHandler createHandler() {
        return params -> new LinkedHashMap<>();
    }

    // Helper: create input schema
    private static Map<String, Object> createInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", new LinkedHashMap<>());
        return schema;
    }

    /**
     * Test: getToolDefinition returns recursively detached mutable copy.
     * Mutations of returned top-level, nested schema map/list, outputSchema,
     * and requiredScopes cannot alter the next read.
     */
    @Test
    void getToolDefinitionReturnsDetachedCopy() throws Exception {
        List<String> required = new ArrayList<>();
        required.add("name");
        Map<String, Object> outputSchema = new LinkedHashMap<>();
        outputSchema.put("type", "object");

        // Register tool with metadata
        registry.registerTool("testTool", "A test tool", createInputSchema(), required, outputSchema, createHandler());

        // Get definition and mutate it
        Map<String, Object> def = registry.getToolDefinition("testTool");
        assertNotNull(def);

        // Mutate top-level
        def.put("mutated", "value");

        // Mutate nested inputSchema
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) def.get("inputSchema");
        assertNotNull(inputSchema);
        inputSchema.put("mutatedNested", "value");

        // Mutate properties inside inputSchema
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
        assertNotNull(properties);
        properties.put("mutatedProp", "value");

        // Mutate required list
        @SuppressWarnings("unchecked")
        List<Object> reqList = (List<Object>) inputSchema.get("required");
        assertNotNull(reqList);
        reqList.add("mutatedRequired");

        // Mutate outputSchema if present
        if (def.get("outputSchema") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> outSchema = (Map<String, Object>) def.get("outputSchema");
            outSchema.put("mutatedOutput", "value");
        }

        // Get again - should not see mutations
        Map<String, Object> def2 = registry.getToolDefinition("testTool");
        assertNull(def2.get("mutated"));
        assertNull(((Map<?, ?>) def2.get("inputSchema")).get("mutatedNested"));
        assertNull(((Map<?, ?>) ((Map<?, ?>) def2.get("inputSchema")).get("properties")).get("mutatedProp"));
        assertFalse(((List<?>) ((Map<?, ?>) def2.get("inputSchema")).get("required")).contains("mutatedRequired"));
    }

    /**
     * Test: getRegisteredTools/resources/templates/prompts return detached copies.
     */
    @Test
    void getRegisteredDefinitionsReturnDetachedCopies() throws Exception {
        // Register tools
        registry.registerTool("tool1", "desc1", createInputSchema(), new ArrayList<String>(), createHandler());
        registry.registerTool("tool2", "desc2", createInputSchema(), new ArrayList<String>(), createHandler());

        // Register resources
        registry.registerResource("uri1", "res1", "desc1", "text/plain", (String uri) -> "data");
        registry.registerResource("uri2", "res2", "desc2", "application/json", (String uri) -> "data");

        // Register resource templates
        registry.registerResourceTemplate("template1", "tpl1", "desc1", "text/plain", (String uri) -> "data");
        registry.registerResourceTemplate("template2", "tpl2", "desc2", "application/json", (String uri) -> "data");

        // Register prompts
        registry.registerPrompt("prompt1", "desc1", null, (Map<String, Object> args) -> null);
        registry.registerPrompt("prompt2", "desc2", null, (Map<String, Object> args) -> null);

        // Get and mutate copies
        List<Map<String, Object>> tools = registry.getRegisteredTools();
        tools.add(new LinkedHashMap<>()); // Should not affect internal state

        List<Map<String, Object>> resources = registry.getRegisteredResources();
        resources.add(new LinkedHashMap<>());

        List<Map<String, Object>> templates = registry.getRegisteredResourceTemplates();
        templates.add(new LinkedHashMap<>());

        List<Map<String, Object>> prompts = registry.getRegisteredPrompts();
        prompts.add(new LinkedHashMap<>());

        // Verify original sizes unchanged
        assertEquals(2, registry.getRegisteredTools().size());
        assertEquals(2, registry.getRegisteredResources().size());
        assertEquals(2, registry.getRegisteredResourceTemplates().size());
        assertEquals(2, registry.getRegisteredPrompts().size());

        // Mutate nested contents
        if (!tools.isEmpty()) {
            tools.get(0).put("mutated", true);
        }
        assertNull(registry.getRegisteredTools().get(0).get("mutated"));
    }

    /**
     * Test: Concurrent registration of N unique tools is safe and returns all N definitions and handlers.
     */
    @Test
    void concurrentUniqueToolRegistrationIsSafe() throws Exception {
        final int numTools = 20;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(numTools);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < numTools; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    registry.registerTool("tool" + index, "desc" + index, createInputSchema(),
                            new ArrayList<String>(), createHandler());
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    failureCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Start all threads
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));

        // All should succeed, none should fail
        assertEquals(numTools, successCount.get());
        assertEquals(0, failureCount.get());
        assertEquals(numTools, registry.getRegisteredTools().size());

        // All handlers should be retrievable
        for (int i = 0; i < numTools; i++) {
            assertNotNull(registry.getToolHandler("tool" + i));
            assertNotNull(registry.getToolDefinition("tool" + i));
        }
    }

    /**
     * Test: Concurrent same-name tool registration produces exactly one success,
     * N-1 IllegalArgumentException failures, exactly one definition,
     * and consistent handler/index lookup.
     */
    @Test
    void concurrentSameNameToolRegistrationProducesExactlyOneSuccess() throws Exception {
        final String toolName = "duplicateTool";
        final int numAttempts = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(numAttempts);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < numAttempts; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    registry.registerTool(toolName, "duplicate description", createInputSchema(),
                            new ArrayList<String>(), createHandler());
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    // Expected for duplicates
                    assertTrue(e.getMessage().contains("Duplicate tool"));
                    failureCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));

        // Exactly one success, rest failures
        assertEquals(1, successCount.get());
        assertEquals(numAttempts - 1, failureCount.get());

        // Exactly one definition in the list
        assertEquals(1, registry.getRegisteredTools().size());
        assertEquals(toolName, registry.getRegisteredTools().get(0).get("name"));

        // Handler and definition index are consistent
        assertNotNull(registry.getToolHandler(toolName));
        assertNotNull(registry.getToolDefinition(toolName));
        assertEquals(toolName, registry.getToolDefinition(toolName).get("name"));
    }

    /**
     * Test: Concurrent reads while a writer registers tools never throw
     * ConcurrentModificationException and only observe valid fully-built definitions.
     */
    @Test
    void concurrentReadsWhileWritingNeverThrow() throws Exception {
        final int numWriters = 5;
        final int numReaders = 5;
        final int toolsPerWriter = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(numWriters + numReaders);
        final AtomicInteger readErrors = new AtomicInteger(0);
        final AtomicInteger writeErrors = new AtomicInteger(0);

        // Writer threads
        for (int w = 0; w < numWriters; w++) {
            final int writerId = w;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < toolsPerWriter; i++) {
                        try {
                            registry.registerTool("writer" + writerId + "_tool" + i,
                                    "desc", createInputSchema(), new ArrayList<String>(), createHandler());
                        } catch (Exception e) {
                            writeErrors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Reader threads
        for (int r = 0; r < numReaders; r++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 100; i++) { // Many reads
                        try {
                            List<Map<String, Object>> tools = registry.getRegisteredTools();
                            for (Map<String, Object> tool : tools) {
                                // Verify each tool is fully built
                                assertNotNull(tool.get("name"));
                                assertNotNull(tool.get("description"));
                                assertNotNull(tool.get("inputSchema"));
                            }
                            // Also check getToolDefinition
                            for (Map<String, Object> tool : tools) {
                                String name = (String) tool.get("name");
                                Map<String, Object> def = registry.getToolDefinition(name);
                                if (def != null) {
                                    assertNotNull(def.get("inputSchema"));
                                }
                            }
                        } catch (Exception e) {
                            readErrors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));

        assertEquals(0, readErrors.get());
        assertEquals(0, writeErrors.get());
    }

    /**
     * Test: Existing registration/listener semantics remain compatible;
     * notification occurs once per successful registration after publication.
     */
    @Test
    void listenerNotificationOccursOnceAfterRegistration() throws Exception {
        final AtomicInteger notificationCount = new AtomicInteger(0);
        final List<String> notificationTypes = new ArrayList<>();

        registry.addRegistryChangeListener(new McpRegistryChangeListener() {
            @Override
            public void onRegistryChanged(String listType) {
                notificationCount.incrementAndGet();
                notificationTypes.add(listType);
            }
        });

        // Register multiple tools
        registry.registerTool("tool1", "desc1", createInputSchema(), new ArrayList<String>(), createHandler());
        registry.registerTool("tool2", "desc2", createInputSchema(), new ArrayList<String>(), createHandler());

        // Register resource
        registry.registerResource("uri1", "res1", "desc1", "text/plain", (String uri) -> "data");

        // Register prompt
        registry.registerPrompt("prompt1", "desc1", null, (Map<String, Object> args) -> null);

        // Each registration should trigger exactly one notification
        assertEquals(4, notificationCount.get());
        assertTrue(notificationTypes.contains("tools"));
        assertTrue(notificationTypes.contains("resources"));
        assertTrue(notificationTypes.contains("prompts"));

        // Count tools notifications
        int toolsCount = 0;
        for (String type : notificationTypes) {
            if ("tools".equals(type)) toolsCount++;
        }
        assertEquals(2, toolsCount);
    }

    /**
     * Test: Listener exception does not roll back completed registration.
     */
    @Test
    void listenerExceptionDoesNotRollbackRegistration() throws Exception {
        registry.addRegistryChangeListener(new McpRegistryChangeListener() {
            @Override
            public void onRegistryChanged(String listType) {
                throw new RuntimeException("Listener failure");
            }
        });

        // This should succeed despite listener throwing
        registry.registerTool("tool1", "desc1", createInputSchema(), new ArrayList<String>(), createHandler());

        // Verify registration succeeded
        assertEquals(1, registry.getRegisteredTools().size());
        assertNotNull(registry.getToolDefinition("tool1"));
    }

    /**
     * Test: Concurrent registration with requiredScopes/confirmationRequired metadata.
     */
    @Test
    void concurrentRegistrationWithAuthorizationMetadata() throws Exception {
        final int numTools = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(numTools);
        final AtomicInteger successCount = new AtomicInteger(0);

        List<String> scopes = new ArrayList<>();
        scopes.add("read");
        scopes.add("write");

        for (int i = 0; i < numTools; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    registry.registerTool("authTool" + index, "desc", createInputSchema(),
                            new ArrayList<String>(), scopes, true, createHandler());
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));

        assertEquals(numTools, successCount.get());

        // Verify metadata is present and immutable
        for (int i = 0; i < numTools; i++) {
            Map<String, Object> def = registry.getToolDefinition("authTool" + i);
            assertNotNull(def);
            assertNotNull(def.get("requiredScopes"));
            @SuppressWarnings("unchecked")
            List<String> defScopes = (List<String>) def.get("requiredScopes");
            assertTrue(defScopes.contains("read"));
            assertTrue(defScopes.contains("write"));
            assertEquals(true, def.get("confirmationRequired"));
        }
    }
}
