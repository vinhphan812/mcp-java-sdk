# Audit: RateLimits Destructive-Tool Policy Enforcement

**Date:** 2026-09-21
**Task:** t_c19a82c6
**Auditor:** dev-architect
**Files reviewed:** `RateLimits.java`, `McpProtocolHandler.java`, `McpSecurityDefaults.java`, `McpRateLimitTest.java`,
`McpRateLimitsConfigTest.java`

---

## 1. RateLimits.Builder: Accepted Destructive Tool Names

### Default set (L118-120, RateLimits.java; L146-148, McpProtocolHandler.java)

The `Builder` initialises `destructiveTools` with a hard-coded set of **6 names**:

```
"shutdown"
"delete_action"
"delete_prompt"
"set_mcp_api_key"
"revoke_mcp_api_key"
"upload_file"
```

This set is duplicated in two places:

- `RateLimits.Builder.destructiveTools` (L118-120)
- `McpProtocolHandler.DESTRUCTIVE_TOOLS` private static (L146-148) — **never read**; appears to be legacy

### Custom tool names

`Builder.destructiveTools(Set<String>)` at L136 accepts any arbitrary `Set<String>`. The test at
`McpRateLimitsConfigTest.java:33` confirms this is functional:

```java
.destructiveTools(Collections.singleton("custom-dangerous-tool"))
```

**Critical gap:** No validation is performed on tool names at `build()` time. Any string is accepted; typos and
mis-configurations silently pass.

---

## 2. Enforcement Switch: Enumeration of Hard-Coded Names and Actions

File: `McpProtocolHandler.java`, method `checkDestructiveCap()` at **L1169-1223**.

| Tool name(s)      | Counter field                      | Cap field                | Cooldown field                  | Cap default | Cooldown default        | Enforcement                                                         |
|-------------------|------------------------------------|--------------------------|---------------------------------|-------------|-------------------------|---------------------------------------------------------------------|
| `"shutdown"`      | `cl.shutdownCount` (AtomicInteger) | `rateLimits.shutdownCap` | `rateLimits.shutdownCooldownMs` | **3**       | **10 min (600,000 ms)** | Block with countdown message; counter resets after cooldown elapses |
| `"delete_action"` | `cl.deleteCount`                   | `rateLimits.deleteCap`   | `rateLimits.deleteCooldownMs`   | **10**      | **2 min (120,000 ms)**  | Same cooldown-reset pattern                                         |
| `"delete_prompt"` | `cl.deleteCount`                   | `rateLimits.deleteCap`   | `rateLimits.deleteCooldownMs`   | **10**      | **2 min (120,000 ms)**  | Shares delete counter with `delete_action`                          |
| `"upload_file"`   | `cl.uploadCount`                   | `rateLimits.uploadCap`   | `rateLimits.uploadCooldownMs`   | **5**       | **1 min (60,000 ms)**   | Same cooldown-reset pattern                                         |

**Only 3 of the 6 named tools have enforcement logic.**

| Named tool             | Has enforcement? | Notes                                                                 |
|------------------------|------------------|-----------------------------------------------------------------------|
| `"shutdown"`           | YES (L1172-1188) |                                                                       |
| `"delete_action"`      | YES (L1190-1204) | Shares counter with `delete_prompt`                                   |
| `"delete_prompt"`      | YES (L1191)      | Falls through to same block as `delete_action`                        |
| `"set_mcp_api_key"`    | **NO**           | Silent no-op — passes through `checkDestructiveCap` to default return |
| `"revoke_mcp_api_key"` | **NO**           | Silent no-op — passes through                                         |
| `"upload_file"`        | YES (L1206-1219) |                                                                       |

The dispatch gate at **L1161** confirms enforcement eligibility by checking
`rateLimits.destructiveTools.contains(toolName)`. For any tool whose name is in the configured set,
`checkDestructiveCap` is called — but the switch only handles 3 cases; the remaining 3 fall through to `return null` (
L1222) with no action taken.

---

## 3. Gap Analysis: Silently Ignored Custom Tool Policies

### Gap 1 — Named tools with no enforcement (3 of 6 default names)

`set_mcp_api_key` and `revoke_mcp_api_key` are listed in the default `destructiveTools` set, meaning a server operator
who calls those tools will consume a "slot" in the `destructiveTools` set (satisfying the gate at L1161), but
`checkDestructiveCap` will do nothing for them. They are effectively unrate-limited despite being in the destructive
set.

### Gap 2 — Custom dangerous tools via `destructiveTools(Set)`

If an operator configures `builder.destructiveTools(Set.of("my_delete", "my_upload", "my_shutdown"))`, all three will be
recognised at L1161 and routed to `checkDestructiveCap`, but only `"shutdown"`, `"delete_action"`/`"delete_prompt"`, and
`"upload_file"` will trigger enforcement — every other name silently falls through to `return null`.

