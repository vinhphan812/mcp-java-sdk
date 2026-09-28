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

import io.github.vinhphan812.mcp.api.config.McpSecurityDefaults;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.config.RateLimits;
import io.github.vinhphan812.mcp.api.handler.McpToolHandler;
import io.github.vinhphan812.mcp.api.handler.McpResourceHandler;
import io.github.vinhphan812.mcp.api.utils.ConcurrencyHook;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for CAS-based category concurrent slot reservation and exception-safe
 * release in {@link McpProtocolHandler}.
 *
 * <p>These tests verify the five acceptance criteria:
 * <ol>
 *   <li>Deterministic blocking: N concurrent requests enter handler; N+1 is denied -32029;
 *       release blockers and verify another request succeeds.</li>
 *   <li>Mixed read/write/admin: each cap is independent and never exceeds its cap
 *       under a common start barrier.</li>
 *   <li>Handler throw and all early response paths release a reserved slot.</li>
 *   <li>Stress: &ge;50 concurrent attempts per category; max in-flight never exceeds cap.</li>
 *   <li>Focused Gradle tests pass.</li>
 * </ol>
 *
 * <p>Design constraints:
 * <ul>
 *   <li>No {@code Thread.sleep} or {@code Thread.yield} to create race windows.</li>
 *   <li>No race on the slot itself — we use {@code CyclicBarrier} to force simultaneous entry.</li>
 *   <li>Java 8 compatible (no diamond operator with lambdas — explicit type where needed).</li>
 * </ul>
 */
@SuppressWarnings("unused")
class McpCategoryReservationTest {

    // ==================== Helpers ====================

    /**
     * Creates a handler with per-category burst/sustained generous (so only concurrent cap
     * is the bottleneck) and known sessions already established so category checks fire.
     * Creates SEPARATE sessions for each category to isolate burst rate-limit state.
     */
    private static TestContext makeContext(int readCap, int writeCap, int adminCap) throws Exception {
        return makeContext(readCap, writeCap, adminCap, null, null, null);
    }

    private static TestContext makeContext(int readCap, int writeCap, int adminCap,
                                           ConcurrencyHook readHook,
                                           ConcurrencyHook writeHook,
                                           ConcurrencyHook adminHook) throws Exception {
        RateLimits limits = RateLimits.builder()
                .maxConcurrentSessions(100)
                .sessionTimeoutMs(Long.MAX_VALUE)
                .sessionCleanupIntervalMs(Long.MAX_VALUE)
                // Burst/sustained high so concurrent cap is the only gate
                .read(10_000, 100_000, readCap)
                .write(10_000, 100_000, writeCap)
                .admin(10_000, 100_000, adminCap)
                .build();
        McpServerConfig config = McpServerConfig.builder()
                .rateLimits(limits)
                .tools(true).resources(true).prompts(false)
                .build();
        McpRegistry registry = new McpRegistry();
        // Register all tools so tests can use tools/call with explicit scopes
        registry.registerTool("readTool", "A read tool",
                new java.util.LinkedHashMap<String, Object>(),
                java.util.Collections.<String>emptyList(), java.util.Arrays.asList("read"), false,
                blockingHandler(readHook));
        registry.registerTool("writeTool", "A write tool",
                new java.util.LinkedHashMap<String, Object>(),
                java.util.Collections.<String>emptyList(), java.util.Arrays.asList("write"), false,
                blockingHandler(writeHook));
        registry.registerTool("adminTool", "An admin tool",
                new java.util.LinkedHashMap<String, Object>(),
                java.util.Collections.<String>emptyList(), java.util.Arrays.asList("admin"), false,
                blockingHandler(adminHook));
        // Register a resource so resources/subscribe (write category) succeeds
        registry.registerResource("test://x", "Test Resource", "A test resource",
                "text/plain",
                (McpResourceHandler) params -> "test content");
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);

        // Create separate sessions for each category to isolate burst rate-limit state
        // This ensures concurrent cap is the only limiting factor, not burst
        String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        McpProtocolHandler.McpResponse initR = handler.handleRequestResponse(initBody, null, null);
        assertFalse(initR.getBody().contains("-32029"), "Init must succeed; got: " + initR.getBody());
        String readSessionId = initR.getSessionId();
        assertTrue(readSessionId != null && !readSessionId.isEmpty(), "Must have a readSessionId");

