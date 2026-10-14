#!/usr/bin/env python3
"""
Doc hygiene guard: Check for stale class names in documentation.
This script validates that active documentation does not reference deprecated/stale class names
that have been renamed or removed. It is intended to be run as part of CI (validate-docs job).
Stale class patterns (must be marked as [HISTORICAL] if present):
- McpGrizzlyHandler -> McpHttpHandler (renamed 2026-09)
- McpGrizzlyServer -> does not exist (never committed)
- McpGrizzly*Test -> test classes that were never committed
"""
import os
import re
import sys

# Stale class patterns: (pattern, allowed_if_historical)
STALE_PATTERNS = [
    (r'\bMcpGrizzlyHandler\b', True),   # Must be marked [HISTORICAL]
    (r'\bMcpGrizzlyServer\b', True),     # Never existed, should not appear
    (r'\bMcpGrizzly(SsePermitFlow|Live|SecurityMatrix|Resumability)Test\.java\b', True),  # Never committed
]

# Files that are historical and may contain stale references (require [HISTORICAL] markers)
HISTORICAL_FILES = [
    'docs/adr/ADR-0016-sse-permit-flow-verification.md',
    'docs/architecture/MCP-SECURITY-SYNTHESIS-TIER2.md',
    'docs/authz/RATE-LIMIT-429-BEHAVIOR-SPEC.md',
    'docs/transport/SSE-LAST-EVENT-ID-REPLAY.md',
    'docs/transport/STREAMABLE-HTTP-MIGRATION-SPEC.md',
    'docs/testing/TEST-0002-sse-validation-plan.md',
]

# Files that should NOT contain stale references (active docs)
ACTIVE_FILES = [
    'README.md',
    'docs/guides/PROJECT-GUIDE.md',
    'docs/guides/API-REFERENCE.md',
    'docs/guides/IMPLEMENTATION-STATUS.md',
    'docs/guides/HTTP-TRANSPORT-EXAMPLE.md',
    'docs/architecture/MCP-COMPATIBILITY-2026.md',
]

# ==================== README / Version hygiene ====================

# Old version strings (must not appear in README)
OLD_VERSION_PATTERNS = [
    (r'\b1\.0\.1\b', 'old version 1.0.1; update to current stable'),
    (r'\b1\.1\.1\b', 'old version 1.1.1; update to current stable'),
]

# Hard-coded test counts (should derive from CI, not manual entry)
TEST_COUNT_PATTERN = r'\b\d+ tests pass\b'

# Overclaim patterns (must not appear in active docs)
OVERCLAIM_PATTERNS = [
    (r'full JSON Schema 2020-12 support',
     'overclaim: validator is a subset, not full JSON Schema 2020-12'),
    (r'full JSON Schema validation',
     'overclaim: validator is a subset, not full JSON Schema'),
]

# Old release tag commands (should show current tag, not historical)
OLD_TAG_PATTERNS = [
    (r'git tag v1\.0\.1\b', 'old release tag command; update to current stable'),
    (r'git tag v1\.1\.1\b', 'old release tag command; update to current stable'),
]


def check_file(filepath):
    """Check a single file for stale class references."""
    errors = []
    if not os.path.exists(filepath):
        return errors

    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    is_historical = any(filepath.startswith(h) for h in HISTORICAL_FILES)
    for pattern, requires_historical in STALE_PATTERNS:
        matches = re.finditer(pattern, content)
        for match in matches:
            line_num = content[:match.start()].count('\n') + 1
            if requires_historical and not is_historical:
                errors.append(f"{filepath}:{line_num}: Stale class '{match.group()}' found in active doc (use [HISTORICAL] marker or update to current class name)")
            if requires_historical and is_historical:
                if '[HISTORICAL' not in content:
                    errors.append(f"{filepath}:{line_num}: Stale class '{match.group()}' in historical doc should have [HISTORICAL] marker")
    return errors


def check_readme_hygiene(filepath):
    """Check README for stale version strings, hard-coded test counts, overclaims."""
    errors = []
    if not os.path.exists(filepath):
        return errors

    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Check old version strings
    for pattern, reason in OLD_VERSION_PATTERNS:
        for m in re.finditer(pattern, content):
            line_num = content[:m.start()].count('\n') + 1
            errors.append(f"{filepath}:{line_num}: Stale version string: {reason}")

    # Check hard-coded test counts
    for m in re.finditer(TEST_COUNT_PATTERN, content):
        line_num = content[:m.start()].count('\n') + 1
        errors.append(f"{filepath}:{line_num}: hard-coded test count; derive from CI or remove (test counts drift on every test addition)")

    # Check overclaim patterns
    for pattern, reason in OVERCLAIM_PATTERNS:
        for m in re.finditer(pattern, content, re.IGNORECASE):
            line_num = content[:m.start()].count('\n') + 1
            errors.append(f"{filepath}:{line_num}: {reason}")

    # Check old release tag commands
    for pattern, reason in OLD_TAG_PATTERNS:
        for m in re.finditer(pattern, content):
            line_num = content[:m.start()].count('\n') + 1
            errors.append(f"{filepath}:{line_num}: {reason}")

    return errors


def main():
    # Check active docs (should not have stale references)
    active_errors = []
    for filepath in ACTIVE_FILES:
        if os.path.exists(filepath):
            active_errors.extend(check_file(filepath))

    # Check historical docs (should have [HISTORICAL] markers)
    historical_errors = []
    for filepath in HISTORICAL_FILES:
        if os.path.exists(filepath):
            historical_errors.extend(check_file(filepath))

    # README hygiene checks
    readme_errors = check_readme_hygiene('README.md')

    all_errors = active_errors + historical_errors + readme_errors

    if all_errors:
        print("FAIL: Doc hygiene check failed:")
        for error in all_errors:
            print(f"  {error}")
        return 1
    else:
        print("PASS: Doc hygiene check passed!")
        return 0


if __name__ == "__main__":
    sys.exit(main())
