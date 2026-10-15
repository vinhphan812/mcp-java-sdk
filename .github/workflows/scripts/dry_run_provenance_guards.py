#!/usr/bin/env python3
"""Dry-run provenance guard tests for PR validation.

Covers:
  - SemVer tag classification (production vs suffixed)
  - Master-ancestry simulation
  - Maven XML metadata parsing (namespace-agnostic)

This script is read-only and makes no external state changes.
"""

import os
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CHECK_VERSION = os.path.join(SCRIPT_DIR, "check_maven_version.py")
CHECK_PUBLISHED = os.path.join(SCRIPT_DIR, "check_published_version.py")


def run(cmd, **kwargs):
    return subprocess.run(cmd, capture_output=True, text=True, **kwargs)


def test_semver_classification():
    print("--- SemVer classification ---")
    import re
    semver_pat = re.compile(r'^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$')
    test_cases = [
        ("v1.2.3",            "semver",    True),
        ("v0.0.1",            "semver",    True),
        ("v1.2.3-beta1",      "suffixed",  False),
        ("v1.2.3-skills-fix", "suffixed",  False),
        ("v1.2.3+build.123", "suffixed",  False),
        ("release-1.0",       "suffixed",  False),
        ("1.2.3",             "suffixed",  False),
        ("V1.2.3",            "suffixed",  False),
    ]
    all_pass = True
    for tag, expected_cls, expected_pub in test_cases:
        m = semver_pat.match(tag)
        cls = "semver" if m else "suffixed"
        pub = m is not None
        ok_cls = cls == expected_cls
        ok_pub = pub == expected_pub
        status = "PASS" if (ok_cls and ok_pub) else "FAIL"
        if status == "FAIL":
            all_pass = False
        print(f"  [{status}]  {tag:25s}  got cls={cls:10s} pub={pub}  expected cls={expected_cls:10s} pub={expected_pub}")
    return all_pass


def test_master_ancestry_simulation():
    print("\n--- Master ancestry simulation ---")
    sandbox = "/tmp/ancestry-test-$$"
    try:
        run(["rm", "-rf", sandbox])
        run(["git", "init", "-q", sandbox])
        run(["git", "-C", sandbox, "config", "user.name", "pr-test"])
        run(["git", "-C", sandbox, "config", "user.email", "pr-test@example.invalid"])

        run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "base"])
        base = run(["git", "-C", sandbox, "rev-parse", "HEAD"]).stdout.strip()

        run(["git", "-C", sandbox, "tag", "v9.9.9", base])
        run(["git", "-C", sandbox, "checkout", "-q", "master"])
        run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "post-tag"])

        r = run(["git", "-C", sandbox, "merge-base", "--is-ancestor", "v9.9.9", "master"])
        if r.returncode == 0:
            print(f"  [PASS]  v9.9.9 tag commit is ancestor of master")
        else:
            print(f"  [FAIL]  v9.9.9 tag commit should be ancestor of master")
            return False

        # Create a SEPARATE git repo to produce an orphaned commit
        # that has no relationship to the sandbox repo at all.
        orphan_sandbox = sandbox + "-orphan"
        run(["rm", "-rf", orphan_sandbox])
        run(["git", "init", "-q", orphan_sandbox])
        run(["git", "-C", orphan_sandbox, "config", "user.name", "pr-test"])
        run(["git", "-C", orphan_sandbox, "config", "user.email", "pr-test@example.invalid"])
        run(["git", "-C", orphan_sandbox, "commit", "--allow-empty", "-qm", "orphan"])
        orphaned_commit = run(["git", "-C", orphan_sandbox, "rev-parse", "HEAD"]).stdout.strip()
        run(["rm", "-rf", orphan_sandbox])

        # Orphaned commit is NOT an ancestor of master (completely disconnected repo)
        r = run(["git", "-C", sandbox, "merge-base", "--is-ancestor", orphaned_commit, "master"])
        if r.returncode == 0:
            print(f"  [FAIL]  orphaned commit should NOT be ancestor of master")
            return False
        else:
            print(f"  [PASS]  orphaned commit correctly rejected by ancestry check")
        return True
    finally:
        run(["rm", "-rf", sandbox])


