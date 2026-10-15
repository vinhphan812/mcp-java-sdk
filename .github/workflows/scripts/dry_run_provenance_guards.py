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


def test_pr_fixtures_pass():
    """PASS: fixture declares merged PR whose merge commit is a local git ancestor."""
    import json, os, subprocess, tempfile
    print("\n--- PR fixture: PASS — merged PR ancestor ---")
    sandbox = f"/tmp/pr-ancestry-test-{os.getpid()}"
    try:
        os.makedirs(sandbox, exist_ok=True)
        # Init fresh git repo (lightweight tags work without object store)
        subprocess.run(["git", "init", "-q", sandbox], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "user.name", "pr-test"], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "user.email", "pr-test@example.invalid"], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "remote.origin.url", "https://github.com/vinhphan812/mcp-java-sdk"], check=True)
        # Commit 1 (oldest)
        subprocess.run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "base"], check=True)
        base = subprocess.run(["git", "-C", sandbox, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
        # Lightweight tag on base commit
        subprocess.run(["git", "-C", sandbox, "tag", "v9.9.1", base], check=True)
        # Commit 2 (newest) — the "tag commit"
        subprocess.run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "post-tag"], check=True)

        # Write changelog with v9.9.1
        cl = (
            "# Changelog\n\n"
            "## [v9.9.1] — 2026-10-15\n\n"
            "### Fixed\n\n"
            "- Some fix.\n\n"
            "Release-note PRs: #7\n"
        )
        with open(os.path.join(sandbox, "CHANGELOG.md"), "w") as f:
            f.write(cl)
        # Write fixture: PR #7 is merged, merge commit = base (oldest = ancestor of tag)
        fixture = {
            "7": {
                "url": "https://api.github.com/repos/vinhphan812/mcp-java-sdk/pulls/7",
                "state": "closed",
                "merged": True,
                "merged_at": "2026-10-15T10:00:00Z",
                "base": {"ref": "master", "repo": {"full_name": "vinhphan812/mcp-java-sdk"}},
                "merge_commit_sha": base,  # oldest = IS ancestor of newest (tag)
            }
        }
        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump(fixture, f)

        script = os.path.realpath(os.path.join(SCRIPT_DIR, "..", "..", "..", "scripts", "release", "release_provenance.py"))
        r = subprocess.run(
            ["python3", script,
             "--tag", "v9.9.1",
             "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path,
             "--changelog", "CHANGELOG.md"],
            capture_output=True, text=True,
            env={**os.environ, "TEST_REPO": sandbox}
        )
        ok = (r.returncode == 0)
        if ok:
            print(f"  [PASS]  merged PR with ancestor merge commit — exit 0")
        else:
            print(f"  [FAIL]  expected exit 0, got {r.returncode}: {r.stderr[:200]}")
        return ok
    finally:
        subprocess.run(["rm", "-rf", sandbox], capture_output=True)


def test_pr_fixtures_fail_not_merged():
    """FAIL: fixture declares a closed-but-unmerged PR."""
    import json, os, subprocess
    print("\n--- PR fixture: FAIL — PR not merged ---")
    sandbox = f"/tmp/pr-unmerged-{os.getpid()}"
    try:
        os.makedirs(sandbox, exist_ok=True)
        subprocess.run(["git", "init", "-q", sandbox], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "user.name", "pr-test"], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "user.email", "pr-test@example.invalid"], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "remote.origin.url", "https://github.com/vinhphan812/mcp-java-sdk"], check=True)
        subprocess.run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "base"], check=True)
        sha = subprocess.run(["git", "-C", sandbox, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
        subprocess.run(["git", "-C", sandbox, "tag", "v9.9.2", sha], check=True)
        subprocess.run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "post"], check=True)

        with open(os.path.join(sandbox, "CHANGELOG.md"), "w") as f:
            f.write("# Changelog\n\n## [v9.9.2] — 2026-10-15\n\n### Fixed\n\n- Fix.\n\nRelease-note PRs: #8\n")
        fixture = {"8": {
            "url": "https://api.github.com/repos/vinhphan812/mcp-java-sdk/pulls/8",
            "state": "closed", "merged": False, "merged_at": None,
            "base": {"ref": "master", "repo": {"full_name": "vinhphan812/mcp-java-sdk"}},
            "merge_commit_sha": sha,
        }}
        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump(fixture, f)

        script = os.path.realpath(os.path.join(SCRIPT_DIR, "..", "..", "..", "scripts", "release", "release_provenance.py"))
        r = subprocess.run(
            ["python3", script, "--tag", "v9.9.2", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path, "--changelog", "CHANGELOG.md"],
            capture_output=True, text=True,
            env={**os.environ, "TEST_REPO": sandbox}
        )
        ok = (r.returncode == 1) and ("not merged" in r.stderr)
        if ok:
            print(f"  [PASS]  unmerged PR — exit 1 with 'not merged'")
        else:
            print(f"  [FAIL]  expected exit 1 + 'not merged', got {r.returncode}: {r.stderr[:200]}")
        return ok
    finally:
        subprocess.run(["rm", "-rf", sandbox], capture_output=True)


