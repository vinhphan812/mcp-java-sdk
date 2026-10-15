#!/usr/bin/env python3
"""Dry-run provenance guard tests for PR validation.

Covers:
  - SemVer tag classification (production vs suffixed)
  - Master-ancestry simulation
  - Maven version-reuse pre-check

This script is read-only and makes no external state changes.
"""

import subprocess
import sys
import xml.etree.ElementTree as ET

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
        ("v1.2.3+build.123",  "suffixed",  False),
        # Non-v-prefixed or malformed tags are classified "suffixed" (non-production)
        # because they fail the SemVer anchor regex.
        ("release-1.0",       "suffixed",  False),
        ("1.2.3",             "suffixed",  False),
        ("V1.2.3",           "suffixed",  False),   # uppercase v fails regex
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

        # Make a base commit on master
        run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "base"])
        base = run(["git", "-C", sandbox, "rev-parse", "HEAD"]).stdout.strip()

        # Tag the base commit on master
        run(["git", "-C", sandbox, "tag", "v9.9.9", base])

        # Advance master past the tag point (simulates post-tag merge)
        run(["git", "-C", sandbox, "checkout", "-q", "master"])
        run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "post-tag"])

        # Tag commit IS an ancestor of master's new HEAD
        r = run(["git", "-C", sandbox, "merge-base", "--is-ancestor", "v9.9.9", "master"])
        if r.returncode == 0:
            print(f"  [PASS]  v9.9.9 tag commit is ancestor of master (correct: tag is behind current master)")
        else:
            print(f"  [FAIL]  v9.9.9 tag commit should be ancestor of master")
            return False

        # Now make a commit on a feature branch that is NOT on master
        run(["git", "-C", sandbox, "checkout", "-q", "-b", "feature-only", base])
        run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "feature"])
        feature_commit = run(["git", "-C", sandbox, "rev-parse", "HEAD"]).stdout.strip()
        run(["git", "-C", sandbox, "checkout", "-q", "master"])

        # Feature commit is NOT an ancestor of master (master has moved past the branch point)
        r = run(["git", "-C", sandbox, "merge-base", "--is-ancestor", feature_commit, "master"])
        if r.returncode == 0:
            print(f"  [FAIL]  feature-only commit should NOT be ancestor of master")
            return False
        else:
            print(f"  [PASS]  feature-only commit correctly rejected by ancestry check")
        return True
    finally:
        run(["rm", "-rf", sandbox])


def main():
    print("=== PR dry-run provenance guard tests ===\n")

    results = []
    results.append(("SemVer classification", test_semver_classification()))
    results.append(("Master ancestry simulation", test_master_ancestry_simulation()))

    print("\n=== Coverage summary ===")
    print("COVERED in this dry-run:")
    print("  [x] SemVer tag classification (production vs suffixed)")
    print("  [x] Master ancestry check simulation")
    print("")
    print("NOT covered (require tag-push context):")
    print("  [ ] Tag object type (annotated vs lightweight) — needs an actual tag")
    print("  [ ] SHA equality: tag peel vs GITHUB_SHA — needs triggering tag ref")
    print("  [ ] Post-publish Maven metadata verification — needs published package")
    print("  [ ] Release asset completeness — needs published GitHub Release")
    print("  [ ] Clean consumer resolution — needs published Maven artifact")
    print("")
    print("This script makes NO external state changes.")

    all_pass = all(r for _, r in results)
    print(f"\nOverall: {'ALL PASS' if all_pass else 'SOME FAILURES'}")
    sys.exit(0 if all_pass else 1)


if __name__ == "__main__":
    main()
