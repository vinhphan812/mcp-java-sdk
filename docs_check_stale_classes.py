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
            # Find line number
            line_num = content[:match.start()].count('\n') + 1
            
            if requires_historical and not is_historical:
                errors.append(f"{filepath}:{line_num}: Stale class '{match.group()}' found in active doc (use [HISTORICAL] marker or update to current class name)")
            if requires_historical and is_historical:
                # Check if file contains any [HISTORICAL] marker (whole-file context for historical files)
                if '[HISTORICAL' not in content:
                    errors.append(f"{filepath}:{line_num}: Stale class '{match.group()}' in historical doc should have [HISTORICAL] marker")
    
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
    
    all_errors = active_errors + historical_errors
    
    if all_errors:
        print("❌ Doc hygiene check failed:")
        for error in all_errors:
            print(f"  {error}")
        return 1
    else:
        print("✅ Doc hygiene check passed!")
        return 0

if __name__ == "__main__":
    sys.exit(main())
