# HANDLER-DUPLICATION-AUDIT-2026-09-22.md

**File analyzed:** `src/main/java/io/github/vinhphan812/mcp/transport/McpHttpHandler.java`
**Date:** 2026-09-22
**Status:** ✅ RESOLVED — refactored 2026-09-22 by coordinator

**Before:** 721 lines, 6 constructor overloads, validateRequest not called from handlePost
**After:** 462 lines, Builder pattern, single canonical constructor, all handlers call validateRequest()

---

## 1. Constructor Chain (6 constructors, all delegating)

**Location:** Lines 94-232

**Current state:** 6 overloaded constructors each with 2-3 params, all delegating to a 9-parameter canonical
constructor.

**Evidence:**

- `McpHttpHandler(McpProtocolHandler handler)` - line 94
- `McpHttpHandler(McpProtocolHandler, String endpoint, Supplier<String> apiKeySupplier)` - line 107
-
`McpHttpHandler(McpProtocolHandler, String endpoint, Supplier<String>, Set<String> allowedOrigins, int maxRequestBodyBytes)` -
line 124
- `McpHttpHandler(..., int maxRequestBodyBytes, boolean trustXForwardedFor)` - line 143
- `McpHttpHandler(..., int maxRequestBodyBytes, int maxSseConnections)` - line 162
- `McpHttpHandler(..., int maxSseConnections, boolean trustXForwardedFor)` - line 183
- Canonical: `McpHttpHandler(..., boolean trustXForwardedFor, TransportMode transportMode)` - line 204

**Recommendation:** Use Builder pattern. Builder holds all config fields. Constructors delegate to canonical form.

---

## 2. handlePost Validation vs handleGet / handleDelete

**Location:** handlePost (lines 287-318), handleGet (lines 511-518), handleDelete (lines 635-642)

**Current state:** All three handlers repeat the same validation guards:

```java
if (isInvalidOrigin(request)) { writeError(response, 403, "Forbidden Origin"); return; }
if (isUnauthorized(request)) { writeError(response, 401, "Unauthorized"); return; }
```

**Evidence:**

- handlePost lines 288-291, 315-318
- handleGet lines 511-518
- handleDelete lines 635-642

**Recommendation:** Extract to `private boolean validateRequest(Request request, Response response) throws IOException`
that returns false (and writes error) on failure, true on success. Call at top of each handler.

---

## 3. acceptsPostResponse vs isModernClient Logic

**Location:** Lines 263-268, 467-489

**Current state:** `acceptsPostResponse()` checks Accept header for SSE. `isModernClient()` also checks Accept header (
no SSE = modern). These are inverted logic of each other.

**Evidence:**

- `acceptsPostResponse()` (lines 263-268):
  ```java
  private boolean acceptsPostResponse(Request request) {
      String value = request.getHeader("Accept");
      if (value == null) return false;
      String lower = value.toLowerCase(Locale.ROOT);
      return lower.contains("application/json") && lower.contains("text/event-stream");
  }
  ```

- `isModernClient()` (lines 467-489) - checks if Accept header does NOT contain "text/event-stream"

**Recommendation:** Extract common Accept header parsing. Keep `isModernClient()` and refactor `acceptsPostResponse()`
to delegate or share the Accept header check logic.

---

## 4. SSE Headers Boilerplate (3 places)

**Location:** handlePostStreaming, handleGet (legacy), handleGetReplayOnly

**Current state:** 3 places set the same SSE headers:

**Evidence:**

1. `handlePostStreaming()` lines 396-400:
   ```java
   response.setContentType("text/event-stream");
   response.setCharacterEncoding("UTF-8");
   response.setHeader("Cache-Control", "no-cache, no-transform");
   response.setHeader("Connection", "keep-alive");
   ```

2. `handleGet()` legacy mode (lines 549-552):
   ```java
   response.setContentType("text/event-stream");
   response.setCharacterEncoding("UTF-8");
   response.setHeader("Cache-Control", "no-cache, no-transform");
   response.setHeader("Connection", "keep-alive");
   ```

3. `handleGetReplayOnly()` lines 614-617:
   ```java
   response.setContentType("text/event-stream");
   response.setCharacterEncoding("UTF-8");
   response.setHeader("Cache-Control", "no-cache, no-transform");
   response.setHeader("Connection", "close");
   ```

**Recommendation:** Extract `private void setSseHeaders(Response response, boolean keepAlive)` helper. The boolean
controls the Connection header value.

---

## 5. writeError Method Overloads

**Location:** Lines 681-708

**Current state:** 3 overloads:

1. `writeError(Response, int status, String message)` - line 681-683
2. `writeRateLimitedError(Response, int status, String message, int limit, int remaining, long reset)` - line 685-688
3. `writeError(Response, int status, String message, Integer limit, Integer remaining, Long reset)` - line 690-708

**Analysis:** The first delegates to the third with nulls. The second also delegates to the third but with swapped
argument order (status/message vs message/status). The third does the actual work.

**Recommendation:** Simplify to 2 methods: `writeError(Response, int status, String message)` and
`writeError(Response, int status, String message, Integer limit, Integer remaining, Long reset)`. Have
writeRateLimitedError delegate directly to the second with correct argument order.

---

## Summary

| Pattern              | Severity | LOC Impact           |
|----------------------|----------|----------------------|
| Constructor chain    | Medium   | ~60 LOC duplicated   |
| Validation guards    | High     | ~12 LOC x 3 = 36 LOC |
| Accept header logic  | Medium   | ~10 LOC shared       |
| SSE headers          | High     | ~12 LOC x 3 = 36 LOC |
| writeError overloads | Low      | ~10 LOC              |

**Total estimated reduction:** ~150 LOC of duplication
