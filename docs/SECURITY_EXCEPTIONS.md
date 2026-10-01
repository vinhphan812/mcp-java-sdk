# Security Exception Registry

This document records all active, granted security exceptions to the project's
dependency vulnerability policy. Exceptions are granted by the maintainer and
must include a rationale and an expiry date.

When a dependency ships a fixed version, remove its entry and close the linked issue.

---

## Active Exceptions

_None currently granted._

---

## Expired / Resolved Exceptions

| Dependency | CVE | Severity | Granted | Expired | Notes |
|------------|-----|----------|---------|---------|-------|
| (none)     |     |          |         |         |       |

---

## Exception Review Cadence

- Exceptions are reviewed on each release (tag push).
- Any exception older than 90 days without a linked open GitHub issue is automatically
  escalated to the maintainer via the release workflow log.
- To add a new exception, edit this file in the same commit that introduces or retains
  the vulnerable dependency, and include a GitHub issue link.
