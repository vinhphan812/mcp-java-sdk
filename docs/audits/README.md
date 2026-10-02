# Audit Documentation

## Current Status

> **Note:** `AUDIT_STATUS.md` is superseded
> by [ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md](./ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md).
> The ADR audit covers all 18 ADRs and is the current authoritative audit record.

## Summary

- **Total findings**: 30
- **VERIFIED**: 30
- **PARTIAL**: 0
- **OPEN**: 0

## Key Documents

|| Document | Description |
||-------------------------------------------|--------------------------------------------------|
|| [ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md](./ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md) | ADR-to-source contract audit (
18 ADRs, all VERIFIED/resolved) — **current authoritative audit** |
|| [LOC-AUDIT.md](./LOC-AUDIT.md)            | Lines of code analysis |
|| [AUDIT_STATUS.md](./AUDIT_STATUS.md)      | Superseded — kept for historical reference only |
|| [historical/](./historical/)              | 45 archived triage/evidence files (superseded)   |

### Additional resources

- [Inspection governance policy and worklist](./evidence/inspect/GOVERNANCE/INSPECTION-GOVERNANCE.md) — current
  authoritative classification

## Categories

- **SEC**: Security findings (15)
- **DOC**: Documentation findings (13)
- **VER**: Verification findings (2)

## Historical archive — classification log (2026-09-28)

### `docs/audits/historical/` — 45 files

#### Deleted duplicates (5)

| File                                                      | Reason                                                 |
|-----------------------------------------------------------|--------------------------------------------------------|
| `McpRegistry-Concurrency-Review-t_c322a9bd-2026-09-20.md` | Identical to -2026-09-21.md; kept newer                |
| `metadata-immutability-policy-t_a21292c9-2026-09-20.md`   | Identical to -2026-09-21.md; kept newer                |
| `2026-09-01-full-source-audit.md`                         | Identical to main/ copy; main copy moved to historical |
| `2026-09-12-audit-supplement.md`                          | Identical to main/ copy; main copy moved to historical |
| `2026-09-12-full-source-audit.md`                         | Identical to main/ copy; main copy moved to historical |

#### Moved from `docs/audits/` (26) + pre-existing untracked (19) — all archived

Key criteria: superseded by ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md, no living references outside historical/, or fully
stale. Notable exceptions kept in historical/ for traceability: `RATELIMITS-DESTRUCTIVE-POLICY-AUDIT-2026-09-21.md`,
`SECURITY-TRIPLE-TRIAGE-2026-09-21.md` (referenced in RATE-LIMIT-429-BEHAVIOR-SPEC.md),
`REFLECTION-TOOL-POLICY-TRIAGE.md` (referenced in SCOPES-AUTHORIZATION-SPEC.md),
`McpRegistry-Technical-Analysis-2026-09-10.md` (referenced in ADR-0015).