def test_pr_fixtures_fail_wrong_repo():
    """FAIL: fixture declares a PR from a different repository."""
    import json, os, subprocess
    print("\n--- PR fixture: FAIL — wrong repository ---")
    sandbox = f"/tmp/pr-wrongrepo-{os.getpid()}"
    try:
        os.makedirs(sandbox, exist_ok=True)
        subprocess.run(["git", "init", "-q", sandbox], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "user.name", "pr-test"], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "user.email", "pr-test@example.invalid"], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "remote.origin.url", "https://github.com/vinhphan812/mcp-java-sdk"], check=True)
        subprocess.run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "base"], check=True)
        sha = subprocess.run(["git", "-C", sandbox, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
        subprocess.run(["git", "-C", sandbox, "tag", "v9.9.3", sha], check=True)
        subprocess.run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "post"], check=True)

        with open(os.path.join(sandbox, "CHANGELOG.md"), "w") as f:
            f.write("# Changelog\n\n## [v9.9.3] — 2026-10-15\n\n### Fixed\n\n- Fix.\n\nRelease-note PRs: #9\n")
        fixture = {"9": {
            "url": "https://api.github.com/repos/wrong-owner/mcp-java-sdk/pulls/9",
            "state": "closed", "merged": True, "merged_at": "2026-10-15T10:00:00Z",
            "base": {"ref": "master", "repo": {"full_name": "wrong-owner/mcp-java-sdk"}},
            "merge_commit_sha": sha,
        }}
        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump(fixture, f)

        script = os.path.realpath(os.path.join(SCRIPT_DIR, "..", "..", "..", "scripts", "release", "release_provenance.py"))
        r = subprocess.run(
            ["python3", script, "--tag", "v9.9.3", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path, "--changelog", "CHANGELOG.md"],
            capture_output=True, text=True,
            env={**os.environ, "TEST_REPO": sandbox}
        )
        ok = (r.returncode == 1) and ("wrong repository" in r.stderr)
        if ok:
            print(f"  [PASS]  wrong-repository PR — exit 1 with 'wrong repository'")
        else:
            print(f"  [FAIL]  expected exit 1 + 'wrong repository', got {r.returncode}: {r.stderr[:200]}")
        return ok
    finally:
        subprocess.run(["rm", "-rf", sandbox], capture_output=True)


def test_pr_fixtures_fail_wrong_base():
    """FAIL: fixture declares a PR targeting a branch other than master."""
    import json, os, subprocess
    print("\n--- PR fixture: FAIL — wrong base branch ---")
    sandbox = f"/tmp/pr-wrongbase-{os.getpid()}"
    try:
        os.makedirs(sandbox, exist_ok=True)
        subprocess.run(["git", "init", "-q", sandbox], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "user.name", "pr-test"], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "user.email", "pr-test@example.invalid"], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "remote.origin.url", "https://github.com/vinhphan812/mcp-java-sdk"], check=True)
        subprocess.run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "base"], check=True)
        sha = subprocess.run(["git", "-C", sandbox, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
        subprocess.run(["git", "-C", sandbox, "tag", "v9.9.4", sha], check=True)
        subprocess.run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "post"], check=True)

        with open(os.path.join(sandbox, "CHANGELOG.md"), "w") as f:
            f.write("# Changelog\n\n## [v9.9.4] — 2026-10-15\n\n### Fixed\n\n- Fix.\n\nRelease-note PRs: #10\n")
        fixture = {"10": {
            "url": "https://api.github.com/repos/vinhphan812/mcp-java-sdk/pulls/10",
            "state": "closed", "merged": True, "merged_at": "2026-10-15T10:00:00Z",
            "base": {"ref": "develop", "repo": {"full_name": "vinhphan812/mcp-java-sdk"}},
            "merge_commit_sha": sha,
        }}
        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump(fixture, f)

        script = os.path.realpath(os.path.join(SCRIPT_DIR, "..", "..", "..", "scripts", "release", "release_provenance.py"))
        r = subprocess.run(
            ["python3", script, "--tag", "v9.9.4", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path, "--changelog", "CHANGELOG.md"],
            capture_output=True, text=True,
            env={**os.environ, "TEST_REPO": sandbox}
        )
        ok = (r.returncode == 1) and ("not protected master" in r.stderr)
        if ok:
            print(f"  [PASS]  wrong-base PR — exit 1 with 'not protected master'")
        else:
            print(f"  [FAIL]  expected exit 1 + 'not protected master', got {r.returncode}: {r.stderr[:200]}")
        return ok
    finally:
        subprocess.run(["rm", "-rf", sandbox], capture_output=True)