### Gap 3 — No abuse scoring for enforced destructive tools

`addAbuseScore()` (L1225) is called only from category-limit violations in `checkMethodRateLimit` (L1021-1057). It is *
*never called** from `checkDestructiveCap`. This means hitting a destructive cap does not accumulate `abuseScore`, does
not contribute to session blocking, and does not appear in abuse logs.

### Gap 4 — State inflation with no enforcement

`cl.deleteCount` is incremented for `delete_action` AND `delete_prompt` (L1204), meaning each call of either tool counts
against the shared cap. This is intentional but undocumented. If a server registers only one of the two tools, the cap
will appear to consume faster than expected.

---

## 4. Data-Driven Alternative: Policy Lookup Map

Replace the hard-coded `switch` with a `Map<String, DestructivePolicy>` lookup.

### Proposed interface

```java
/**
 * Associates a destructive tool name with its enforcement parameters.
 * Implemented as a functional interface to allow inline lambdas or
 * configuration-driven construction.
 */
@FunctionalInterface
public interface DestructivePolicy {
    /**
     * Checks the per-session cap for this tool.
     *
     * @param cl          the session's rate-limit state (read/write)
     * @param toolName    the tool name (available for error messages)
     * @param now         current epoch ms
     * @param sessionId   session id (available for error messages)
     * @param rateLimits  the server's rate-limit configuration
     * @return null if allowed; an error string if the call should be blocked
     */
    String check(CategoryRateLimitState cl, String toolName, long now,
                 String sessionId, RateLimits rateLimits);

    /** Sentinel policy that performs no action — used for unknown tool names. */
    DestructivePolicy NO_OP = (cl, tool, now, sessionId, rl) -> null;
}
```

### Default policies (replacing the switch)

```java
private static final Map<String, DestructivePolicy> DESTRUCTIVE_POLICIES;

static {
    Map<String, DestructivePolicy> m = new LinkedHashMap<>();

    m.put("shutdown", (cl, tool, now, sid, rl) -> {
        if (cl.shutdownCount.get() >= rl.shutdownCap) {
            if (cl.shutdownLastMs == 0L) cl.shutdownLastMs = now;
            long elapsedSec = (now - cl.shutdownLastMs) / 1000;
            if (elapsedSec < rl.shutdownCooldownMs / 1000) {
                return "shutdown tool on cool-down, retry in "
                        + (rl.shutdownCooldownMs / 1000 - elapsedSec) + "s";
            }
            cl.shutdownCount.set(0);
            cl.shutdownLastMs = now;
        }
        cl.shutdownCount.incrementAndGet();
        return null;
    });

    // delete_action and delete_prompt share the same counter
    DestructivePolicy deletePolicy = (cl, tool, now, sid, rl) -> {
        if (cl.deleteCount.get() >= rl.deleteCap) {
            if (cl.deleteLastMs == 0L) cl.deleteLastMs = now;
            long elapsedSec = (now - cl.deleteLastMs) / 1000;
            if (elapsedSec < rl.deleteCooldownMs / 1000) {
                return tool + " on cool-down, retry in "
                        + (rl.deleteCooldownMs / 1000 - elapsedSec) + "s";
            }
            cl.deleteCount.set(0);
            cl.deleteLastMs = now;
        }
        cl.deleteCount.incrementAndGet();
        return null;
    };
    m.put("delete_action", deletePolicy);
    m.put("delete_prompt", deletePolicy);

    m.put("upload_file", (cl, tool, now, sid, rl) -> {
        if (cl.uploadCount.get() >= rl.uploadCap) {
            if (cl.uploadLastMs == 0L) cl.uploadLastMs = now;
            long elapsedSec = (now - cl.uploadLastMs) / 1000;
            if (elapsedSec < rl.uploadCooldownMs / 1000) {
                return "upload_file on cool-down, retry in "
                        + (rl.uploadCooldownMs / 1000 - elapsedSec) + "s";
            }
            cl.uploadCount.set(0);
            cl.uploadLastMs = now;
        }
        cl.uploadCount.incrementAndGet();
        return null;
    });

    DESTRUCTIVE_POLICIES = Collections.unmodifiableMap(m);
}
```

### Updated dispatch (L1161)

```java
// Before (existing gate check remains, but checkDestructiveCap changes):
String destructiveError = checkDestructiveCap(cl, toolName, now, sessionId);

// checkDestructibleCap refactored:
private String checkDestructiveCap(CategoryRateLimitState cl, String toolName,
                                   long now, String sessionId) {
    DestructivePolicy policy = DESTRUCTIVE_POLICIES.get(toolName);
    if (policy == null) policy = DestructivePolicy.NO_OP;
    return policy.check(cl, toolName, now, sessionId, rateLimits);
}
```

### Custom policy registration

