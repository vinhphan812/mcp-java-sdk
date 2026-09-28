# README and Documentation Audit — 2026-09-17

## Scope

This audit reviewed `README.md`, the documentation links it exposes, `docs/API-REFERENCE.md`, `docs/PROJECT-GUIDE.md`,
`docs/USER_GUIDE.md`, and the current public source for `RateLimits`, `QueueOverflowPolicy`, and `McpAuthorization`.

## Findings

| ID         | Description                                                           | Status                                                                                                | Evidence                                                                                                                                                                                                                   |
|------------|-----------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| README-001 | The README exposes the user guide and API reference.                  | VERIFIED                                                                                              | `README.md` links to `docs/USER_GUIDE.md` and `docs/API-REFERENCE.md` in Quick start; the documentation table also lists both.                                                                                             |
| README-002 | The README provides discoverable examples.                            | VERIFIED                                                                                              | `README.md` has an `Examples` section linking to `docs/GRIZZLY-EXAMPLE.md` and `examples/`.                                                                                                                                |
| API-001    | `McpAuthorization` documentation must match the SPI method signature. | VERIFIED                                                                                              | `docs/API-REFERENCE.md` documents `String denial(String[] requiredScopes, boolean confirmationRequired, Map<String, Object> arguments)`, matching `src/main/java/io/github/vinhphan812/mcp/api/spi/McpAuthorization.java`. |
|            | API-002                                                               | RateLimits builder documentation must use the current queue-overflow enum values.                     | VERIFIED                                                                                                                                                                                                                   | `docs/API-REFERENCE.md` updated to match `QueueOverflowPolicy.java` values (THROW_EXCEPTION, DROP_OLDEST, NOTIFY_LISTENER). |
|            | API-003                                                               | The RateLimits.Builder method table must cover the complete public builder surface.                   | VERIFIED                                                                                                                                                                                                                   | `docs/API-REFERENCE.md` updated to include all public `RateLimits.Builder` methods. |
|            | GUIDE-001                                                             | USER_GUIDE.md basic example imports and dependency coordinates must reference the current public SDK. | VERIFIED                                                                                                                                                                                                                   | `USER_GUIDE.md` import corrected to `io.github.vinhphan812.mcp.core.McpServer`. |
|            | GUIDE-002                                                             | Links within files under docs/ must be relative to the docs/ directory.                               | VERIFIED                                                                                                                                                                                                                   | All relative library links in `USER_GUIDE.md` updated to no longer include `docs/` prefix. |

## README change completed

`README.md` now adds:

- Quick-start links to the User Guide and API Reference.
- An `Examples` section linking to the Grizzly walkthrough and checked-in source examples.
- A User Guide entry in the core documentation table.

## Verification performed

- Inspected the current `README.md` after the update.
- Confirmed the README has two requested guide/reference links and four example-section references using static checks.
- Compared `RateLimits.Builder` declarations in `RateLimits.java` with `docs/API-REFERENCE.md`.
- Compared the `McpAuthorization.denial(...)` declaration with its API-reference signature.
- Ran `git diff --check -- README.md docs/audits/2026-09-17-readme-audit.md`; no whitespace errors in this task's files.

## Follow-up

The README requirements are complete. API-002, API-003, GUIDE-001, and GUIDE-002 require a focused documentation
remediation before the documentation set can be described as fully reconciled with the current public API.