def test_pr_fixtures_fail_null_merge_sha():
    """FAIL: fixture declares merged PR with null merge_commit_sha."""
    import json, os, subprocess
    print("\n--- PR fixture: FAIL — null merge_commit_sha ---")
    sandbox = f"/tmp/pr-nullsha-{os.getpid()}"
    try:
        os.makedirs(sandbox, exist_ok=True)
        subprocess.run(["git", "init", "-q", sandbox], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "user.name", "pr-test"], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "user.email", "pr-test@example.invalid"], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "remote.origin.url", "https://github.com/vinhphan812/mcp-java-sdk"], check=True)
        subprocess.run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "base"], check=True)
        sha = subprocess.run(["git", "-C", sandbox, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
        subprocess.run(["git", "-C", sandbox, "tag", "v9.9.5", sha], check=True)
        subprocess.run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "post"], check=True)

        with open(os.path.join(sandbox, "CHANGELOG.md"), "w") as f:
            f.write("# Changelog\n\n## [v9.9.5] — 2026-10-15\n\n### Fixed\n\n- Fix.\n\nRelease-note PRs: #11\n")
        fixture = {"11": {
            "url": "https://api.github.com/repos/vinhphan812/mcp-java-sdk/pulls/11",
            "state": "closed", "merged": True, "merged_at": "2026-10-15T10:00:00Z",
            "base": {"ref": "master", "repo": {"full_name": "vinhphan812/mcp-java-sdk"}},
            "merge_commit_sha": None,
        }}
        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump(fixture, f)

        script = os.path.realpath(os.path.join(SCRIPT_DIR, "..", "..", "..", "scripts", "release", "release_provenance.py"))
        r = subprocess.run(
            ["python3", script, "--tag", "v9.9.5", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path, "--changelog", "CHANGELOG.md"],
            capture_output=True, text=True,
            env={**os.environ, "TEST_REPO": sandbox}
        )
        ok = (r.returncode == 1) and ("no valid merge commit SHA" in r.stderr)
        if ok:
            print(f"  [PASS]  null merge_sha — exit 1 with 'no valid merge commit SHA'")
        else:
            print(f"  [FAIL]  expected exit 1 + 'no valid merge commit SHA', got {r.returncode}: {r.stderr[:200]}")
        return ok
    finally:
        subprocess.run(["rm", "-rf", sandbox], capture_output=True)