        // Second session for write
        initBody = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}";
        initR = handler.handleRequestResponse(initBody, null, null);
        String writeSessionId = initR.getSessionId();
        assertTrue(writeSessionId != null && !writeSessionId.isEmpty(), "Must have a writeSessionId");

        // Third session for admin
        initBody = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"initialize\",\"params\":{}}";
        initR = handler.handleRequestResponse(initBody, null, null);
        String adminSessionId = initR.getSessionId();
        assertTrue(adminSessionId != null && !adminSessionId.isEmpty(), "Must have a adminSessionId");

        return new TestContext(handler, registry, readSessionId, writeSessionId, adminSessionId, readCap, writeCap, adminCap);
    }

    private static final class TestContext {
        final McpProtocolHandler handler;
        final McpRegistry registry;
        final String readSessionId;
        final String writeSessionId;
        final String adminSessionId;
        final int readCap;
        final int writeCap;
        final int adminCap;

        TestContext(McpProtocolHandler handler, McpRegistry registry, String readSessionId, String writeSessionId, String adminSessionId,
                    int readCap, int writeCap, int adminCap) {
            this.handler = handler;
            this.registry = registry;
            this.readSessionId = readSessionId;
            this.writeSessionId = writeSessionId;
            this.adminSessionId = adminSessionId;
            this.readCap = readCap;
            this.writeCap = writeCap;
            this.adminCap = adminCap;
        }
    }

    private static McpToolHandler blockingHandler(final ConcurrencyHook hook) {
        return new McpToolHandler() {
            @Override
            public Map<String, Object> call(Map<String, Object> params) throws Exception {
                if (hook != null) {
                    hook.blockIfAdmitted();
                }
                return new java.util.LinkedHashMap<String, Object>();
            }
        };
    }

    private static String toolCall(String id, String toolName) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                + "\",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + toolName + "\"}}";
    }

    private static boolean isAdmitted(String body) {
        if (body == null) return false;
        // Exclude -32029 (rate limit denied) and -32603 (internal error)
        if (body.contains("-32029")) return false;
        if (body.contains("-32603")) return false;
        // A successful response must contain "result" not "error"
        return body.contains("\"result\"") && !body.contains("\"error\"");
    }

    // ==================== Test 1: N concurrent requests — N+1 denied, release unblocks ====================

    /**
     * Test 1 (deterministic blocking):
     * With category cap=N, N concurrent requests enter the handler via CyclicBarrier.
     * Exactly N are admitted; N+1 is denied -32029.
     * After the N admitted calls complete and release their slots, another request succeeds.
     *
     * <p>No sleep/yield — concurrency is synchronised purely by barriers.
     */
    @org.junit.jupiter.api.Test
    void categoryCap_N_plus_1_denied_then_release_unblocks() throws Exception {
        final int cap = 2;
        final ConcurrencyHook readHook = new ConcurrencyHook(cap);
        final TestContext ctx = makeContext(cap, cap, cap, readHook, null, null);
        final CountDownLatch completed = new CountDownLatch(cap);
        final AtomicReference<Throwable> workerFailure = new AtomicReference<Throwable>();
        final AtomicInteger successes = new AtomicInteger();
        final ExecutorService executor = Executors.newFixedThreadPool(cap);

        try {
            submitCalls(executor, ctx, ctx.readSessionId, "readTool", "R", cap,
                    completed, workerFailure, successes);
            assertTrue(readHook.awaitEntered(10, TimeUnit.SECONDS),
                    "Read cap calls did not enter their handlers");

            assertOverflow(ctx, ctx.readSessionId, "readTool", "overflow");
        } finally {
            readHook.unblockAll();
            try {
                assertTrue(completed.await(10, TimeUnit.SECONDS),
                        "Admitted calls did not complete after the hook was released");
                assertNull(workerFailure.get(), "Admitted worker failed");
                assertEquals(cap, successes.get(), "Exactly the cap calls must succeed");

                McpProtocolHandler.McpResponse response = ctx.handler.handleRequestResponse(
                        toolCall("after-release", "readTool"), ctx.readSessionId, null);
                assertTrue(isAdmitted(response.getBody()),
                        "After release, another request must be admitted: " + response.getBody());
            } finally {
                executor.shutdownNow();
                ctx.handler.shutdown();
            }
        }
    }

    // ==================== Test 2: Mixed read/write/admin caps independent ====================

    /**
     * Test 2 (mixed categories):
     * Each category has its own cap. Under a common start barrier, fire read, write, and
     * admin calls simultaneously. Verify each category independently stays at or below
     * its cap. This proves the three AtomicInteger counters are independent.
     */
    @org.junit.jupiter.api.Test
    void mixed_read_write_admin_capsIndependent() throws Exception {
        final int readCap = 3;
        final int writeCap = 2;
        final int adminCap = 1;
        final ConcurrencyHook readHook = new ConcurrencyHook(readCap);
        final ConcurrencyHook writeHook = new ConcurrencyHook(writeCap);
        final ConcurrencyHook adminHook = new ConcurrencyHook(adminCap);
        final TestContext ctx = makeContext(readCap, writeCap, adminCap,
                readHook, writeHook, adminHook);
        final CountDownLatch completed = new CountDownLatch(readCap + writeCap + adminCap);
        final AtomicReference<Throwable> workerFailure = new AtomicReference<Throwable>();
        final AtomicInteger readSuccesses = new AtomicInteger();
        final AtomicInteger writeSuccesses = new AtomicInteger();
        final AtomicInteger adminSuccesses = new AtomicInteger();
        final ExecutorService executor = Executors.newFixedThreadPool(readCap + writeCap + adminCap);

        try {
            submitCalls(executor, ctx, ctx.readSessionId, "readTool", "R", readCap,
                    completed, workerFailure, readSuccesses);
            submitCalls(executor, ctx, ctx.writeSessionId, "writeTool", "W", writeCap,
                    completed, workerFailure, writeSuccesses);
            submitCalls(executor, ctx, ctx.adminSessionId, "adminTool", "A", adminCap,
                    completed, workerFailure, adminSuccesses);

            assertTrue(readHook.awaitEntered(10, TimeUnit.SECONDS),
                    "Read cap calls did not enter their handlers");
            assertTrue(writeHook.awaitEntered(10, TimeUnit.SECONDS),
                    "Write cap calls did not enter their handlers");
            assertTrue(adminHook.awaitEntered(10, TimeUnit.SECONDS),
                    "Admin cap calls did not enter their handlers");

            assertOverflow(ctx, ctx.readSessionId, "readTool", "RO");
            assertOverflow(ctx, ctx.writeSessionId, "writeTool", "WO");
            assertOverflow(ctx, ctx.adminSessionId, "adminTool", "AO");
        } finally {
            readHook.unblockAll();
            writeHook.unblockAll();
            adminHook.unblockAll();
            try {
                assertTrue(completed.await(10, TimeUnit.SECONDS),
                        "Admitted calls did not complete after hooks were released");
                assertNull(workerFailure.get(), "Admitted worker failed");
                assertEquals(readCap, readSuccesses.get(), "Read cap calls must succeed");
                assertEquals(writeCap, writeSuccesses.get(), "Write cap calls must succeed");
                assertEquals(adminCap, adminSuccesses.get(), "Admin cap calls must succeed");
            } finally {
                executor.shutdownNow();
                ctx.handler.shutdown();
            }
        }
    }

    private static void submitCalls(ExecutorService executor, final TestContext ctx,
                                    final String sessionId, final String toolName,
                                    final String idPrefix, int count,
                                    final CountDownLatch completed,
                                    final AtomicReference<Throwable> workerFailure,
                                    final AtomicInteger successes) {
        for (int i = 0; i < count; i++) {
            final String id = idPrefix + i;
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        McpProtocolHandler.McpResponse response =
                                ctx.handler.handleRequestResponse(toolCall(id, toolName), sessionId, null);
                        if (isAdmitted(response.getBody())) {
                            successes.incrementAndGet();
                        } else {
                            workerFailure.compareAndSet(null,
                                    new AssertionError("Expected admitted response for " + id + ": "
                                            + response.getBody()));
                        }
                    } catch (Throwable t) {
                        workerFailure.compareAndSet(null, t);
                    } finally {
                        completed.countDown();
                    }
                }
            });
        }
    }

    private static void assertOverflow(TestContext ctx, String sessionId, String toolName, String id) {
        McpProtocolHandler.McpResponse response =
                ctx.handler.handleRequestResponse(toolCall(id, toolName), sessionId, null);
        assertTrue(response.getBody().contains("-32029"),
                "Overflow " + toolName + " call must be denied: " + response.getBody());
    }

    // ==================== Test 3: Exception and early-return paths release slot ====================

    /**
     * Test 3a: tools/call with a handler that throws an Error.
     * An Error propagates through handleHandlerException up to the outer
     * Throwable catch block in handleRequestResponse, which releases the slot.
     * With cap=1, the next call succeeds.
     *
     * <p>Note: a RuntimeException from a tool handler is caught by
     * {@code handleHandlerException} which returns a Map (not an exception), so the
     * slot is still released via the finally block, not the catch path.
     * This test uses a custom Error to exercise the catch path specifically.
     */
    @org.junit.jupiter.api.Test
    void handlerThrow_releasesSlot() throws Exception {
        TestContext ctx = makeContext(1, 1, 1);

        // Use a custom Error subclass so it propagates past handleHandlerException
        final Error THROWING_ERROR = new Error("handler intentionally throws") {
        };

        // Register with explicit empty inputSchema to bypass required-param validation
        java.util.LinkedHashMap<String, Object> emptySchema = new java.util.LinkedHashMap<>();
        emptySchema.put("required", new java.util.ArrayList<>());
        ctx.registry.registerTool("throwingTool", "A throwing tool",
                emptySchema,
                java.util.Arrays.asList("read"),
                (McpToolHandler) params -> {
                    throw THROWING_ERROR;
                });

        try {
            // First call: Error propagates to catch(Throwable) → handleHandlerException
            // → errorToolResult → JSON-RPC success with isError:true
            String req1 = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"throwingTool\"}}";
            McpProtocolHandler.McpResponse r1 =
                    ctx.handler.handleRequestResponse(req1, ctx.readSessionId, null);
            // Error from handler returns isError:true (not -32603)
            assertTrue(r1.getBody().contains("\"isError\":true"),
                    "Handler Error should return isError:true; got: " + r1.getBody());

            // Second call with cap=1: must succeed because slot was released by catch block
            String req2 = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"throwingTool\"}}";
            McpProtocolHandler.McpResponse r2 =
                    ctx.handler.handleRequestResponse(req2, ctx.readSessionId, null);
            // Same handler error but slot was taken and released; still admitted (isError:true)
            assertTrue(r2.getBody().contains("\"isError\":true"),
                    "Second call should also return isError:true from handler; got: " + r2.getBody());
        } finally {
            ctx.handler.shutdown();
        }
    }

    /**
     * Test 3b: Method-not-found early return — the reserved slot is released before
     * the -32601 response is returned.
     */
    @org.junit.jupiter.api.Test
    void methodNotFound_earlyReturn_releasesSlot() throws Exception {
        TestContext ctx = makeContext(1, 1, 1);

        try {
            // First call: unknown method → early return before result is built
            String req1 = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"unknown/method\"}";
            McpProtocolHandler.McpResponse r1 =
                    ctx.handler.handleRequestResponse(req1, ctx.readSessionId, null);
            assertTrue(r1.getBody().contains("-32601"),
                    "Unknown method should return -32601; got: " + r1.getBody());

            // Second call with cap=1: must succeed because slot was released
            String req2 = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
            McpProtocolHandler.McpResponse r2 =
                    ctx.handler.handleRequestResponse(req2, ctx.readSessionId, null);
            assertTrue(isAdmitted(r2.getBody()),
                    "After early-return release, next call must be admitted; got: " + r2.getBody());
        } finally {
            ctx.handler.shutdown();
        }
    }

    /**
     * Test 3c: A blocked session (abuse score threshold) returns -32029 without
     * taking a slot (pre-CAS check).  The next call at cap=1 still succeeds
     * because no slot was consumed.
     */
    @org.junit.jupiter.api.Test
    void blockedSession_deniesWithoutConsumingSlot() throws Exception {
        TestContext ctx = makeContext(1, 1, 1);

        try {
            // Find the session's CategoryRateLimitState and block it
            McpProtocolHandler.CategoryRateLimitState cl =
                    (McpProtocolHandler.CategoryRateLimitState) ctx.handler.getSessionCategoryLimits(ctx.readSessionId);
            cl.setAbuseScore(McpSecurityDefaults.ABUSE_SCORE_BLOCK_THRESHOLD);
            cl.setBlocked(true);

            // Blocked session denies
            String req1 = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
            McpProtocolHandler.McpResponse r1 =
                    ctx.handler.handleRequestResponse(req1, ctx.readSessionId, null);
            assertTrue(r1.getBody().contains("-32029"),
                    "Blocked session should be denied -32029; got: " + r1.getBody());

            // Clear block — slot was never consumed (no CAS ran), so cap=1 is still free
            cl.setBlocked(false);
            String req2 = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
            McpProtocolHandler.McpResponse r2 =
                    ctx.handler.handleRequestResponse(req2, ctx.readSessionId, null);
            assertTrue(isAdmitted(r2.getBody()),
                    "After blocked denial (no slot taken), next call must succeed; got: "
                            + r2.getBody());
        } finally {
            ctx.handler.shutdown();
        }
    }

    // ==================== Test 4: Stress — >=50 concurrent per category, no over-admission ====================

    /**
     * Test 4 (stress):
     * Bounded ExecutorService, 60 concurrent attempts per category.
     * Records the maximum observed in-flight count per category and asserts it
     * never exceeds the configured cap.
     *
     * <p>Uses AtomicInteger as a proxy for the concurrent counter value, sampled
     * via the handler's test seam after all threads have entered the barrier.
     * No Thread.sleep or Thread.yield.
     */
    @org.junit.jupiter.api.Test
    void stress_fiftyConcurrent_noOverAdmission() throws Exception {
        final int readCap = 5;
        final int writeCap = 3;
        final int adminCap = 2;
        final int attemptsPerCategory = 60;

        TestContext ctx = makeContext(readCap, writeCap, adminCap);

        // All tools and test://x resource are already registered by makeContext.

        try {
            ExecutorService exec = Executors.newFixedThreadPool(attemptsPerCategory * 3 + 10);

            // Read stress
            AtomicInteger readMax = new AtomicInteger(0);
            AtomicInteger readAdmitted = new AtomicInteger(0);
            CountDownLatch readDone = new CountDownLatch(attemptsPerCategory);
            CyclicBarrier readBarrier = new CyclicBarrier(attemptsPerCategory);
            for (int i = 0; i < attemptsPerCategory; i++) {
                exec.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            readBarrier.await();
                            String req = "{\"jsonrpc\":\"2.0\",\"id\":\"R" + System.identityHashCode(this)
                                    + "\",\"method\":\"tools/call\",\"params\":{\"name\":\"readTool\",\"arguments\":{}}}";
                            McpProtocolHandler.McpResponse r =
                                    ctx.handler.handleRequestResponse(req, ctx.readSessionId, null);
                            if (isAdmitted(r.getBody())) {
                                int prev = readAdmitted.getAndIncrement();
                                System.out.println("DEBUG READ admitted response=" + r.getBody());
                                // Track a rough max (sampled at admission — not the true concurrent max
                                // because threads release at different times, but we also directly
                                // observe the counter below)
                            }
                            // Sample the actual counter while other threads may still be in-flight
                            McpProtocolHandler.CategoryRateLimitState limits =
                                    (McpProtocolHandler.CategoryRateLimitState) ctx.handler.getSessionCategoryLimits(ctx.readSessionId);
                            if (limits != null) {
                                int val = limits.readConcurrent.get();
                                for (; ; ) {
                                    if (val <= readMax.get()) break;
                                    if (readMax.compareAndSet(readMax.get(), val)) break;
                                }
                            }
                        } catch (Exception e) {
                            // ignore
                        } finally {
                            readDone.countDown();
                        }
                    }
                });
            }

            // Write stress (write category via tools/call with write scope)
            AtomicInteger writeMax = new AtomicInteger(0);
            AtomicInteger writeAdmitted = new AtomicInteger(0);
            CountDownLatch writeDone = new CountDownLatch(attemptsPerCategory);
            CyclicBarrier writeBarrier = new CyclicBarrier(attemptsPerCategory);
            for (int i = 0; i < attemptsPerCategory; i++) {
                exec.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            writeBarrier.await();
                            String req = "{\"jsonrpc\":\"2.0\",\"id\":\"W" + System.identityHashCode(this)
                                    + "\",\"method\":\"tools/call\","
                                    + "\"params\":{\"name\":\"writeTool\"}}";
                            McpProtocolHandler.McpResponse r =
                                    ctx.handler.handleRequestResponse(req, ctx.writeSessionId, null);
                            if (isAdmitted(r.getBody())) {
                                writeAdmitted.incrementAndGet();
                            }
                            McpProtocolHandler.CategoryRateLimitState limitsW =
                                    (McpProtocolHandler.CategoryRateLimitState) ctx.handler.getSessionCategoryLimits(ctx.writeSessionId);
                            if (limitsW != null) {
                                int val = limitsW.writeConcurrent.get();
                                for (; ; ) {
                                    if (val <= writeMax.get()) break;
                                    if (writeMax.compareAndSet(writeMax.get(), val)) break;
                                }
                            }
                        } catch (Exception e) {
                            // ignore
                        } finally {
                            writeDone.countDown();
                        }
                    }
                });
            }

            // Admin stress (admin category via tools/call with admin scope)
            AtomicInteger adminMax = new AtomicInteger(0);
            AtomicInteger adminAdmitted = new AtomicInteger(0);
            CountDownLatch adminDone = new CountDownLatch(attemptsPerCategory);
            CyclicBarrier adminBarrier = new CyclicBarrier(attemptsPerCategory);
            for (int i = 0; i < attemptsPerCategory; i++) {
                exec.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            adminBarrier.await();
                            String req = "{\"jsonrpc\":\"2.0\",\"id\":\"A" + System.identityHashCode(this)
                                    + "\",\"method\":\"tools/call\","
                                    + "\"params\":{\"name\":\"adminTool\"}}";
                            McpProtocolHandler.McpResponse r =
                                    ctx.handler.handleRequestResponse(req, ctx.adminSessionId, null);
                            if (isAdmitted(r.getBody())) {
                                adminAdmitted.incrementAndGet();
                            }
                            McpProtocolHandler.CategoryRateLimitState limitsA =
                                    (McpProtocolHandler.CategoryRateLimitState) ctx.handler.getSessionCategoryLimits(ctx.adminSessionId);
                            if (limitsA != null) {
                                int val = limitsA.adminConcurrent.get();
                                for (; ; ) {
                                    if (val <= adminMax.get()) break;
                                    if (adminMax.compareAndSet(adminMax.get(), val)) break;
                                }
                            }
                        } catch (Exception e) {
                            // ignore
                        } finally {
                            adminDone.countDown();
                        }
                    }
                });
            }

            assertTrue(readDone.await(30, TimeUnit.SECONDS),
                    "Read threads must complete within 30s");
            assertTrue(writeDone.await(30, TimeUnit.SECONDS),
                    "Write threads must complete within 30s");
            assertTrue(adminDone.await(30, TimeUnit.SECONDS),
                    "Admin threads must complete within 30s");

            exec.shutdown();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS), "Executor must shut down");

            // Assert: max in-flight must not exceed cap,
            // total admitted depends on throughput/duration, no direct assertion possible.

            // Max observed must not exceed cap
            assertTrue(readMax.get() <= readCap,
                    "Read max in-flight (" + readMax.get() + ") must not exceed cap " + readCap);
            assertTrue(writeMax.get() <= writeCap,
                    "Write max in-flight (" + writeMax.get() + ") must not exceed cap " + writeCap);
            assertTrue(adminMax.get() <= adminCap,
                    "Admin max in-flight (" + adminMax.get() + ") must not exceed cap " + adminCap);
        } finally {
            ctx.handler.shutdown();
        }
    }
}