def test_maven_xml_parsing():
    print("\n--- Maven XML namespace-agnostic parsing ---")
    all_pass = True

    fixtures = [
        # (label, xml_content, version_query, expect_found, script_key)
        (
            "no namespace — version present",
            '<?xml version="1.0" encoding="UTF-8"?>\n<metadata>\n  <versioning>\n    <versions>\n      <version>1.0.0</version>\n      <version>1.1.0</version>\n    </versions>\n  </versioning>\n</metadata>\n',
            "1.1.0", True, "check_version"
        ),
        (
            "http://maven.apache.org/metadata/v2 — version present",
            '<?xml version="1.0" encoding="UTF-8"?>\n<metadata xmlns="http://maven.apache.org/metadata/v2">\n  <versioning>\n    <versions>\n      <version>2.0.0</version>\n    </versions>\n  </versioning>\n</metadata>\n',
            "2.0.0", True, "check_version"
        ),
        (
            "http://maven.apache.org/METADATA/1.1.0 — version present",
            '<?xml version="1.0" encoding="UTF-8"?>\n<metadata xmlns="http://maven.apache.org/METADATA/1.1.0">\n  <versioning>\n    <versions>\n      <version>3.0.0</version>\n    </versions>\n  </versioning>\n</metadata>\n',
            "3.0.0", True, "check_version"
        ),
        (
            "no namespace — version absent",
            '<?xml version="1.0" encoding="UTF-8"?>\n<metadata>\n  <versioning>\n    <versions>\n      <version>1.0.0</version>\n    </versions>\n  </versioning>\n</metadata>\n',
            "9.9.9", False, "check_version"
        ),
        (
            "namespaced — version absent",
            '<?xml version="1.0" encoding="UTF-8"?>\n<metadata xmlns="http://maven.apache.org/METADATA/1.1.0">\n  <versioning>\n    <versions>\n      <version>3.0.0</version>\n    </versions>\n  </versioning>\n</metadata>\n',
            "9.9.9", False, "check_version"
        ),
        (
            "check_published: version found",
            '<?xml version="1.0" encoding="UTF-8"?>\n<metadata>\n  <versioning>\n    <versions>\n      <version>1.2.3</version>\n    </versions>\n  </versioning>\n</metadata>\n',
            "1.2.3", True, "check_published"
        ),
        (
            "check_published: version absent",
            '<?xml version="1.0" encoding="UTF-8"?>\n<metadata>\n  <versioning>\n    <versions>\n      <version>1.2.3</version>\n    </versions>\n  </versioning>\n</metadata>\n',
            "9.9.9", False, "check_published"
        ),
        (
            "malformed XML — fail closed (exit 2)",
            '<?xml version="1.0" encoding="UTF-8"?>\n<metadata>\n  <versioning>\n    <versions>\n      <version>1.0.0</version>\n    <!-- unclosed comment\n  </versioning>\n</metadata>\n',
            "1.0.0", False, "check_version"
        ),
    ]

    script_map = {"check_version": CHECK_VERSION, "check_published": CHECK_PUBLISHED}

    for label, xml_content, version_query, expect_found, script_key in fixtures:
        script = script_map[script_key]
        with tempfile.NamedTemporaryFile(mode='w', suffix='.xml', delete=False) as f:
            f.write(xml_content)
            xml_path = f.name

        try:
            r = subprocess.run(
                ["python3", script, version_query, xml_path],
                capture_output=True, text=True
            )
            rc = r.returncode

            if "malformed" in label:
                ok = (rc == 2)
                detail = f"exit={rc} (expect 2)"
            elif expect_found:
                if script_key == "check_published":
                    ok = (rc == 0)
                    detail = f"exit={rc} (expect 0)"
                else:
                    ok = (rc == 1)
                    detail = f"exit={rc} (expect 1)"
            else:
                if script_key == "check_published":
                    ok = (rc == 1)
                    detail = f"exit={rc} (expect 1)"
                else:
                    ok = (rc == 0)
                    detail = f"exit={rc} (expect 0)"

            status = "PASS" if ok else "FAIL"
            if not ok:
                all_pass = False
            print(f"  [{status}]  {label:50s}  {detail}")
            if rc != 0 and "malformed" not in label:
                print(f"           stderr: {r.stderr[:100]}")
        finally:
            os.unlink(xml_path)

    return all_pass


def main():
    print("=== PR dry-run provenance guard tests ===\n")

    results = []
    results.append(("SemVer classification", test_semver_classification()))
    results.append(("Master ancestry simulation", test_master_ancestry_simulation()))
    results.append(("Maven XML namespace-agnostic parsing", test_maven_xml_parsing()))

    print("\n=== Coverage summary ===")
    print("COVERED in this dry-run:")
    print("  [x] SemVer tag classification (production vs suffixed)")
    print("  [x] Master ancestry check simulation")
    print("  [x] Maven XML namespace-agnostic parsing")
    print("  [x] Malformed XML fail-closed")
    print("")
    print("NOT covered (require tag-push context):")
    print("  [ ] Tag object type (annotated vs lightweight)")
    print("  [ ] SHA equality: tag peel vs GITHUB_SHA")
    print("  [ ] Post-publish Maven metadata verification")
    print("  [ ] Release asset completeness")
    print("  [ ] Clean consumer resolution")
    print("")
    print("This script makes NO external state changes.")

    all_pass = all(r for _, r in results)
    print(f"\nOverall: {'ALL PASS' if all_pass else 'SOME FAILURES'}")
    sys.exit(0 if all_pass else 1)


if __name__ == "__main__":
    main()