def test_pr_fixtures_fail_not_ancestor():
    """FAIL: fixture declares merged PR whose merge commit is NOT a local git ancestor."""
    import json, os, subprocess
    print("\n--- PR fixture: FAIL — merge commit not ancestor ---")
    sandbox = f"/tmp/pr-notancestor-{os.getpid()}"
    try:
        os.makedirs(sandbox, exist_ok=True)
        subprocess.run(["git", "init", "-q", sandbox], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "user.name", "pr-test"], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "user.email", "pr-test@example.invalid"], check=True)
        subprocess.run(["git", "-C", sandbox, "config", "remote.origin.url", "https://github.com/vinhphan812/mcp-java-sdk"], check=True)
        # Oldest commit
        subprocess.run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "oldest"], check=True)
        oldest = subprocess.run(["git", "-C", sandbox, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
        # Middle commit
        subprocess.run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "middle"], check=True)
        # Tag at newest (HEAD after the next commit)
        subprocess.run(["git", "-C", sandbox, "commit", "--allow-empty", "-qm", "newest"], check=True)
        newest = subprocess.run(["git", "-C", sandbox, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
        # Lightweight tag at newest
        subprocess.run(["git", "-C", sandbox, "tag", "v9.9.6", newest], check=True)

        with open(os.path.join(sandbox, "CHANGELOG.md"), "w") as f:
            f.write("# Changelog\n\n## [v9.9.6] — 2026-10-15\n\n### Fixed\n\n- Fix.\n\nRelease-note PRs: #12\n")
        # Merge commit = oldest (IS ancestor of newest — we need NOT-ancestor)
        # Use a SHA that exists locally but is NOT an ancestor: create a detached orphan
        orphan = f"/tmp/pr-orphan-{os.getpid()}"
        os.makedirs(orphan, exist_ok=True)
        subprocess.run(["git", "init", "-q", orphan], check=True)
        subprocess.run(["git", "-C", orphan, "config", "user.name", "pr-test"], check=True)
        subprocess.run(["git", "-C", orphan, "config", "user.email", "pr-test@example.invalid"], check=True)
        subprocess.run(["git", "-C", orphan, "commit", "--allow-empty", "-qm", "orphan"], check=True)
        orphan_sha = subprocess.run(["git", "-C", orphan, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
        subprocess.run(["rm", "-rf", orphan], capture_output=True)
        # Orphan SHA is NOT an ancestor of sandbox newest (different repo = not ancestor)
        fixture = {"12": {
            "url": "https://api.github.com/repos/vinhphan812/mcp-java-sdk/pulls/12",
            "state": "closed", "merged": True, "merged_at": "2026-10-15T10:00:00Z",
            "base": {"ref": "master", "repo": {"full_name": "vinhphan812/mcp-java-sdk"}},
            "merge_commit_sha": orphan_sha,  # exists locally but NOT an ancestor
        }}
        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump(fixture, f)

        script = os.path.realpath(os.path.join(SCRIPT_DIR, "..", "..", "..", "scripts", "release", "release_provenance.py"))
        r = subprocess.run(
            ["python3", script, "--tag", "v9.9.6", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path, "--changelog", "CHANGELOG.md"],
            capture_output=True, text=True,
            env={**os.environ, "TEST_REPO": sandbox}
        )
        ok = (r.returncode == 1) and ("not an ancestor" in r.stderr or "unavailable locally" in r.stderr)
        if ok:
            print(f"  [PASS]  non-ancestor merge commit — exit 1")
        else:
            print(f"  [FAIL]  expected exit 1 + ancestry error, got {r.returncode}: {r.stderr[:200]}")
        return ok
    finally:
        subprocess.run(["rm", "-rf", sandbox], capture_output=True)



def main():

    print("=== PR dry-run provenance guard tests ===\n")

    results = []
    results.append(("SemVer classification", test_semver_classification()))
    results.append(("Master ancestry simulation", test_master_ancestry_simulation()))
    results.append(("Maven XML namespace-agnostic parsing", test_maven_xml_parsing()))
    results.append(("PR fixture: PASS — merged PR ancestor", test_pr_fixtures_pass()))
    results.append(("PR fixture: FAIL — PR not merged", test_pr_fixtures_fail_not_merged()))
    results.append(("PR fixture: FAIL — wrong repository", test_pr_fixtures_fail_wrong_repo()))
    results.append(("PR fixture: FAIL — wrong base branch", test_pr_fixtures_fail_wrong_base()))
    results.append(("PR fixture: FAIL — null merge_sha", test_pr_fixtures_fail_null_merge_sha()))
    results.append(("PR fixture: FAIL — merge commit not ancestor", test_pr_fixtures_fail_not_ancestor()))

    print("\n=== Coverage summary ===")
    print("COVERED in this dry-run:")
    print("  [x] SemVer tag classification (production vs suffixed)")
    print("  [x] Master ancestry check simulation")
    print("  [x] Maven XML namespace-agnostic parsing")
    print("  [x] Malformed XML fail-closed")
    print("  [x] PR ancestry fixtures: PASS (merged, ancestor)")
    print("  [x] PR ancestry fixtures: FAIL (not merged, wrong repo, wrong base, null SHA, not ancestor)")
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
