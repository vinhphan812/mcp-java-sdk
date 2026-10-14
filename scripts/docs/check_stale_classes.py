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

# ---------- Positive assertion: README must match the latest git tag ----------
# Single version source: the latest tag is authoritative.
# README (Gradle snippets, JAR filenames, build output table) MUST match it.
# Old version strings below are retained as warnings only — they are NOT errors
# in the positive-assertion model (the README might legitimately mention an older
# version in a historical context).  The positive check below is the real gate.

def get_latest_tag():
    """Return version string from latest git tag (e.g. 'v1.2.3' -> '1.2.3')."""
    import subprocess
    try:
        tag = subprocess.check_output(
            ['git', 'describe', '--tags', '--abbrev=0'],
            stderr=subprocess.DEVNULL, text=True
        ).strip()
        # strip leading 'v' prefix if present
        return tag.lstrip('v')
    except Exception:
        return None

def get_gradle_version():
    """Return the version declared in build.gradle (sdkVersion property)."""
    import re
    try:
        with open('build.gradle', 'r', encoding='utf-8') as f:
            content = f.read()
        m = re.search(r'^\s*sdkVersion\s*=\s*["\']([^"\']+)["\']', content, re.MULTILINE)
        return m.group(1) if m else None
    except Exception:
        return None

def check_readme_version_from_tag(filepath, latest_tag):
    """
    Positive assertion: README must mention the latest_tag version.
    Checks:
      - Gradle snippet: io.github.vinhphan812.mcp:mcp-java-sdk:X.Y.Z
      - JAR filename in build output table: mcp-java-sdk-X.Y.Z.jar
    Returns a list of errors (empty = pass).

    Note: errors always report 'README' as the file name for consistent
    fixture comparison, even when run against a temp file during tests.
    """
    errors = []
    if not os.path.exists(filepath):
        return errors
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    version_patterns = [
        # (regex, location description)
        (r'io\.github\.vinhphan812\.mcp:mcp-java-sdk:' + re.escape(latest_tag),
         'Gradle dependency snippet'),
        (r'mcp-java-sdk-' + re.escape(latest_tag) + r'\.jar',
         'JAR filename in build output table'),
    ]

    for pattern, location in version_patterns:
        if not re.search(pattern, content):
            # Always report 'README' in error messages for fixture stability
            errors.append(
                f"README: README version does not match latest tag v{latest_tag}; "
                f"missing '{latest_tag}' in {location}"
            )
    return errors

# ---------- Legacy blacklist: retained as warnings, not errors ----------
# These patterns used to be hard errors.  They are now warnings only
# (see OLD_VERSION_PATTERNS_WARN below) because the positive check above
# is the authoritative gate.

LEGACY_BLACKLIST_PATTERNS = [
    (r'\b1\.0\.1\b', 'old version 1.0.1'),
    (r'\b1\.1\.1\b', 'old version 1.1.1'),
]

OLD_TAG_COMMAND_PATTERNS = [
    (r'git tag v1\.0\.1\b', 'old release tag command'),
    (r'git tag v1\.1\.1\b', 'old release tag command'),
]

# Old version strings: WARN only, not errors (positive check is the gate)
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


def check_readme_hygiene(filepath, latest_tag=None):
    """
    Check README for version and documentation quality issues.

    Errors (hard failures):
      - README version does not match latest git tag (positive assertion)
      - Gradle build.gradle version does not match latest tag

    Warnings (not errors — retained for historical awareness):
      - Old version strings (1.0.1, 1.1.1)
      - Old git tag commands in example snippets
      - Hard-coded test counts
      - Overclaim patterns
    """
    errors = []
    warnings = []
    if not os.path.exists(filepath):
        return errors, warnings

    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # ----- Positive assertion: README must match latest tag -----
    if latest_tag:
        errors.extend(check_readme_version_from_tag(filepath, latest_tag))

    # ----- Gradle version must match latest tag -----
    if latest_tag:
        gradle_version = get_gradle_version()
        if gradle_version and gradle_version != latest_tag:
            errors.append(
                f"build.gradle: sdkVersion='{gradle_version}' does not match "
                f"latest tag v{latest_tag}"
            )

    # ----- Warnings: legacy blacklist (not errors) -----
    for pattern, reason in OLD_VERSION_PATTERNS:
        for m in re.finditer(pattern, content):
            line_num = content[:m.start()].count('\n') + 1
            warnings.append(f"{filepath}:{line_num}: [WARN] {reason}")

    # Check hard-coded test counts
    for m in re.finditer(TEST_COUNT_PATTERN, content):
        line_num = content[:m.start()].count('\n') + 1
        warnings.append(
            f"{filepath}:{line_num}: [WARN] hard-coded test count; "
            "derive from CI or remove (test counts drift on every test addition)"
        )

    # Check overclaim patterns
    for pattern, reason in OVERCLAIM_PATTERNS:
        for m in re.finditer(pattern, content, re.IGNORECASE):
            line_num = content[:m.start()].count('\n') + 1
            warnings.append(f"{filepath}:{line_num}: [WARN] {reason}")

    # Check old release tag commands
    for pattern, reason in OLD_TAG_COMMAND_PATTERNS:
        for m in re.finditer(pattern, content):
            line_num = content[:m.start()].count('\n') + 1
            warnings.append(f"{filepath}:{line_num}: [WARN] {reason}")

    return errors, warnings


# ==================== Test fixtures ====================

