# WORKING-TREE-RECONCILIATION-2026-09-23

## Summary

Audit of the repository `D:/android/mcp-java-sdk` reveals a mixed working tree. This report classifies files for
reconciliation and provides recommended commit groups.

## Classification

### 1. Intentional Deliverables (Untracked)

These are new features, documentation, or reorganizations intended for inclusion:

- `CHANGELOG.md`
- `docs/adr/ADR-0018-transport-contract-http-sse-vs-streamable-http.md`
- `docs/audits/CATEGORY-RESERVATION-CONTROL-FLOW-AUDIT-2026-09-23.md`
- `docs/audits/CATEGORY-RESERVATION-RECONCILIATION-2026-09-23.md`
- `docs/audits/CATEGORY-RESERVATION-TEST-EVIDENCE-2026-09-23.md`
- `docs/audits/TOOL-ERROR-CONTRACT-REVIEW-2026-09-23.md`
- `docs/authz/`
- `docs/guides/`
- `docs/transport/`
- `examples/src/main/java/io/github/vinhphan812/mcp/examples/HttpExample.java`
- `src/test/java/io/github/vinhphan812/mcp/core/`

### 2. Implicit Changes (Modified)

Changes that need to be reviewed and included with functional or documentation updates:

- `docs/API-REFERENCE.md`
- `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`

### 3. Obsolete / Stale (Deleted)

Files that were moved or replaced:

- `src/test/java/io/github/vinhphan812/mcp/transport/McpGrizzlyResumabilityTest.java` (Needs `git rm`)

## Recommended Commit Groups

1. **Docs Registry & ADR**: Stage all docs (ADR, audits, authz, guides, transport).
2. **Examples & Refactored Tests**: Stage `examples/` and new `src/test/java/.../core/` tests.
3. **Core Protocol Refactor**: Stage `src/main/java/.../McpProtocolHandler.java` and `docs/API-REFERENCE.md`.
4. **Cleanup**: Remove `src/test/java/io/github/vinhphan812/mcp/transport/McpGrizzlyResumabilityTest.java` from git.

## Collision Risks

- Verify `src/test/java/io/github/vinhphan812/mcp/core/` new tests do not overlap or conflict with existing logic in
  `McpProtocolHandler.java`.
- Ensure docs/transport hierarchy does not conflict with existing transport docs.