```java
public Builder destructiveToolPolicy(String toolName, DestructivePolicy policy) {
    // Store alongside the tool name in destructiveTools; also register in a
    // Map<String, DestructivePolicy> customPolicies field.
    // At enforcement time: lookup customPolicies first, then DESTRUCTIVE_POLICIES.
    return this;
}
```

---

## 5. Backward-Compatibility Specification

### Requirement: Existing hard-coded names must continue to work

The default `DESTRUCTIVE_POLICIES` map is seeded with the same 3 effective entries (`shutdown`, `delete_action`+
`delete_prompt`, `upload_file`) using the same counter fields and the same cooldown-reset logic. This is a pure
refactor — no behavioural change for the tools currently under enforcement.

### Requirement: New custom tool policies are opt-in

Custom policies are **never auto-applied**. An operator must explicitly call:

```java
RateLimits.builder()
    .destructiveTools(Set.of("my_custom_tool"))
    .destructiveToolPolicy("my_custom_tool", customPolicy)  // new method
    .build();
```

If `.destructiveToolPolicy` is not called for a name in `destructiveTools`, it falls through to `NO_OP`, maintaining the
current silent-ignore behaviour rather than introducing a breaking change.

### Requirement: `set_mcp_api_key` and `revoke_mcp_api_key` remain in default set

These two names remain in `RateLimits.Builder.destructiveTools` default set for the sake of the name-set being
semantically complete (they ARE destructive operations). The enforcement gap for these two names is documented as a *
*known gap** (Gap 1 above) to be addressed in a separate follow-up task.

---

## 6. Findings Summary with Exact Code References

| #    | Severity   | Location                                                       | Finding                                                                                                                                                                                                                                                                                       |
|------|------------|----------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| F-01 | **HIGH**   | `McpProtocolHandler.java:1206-1221`                            | `"upload_file"`, `"delete_action"`, `"delete_prompt"`, and `"shutdown"` switch cases have no `default` branch. An unknown tool name falls through to `return null` silently — no log, no alert, no abuse score.                                                                               |
| F-02 | **HIGH**   | `McpProtocolHandler.java:146-148` vs `RateLimits.java:118-120` | `DESTRUCTIVE_TOOLS` private static set in `McpProtocolHandler` is **never read anywhere** (confirmed by grep: 1 definition, 0 usages). It is a dead field and a source of confusion. Should be removed.                                                                                       |
| F-03 | **MEDIUM** | `McpProtocolHandler.java:1225`                                 | `addAbuseScore` is **never called** from `checkDestructiveCap`. A session can exhaust all three destructive caps without accumulating a single abuse point, bypassing the `abuseScoreBlockThreshold` session-blocking mechanism entirely.                                                     |
| F-04 | **MEDIUM** | `RateLimits.java:136`                                          | `destructiveTools(Set)` accepts any `String` with no validation. A typo in a tool name (`"delete_acion"`) is silently accepted and the tool becomes unrate-limited.                                                                                                                           |
| F-05 | **LOW**    | `McpProtocolHandler.java:1191`                                 | `"delete_action"` and `"delete_prompt"` share `cl.deleteCount` but this sharing is not documented. If only one is registered, the cap appears to decay faster than the operator expects.                                                                                                      |
| F-06 | **LOW**    | `McpProtocolHandler.java:146-148`                              | The 6-name `DESTRUCTIVE_TOOLS` set (and the matching `RateLimits.Builder` default) includes `set_mcp_api_key` and `revoke_mcp_api_key` which have **no enforcement action** (they are in the gate but not in the switch). These names are effectively decorative.                             |
| F-07 | **INFO**   | `RateLimits.java:109-114`                                      | `RateLimits.Builder` copies defaults from `McpProtocolHandler` constants. `McpSecurityDefaults` defines the same constants but `RateLimits.Builder` references `McpProtocolHandler` directly, creating an indirect dependency. Recommend using `McpSecurityDefaults` as the canonical source. |

### Recommended follow-up tasks

1. **Remove dead `DESTRUCTIVE_TOOLS` static** — `McpProtocolHandler.java:146-148` (F-02).
2. **Add `default` branch with warning log** in `checkDestructiveCap` — unknown tool names should be logged at WARNING
   level (F-01).
3. **Call `addAbuseScore`** from `checkDestructiveCap` when a cap is hit (F-03).
4. **Add tool-name validation** in `RateLimits.Builder.destructiveTools(Set)` — either fail on unknown names or log a
   warning (F-04).
5. **Decide fate of `set_mcp_api_key` / `revoke_mcp_api_key`** — either remove from default set (Gap 1) or implement
   enforcement for them (separate task).
6. **Migrate to `DESTRUCTIVE_POLICIES` map** — replace the switch (Section 4 proposal).

---

*End of audit. Findings reference t_c19a82c6.*