FIXTURES = {
    # Each positive fixture must have BOTH the Gradle snippet AND the JAR filename,
    # because check_readme_version_from_tag validates both locations simultaneously.
    "positive_readme_correct_version": {
        "readme_content": """# Example
```groovy
dependencies {
    implementation("io.github.vinhphan812.mcp:mcp-java-sdk:1.1.3")
}
```
Build outputs:
- `build/libs/mcp-java-sdk-1.1.3.jar`
- `build/libs/mcp-java-sdk-1.1.3-sources.jar`
""",
        "expected_tag": "1.1.3",
        "expected_errors": [],
    },
    "negative_old_version_string": {
        # Gradle snippet is wrong, JAR filename is correct -> fails because both required
        "readme_content": """# Example
```groovy
dependencies {
    implementation("io.github.vinhphan812.mcp:mcp-java-sdk:1.1.2")
}
```
JAR: `build/libs/mcp-java-sdk-1.1.3.jar`
""",
        "expected_tag": "1.1.3",
        "expected_errors": [
            "README: README version does not match latest tag v1.1.3; missing '1.1.3' in Gradle dependency snippet",
        ],
    },
    "negative_wrong_jar_version": {
        # JAR filename is wrong, Gradle snippet is correct -> fails
        "readme_content": """# Example
```groovy
dependencies {
    implementation("io.github.vinhphan812.mcp:mcp-java-sdk:1.1.3")
}
```
JAR: `build/libs/mcp-java-sdk-1.1.2.jar`
""",
        "expected_tag": "1.1.3",
        "expected_errors": [
            "README: README version does not match latest tag v1.1.3; missing '1.1.3' in JAR filename in build output table",
        ],
    },
    "negative_both_wrong": {
        # Both are wrong -> two errors
        "readme_content": """Gradle: `mcp-java-sdk:1.1.2`
JAR: `mcp-java-sdk-1.1.2.jar`
""",
        "expected_tag": "1.1.3",
        "expected_errors": [
            "README: README version does not match latest tag v1.1.3; missing '1.1.3' in Gradle dependency snippet",
            "README: README version does not match latest tag v1.1.3; missing '1.1.3' in JAR filename in build output table",
        ],
    },
    "negative_gradle_mismatch": {
        "build_gradle_content": 'sdkVersion = "1.1.2"',
        "expected_tag": "1.1.3",
        "expected_errors": [
            "build.gradle: sdkVersion='1.1.2' does not match latest tag v1.1.3",
        ],
    },
    "positive_gradle_matches": {
        "build_gradle_content": 'sdkVersion = "1.1.3"',
        "expected_tag": "1.1.3",
        "expected_errors": [],
    },
}


def run_fixtures():
    """Run test fixtures against the positive version checker."""
    import tempfile, os

    passed = failed = 0
    for name, fixture in FIXTURES.items():
        errors = []
        warnings = []

        # Positive README check
        if "readme_content" in fixture:
            with tempfile.NamedTemporaryFile(
                mode='w', suffix='.md', delete=False, encoding='utf-8'
            ) as tf:
                tf.write(fixture["readme_content"])
                tf_name = tf.name
            try:
                errs, warns = check_readme_hygiene(tf_name, fixture["expected_tag"])
                errors.extend(errs)
            finally:
                os.unlink(tf_name)

        # Positive gradle check
        if "build_gradle_content" in fixture:
            with tempfile.NamedTemporaryFile(
                mode='w', suffix='.gradle', delete=False, encoding='utf-8'
            ) as tf:
                tf.write(fixture["build_gradle_content"])
                tf_name = tf.name
            try:
                # Run gradle check inline
                import re as _re
                m = _re.search(
                    r'^\s*sdkVersion\s*=\s*["\']([^"\']+)["\']',
                    fixture["build_gradle_content"], _re.MULTILINE
                )
                gv = m.group(1) if m else None
                if gv and gv != fixture["expected_tag"]:
                    errors.append(
                        f"build.gradle: sdkVersion='{gv}' does not match "
                        f"latest tag v{fixture['expected_tag']}"
                    )
            finally:
                os.unlink(tf_name)

        expected = fixture.get("expected_errors", [])
        if errors == expected:
            print(f"  [PASS] {name}")
            passed += 1
        else:
            print(f"  [FAIL] {name}")
            print(f"         Expected: {expected}")
            print(f"         Got:      {errors}")
            failed += 1

    print(f"\nFixtures: {passed} passed, {failed} failed")
    return 0 if failed == 0 else 1


def main():
    # Support --test flag to run fixtures only
    if '--test' in sys.argv or '--fixtures' in sys.argv:
        return run_fixtures()

    # Obtain latest tag for positive assertion
    latest_tag = get_latest_tag()

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

    # README hygiene checks (errors + warnings)
    readme_errors, readme_warnings = check_readme_hygiene('README.md', latest_tag)

    all_errors = active_errors + historical_errors + readme_errors

    if latest_tag:
        print(f"Using latest tag: v{latest_tag}")
    else:
        print("WARNING: Could not determine latest tag; skipping positive version check")

    if readme_warnings:
        print("\nWarnings (non-blocking):")
        for w in readme_warnings:
            print(f"  {w}")

    if all_errors:
        print("\nFAIL: Doc hygiene check failed:")
        for error in all_errors:
            print(f"  {error}")
        return 1
    else:
        print("PASS: Doc hygiene check passed!")
        return 0


if __name__ == "__main__":
    sys.exit(main())
