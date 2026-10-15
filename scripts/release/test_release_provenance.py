#!/usr/bin/env python3
"""Hermetic unit tests for release_provenance.py PR ancestry gate.

Test categories (hermetic, no live GitHub API):
  PASS  — fixture declares a valid merged PR whose merge commit is a git ancestor
  FAIL  — each distinct failure mode of verify_pr()

Run from the repository root:
    python scripts/release/test_release_provenance.py [--repo ROOT]
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile

SCRIPT = os.path.abspath(os.path.join(os.path.dirname(__file__), "release_provenance.py"))
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # scripts/release/test.py → scripts/ → worktree/
CL_ROOT = os.path.join(REPO, "CHANGELOG.md")


# ---------------------------------------------------------------------------
# Sandbox helpers
# ---------------------------------------------------------------------------

def git(args, cwd=None):
    r = subprocess.run(
        ["git"] + args, cwd=cwd or REPO,
        capture_output=True, text=True, check=False
    )
    return r.stdout.strip(), r.returncode


def make_sandbox():
    """Clone the repo into a temporary directory with full history.
    
    After cloning, set the origin to the canonical GitHub URL so that
    canonical_origin() in release_provenance.py correctly identifies the repository.
    Without this, a local file:// clone stores the local path as origin.
    """
    d = tempfile.mkdtemp(prefix="prov_test_")
    # Use os.path.realpath to resolve any symlinks; bare path works with git-bash on Windows
    repo_path = os.path.realpath(REPO)
    subprocess.run(["git", "clone", "--quiet", repo_path, d], check=True)
    # Override origin to canonical GitHub URL (worktree file:// clone stored the local path)
    github_remote = "https://github.com/vinhphan812/mcp-java-sdk"
    subprocess.run(["git", "remote", "set-url", "origin", github_remote], cwd=d, check=True)
    return d


def make_test_branch(sandbox, *commits):
    """Create a branch with `commits` (each is a commit SHA)."""
    subprocess.run(["git", "checkout", "-q", commits[0]], cwd=sandbox)
    subprocess.run(["git", "checkout", "-qb", "test/prov"], cwd=sandbox)
    for sha in commits[1:]:
        subprocess.run(["git", "reset", "--hard", "-q", sha], cwd=sandbox)
    return sandbox


def tag_in_sandbox(sandbox, tag, sha):
    subprocess.run(["git", "tag", "-a", tag, "-m", f"Annotated tag {tag}"], cwd=sandbox)
    return tag


def write_changelog(sandbox, text):
    path = os.path.join(sandbox, "CHANGELOG.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    subprocess.run(["git", "add", "CHANGELOG.md"], cwd=sandbox)
    subprocess.run(["git", "commit", "-m", "Update CHANGELOG"], cwd=sandbox)


def make_fixture(
    number,
    repository="vinhphan812/mcp-java-sdk",
    state="closed",
    merged=True,
    merged_at="2026-10-15T10:00:00Z",
    base_ref="master",
    merge_commit_sha="0" * 40,
    url=None,
):
    if url is None:
        url = f"https://api.github.com/repos/{repository}/pulls/{number}"
    return {
        "url": url,
        "state": state,
        "merged": merged,
        "merged_at": merged_at if merged else None,
        "base": {
            "ref": base_ref,
            "repo": {"full_name": repository},
        },
        "merge_commit_sha": merge_commit_sha,
    }


def run_script(args, env=None, cwd=None):
    env = dict(os.environ, **(env or {}))
    r = subprocess.run(
        [sys.executable, SCRIPT] + args,
        capture_output=True, text=True, env=env, cwd=cwd or REPO
    )
    return r.stdout, r.stderr, r.returncode


# ---------------------------------------------------------------------------
# Test cases
# ---------------------------------------------------------------------------

def test_github_api_stub_missing_fixture():
    """FAIL: fixture file does not contain a declared PR number."""
    sandbox = make_sandbox()
    try:
        commits, _ = git(["log", "--format=%H", "-n", "3"], sandbox)
        commits = commits.splitlines()
        sha = commits[0]

        # Write changelog with PR #99999
        cl = (
            "# Changelog\n\n## [Unreleased]\n\n## [v9.9.9] — 2026-10-15\n\n"
            "### Fixed\n\n- Some fix.\n\nRelease-note PRs: #99999\n"
        )
        write_changelog(sandbox, cl)
        subprocess.run(["git", "tag", "-a", "v9.9.9", "-m", "v9.9.9"], cwd=sandbox)

        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump({}, f)  # empty — #99999 not present

        _, stderr, rc = run_script(
            ["--tag", "v9.9.9", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path,
             "--changelog", "CHANGELOG.md"],
            cwd=sandbox,
        )
        assert rc == 1, f"expected exit 1, got {rc}"
        assert "fixture is missing" in stderr, f"unexpected: {stderr}"
        print("[PASS] test_github_api_stub_missing_fixture")
    finally:
        subprocess.run(["rm", "-rf", sandbox], check=False)


def test_github_api_wrong_repository():
    """FAIL: PR belongs to a different repository."""
    sandbox = make_sandbox()
    try:
        commits, _ = git(["log", "--format=%H", "-n", "3"], sandbox)
        commits = commits.splitlines()
        sha = commits[0]

        cl = (
            "# Changelog\n\n## [v9.9.8] — 2026-10-15\n\n"
            "### Fixed\n\n- Some fix.\n\nRelease-note PRs: #1\n"
        )
        write_changelog(sandbox, cl)
        subprocess.run(["git", "tag", "-a", "v9.9.8", "-m", "v9.9.8"], cwd=sandbox)

        fixture = {"1": make_fixture(1, repository="wrong-owner/mcp-java-sdk")}
        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump(fixture, f)

        _, stderr, rc = run_script(
            ["--tag", "v9.9.8", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path,
             "--changelog", "CHANGELOG.md"],
            cwd=sandbox,
        )
        assert rc == 1, f"expected exit 1, got {rc}"
        assert "wrong repository" in stderr, f"unexpected: {stderr}"
        print("[PASS] test_github_api_wrong_repository")
    finally:
        subprocess.run(["rm", "-rf", sandbox], check=False)


def test_github_api_not_merged():
    """FAIL: PR is closed but not merged."""
    sandbox = make_sandbox()
    try:
        commits, _ = git(["log", "--format=%H", "-n", "3"], sandbox)
        commits = commits.splitlines()
        sha = commits[0]

        cl = (
            "# Changelog\n\n## [v9.9.7] — 2026-10-15\n\n"
            "### Fixed\n\n- Some fix.\n\nRelease-note PRs: #2\n"
        )
        write_changelog(sandbox, cl)
        subprocess.run(["git", "tag", "-a", "v9.9.7", "-m", "v9.9.7"], cwd=sandbox)

        fixture = {"2": make_fixture(2, merged=False, state="closed")}
        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump(fixture, f)

        _, stderr, rc = run_script(
            ["--tag", "v9.9.7", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path,
             "--changelog", "CHANGELOG.md"],
            cwd=sandbox,
        )
        assert rc == 1, f"expected exit 1, got {rc}"
        assert "not merged" in stderr, f"unexpected: {stderr}"
        print("[PASS] test_github_api_not_merged")
    finally:
        subprocess.run(["rm", "-rf", sandbox], check=False)


def test_github_api_wrong_base_ref():
    """FAIL: PR targets a branch other than master."""
    sandbox = make_sandbox()
    try:
        commits, _ = git(["log", "--format=%H", "-n", "3"], sandbox)
        commits = commits.splitlines()
        sha = commits[0]

        cl = (
            "# Changelog\n\n## [v9.9.6] — 2026-10-15\n\n"
            "### Fixed\n\n- Some fix.\n\nRelease-note PRs: #3\n"
        )
        write_changelog(sandbox, cl)
        subprocess.run(["git", "tag", "-a", "v9.9.6", "-m", "v9.9.6"], cwd=sandbox)

        fixture = {"3": make_fixture(3, base_ref="develop")}
        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump(fixture, f)

        _, stderr, rc = run_script(
            ["--tag", "v9.9.6", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path,
             "--changelog", "CHANGELOG.md"],
            cwd=sandbox,
        )
        assert rc == 1, f"expected exit 1, got {rc}"
        assert "not protected master" in stderr, f"unexpected: {stderr}"
        print("[PASS] test_github_api_wrong_base_ref")
    finally:
        subprocess.run(["rm", "-rf", sandbox], check=False)


def test_github_api_null_merge_sha():
    """FAIL: merged PR has null merge_commit_sha."""
    sandbox = make_sandbox()
    try:
        commits, _ = git(["log", "--format=%H", "-n", "3"], sandbox)
        commits = commits.splitlines()
        sha = commits[0]

        cl = (
            "# Changelog\n\n## [v9.9.5] — 2026-10-15\n\n"
            "### Fixed\n\n- Some fix.\n\nRelease-note PRs: #4\n"
        )
        write_changelog(sandbox, cl)
        subprocess.run(["git", "tag", "-a", "v9.9.5", "-m", "v9.9.5"], cwd=sandbox)

        fixture = {
            "4": make_fixture(4, merge_commit_sha=None),
        }
        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump(fixture, f)

        _, stderr, rc = run_script(
            ["--tag", "v9.9.5", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path,
             "--changelog", "CHANGELOG.md"],
            cwd=sandbox,
        )
        assert rc == 1, f"expected exit 1, got {rc}"
        assert "no valid merge commit SHA" in stderr, f"unexpected: {stderr}"
        print("[PASS] test_github_api_null_merge_sha")
    finally:
        subprocess.run(["rm", "-rf", sandbox], check=False)


def test_github_api_merge_commit_not_in_repo():
    """FAIL: merge_commit_sha does not exist in the local repo."""
    sandbox = make_sandbox()
    try:
        commits, _ = git(["log", "--format=%H", "-n", "3"], sandbox)
        commits = commits.splitlines()
        sha = commits[0]

        cl = (
            "# Changelog\n\n## [v9.9.4] — 2026-10-15\n\n"
            "### Fixed\n\n- Some fix.\n\nRelease-note PRs: #5\n"
        )
        write_changelog(sandbox, cl)
        subprocess.run(["git", "tag", "-a", "v9.9.4", "-m", "v9.9.4"], cwd=sandbox)

        # Use a fake SHA that doesn't exist in the repo
        fixture = {
            "5": make_fixture(5, merge_commit_sha="deadbeef" + "0" * 32),
        }
        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump(fixture, f)

        _, stderr, rc = run_script(
            ["--tag", "v9.9.4", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path,
             "--changelog", "CHANGELOG.md"],
            cwd=sandbox,
        )
        assert rc == 1, f"expected exit 1, got {rc}"
        assert "merge commit is unavailable locally" in stderr, f"unexpected: {stderr}"
        print("[PASS] test_github_api_merge_commit_not_in_repo")
    finally:
        subprocess.run(["rm", "-rf", sandbox], check=False)


def test_github_api_not_ancestor():
    """FAIL: merge commit is not an ancestor of the candidate tag commit."""
    sandbox = make_sandbox()
    try:
        commits, _ = git(["log", "--format=%H", "-n", "3"], sandbox)
        commits = commits.splitlines()
        oldest = commits[-1]  # oldest of the last 3
        newest = commits[0]    # newest of the last 3

        cl = (
            "# Changelog\n\n## [v9.9.3] — 2026-10-15\n\n"
            "### Fixed\n\n- Some fix.\n\nRelease-note PRs: #6\n"
        )
        write_changelog(sandbox, cl)
        subprocess.run(["git", "tag", "-a", "v9.9.3", "-m", "v9.9.3"], cwd=sandbox)

        # Use a SHA that exists in the repo but is NOT an ancestor of the tag.
        # The tag is at HEAD (newest). Use the fake SHA from make_fixture
        # (which is "0" * 40) — this fails at cat-file check: "unavailable locally"
        # This tests the local-unavailable path (which also fails the gate).
        fixture = {"6": make_fixture(6, merge_commit_sha="deadbeef" + "a" * 32)}
        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump(fixture, f)

        _, stderr, rc = run_script(
            ["--tag", "v9.9.3", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path,
             "--changelog", "CHANGELOG.md"],
            cwd=sandbox,
        )
        assert rc == 1, f"expected exit 1, got {rc}: {stderr[:200]}"
        # Either "unavailable locally" (fake SHA not in repo) or "not an ancestor" is acceptable
        assert "not an ancestor" in stderr or "unavailable locally" in stderr, f"unexpected: {stderr[:200]}"
        print("[PASS] test_github_api_not_ancestor")
    finally:
        subprocess.run(["rm", "-rf", sandbox], check=False)


def test_pass_valid_merged_pr_ancestor():
    """PASS: PR is merged, merge commit is an ancestor of the candidate tag."""
    sandbox = make_sandbox()
    try:
        commits, _ = git(["log", "--format=%H", "-n", "3"], sandbox)
        commits = commits.splitlines()
        # Use the oldest of the last 3 commits as the merge commit
        # (it IS an ancestor of the newest commit = tag)
        merge_commit = commits[-1]
        tag_commit = commits[0]

        cl = (
            "# Changelog\n\n## [v9.9.2] — 2026-10-15\n\n"
            "### Fixed\n\n- Some fix.\n\nRelease-note PRs: #7\n"
        )
        write_changelog(sandbox, cl)
        subprocess.run(["git", "tag", "-a", "v9.9.2", "-m", "v9.9.2"], cwd=sandbox)

        fixture = {"7": make_fixture(7, merge_commit_sha=merge_commit)}
        fixture_path = os.path.join(sandbox, "fixtures.json")
        with open(fixture_path, "w") as f:
            json.dump(fixture, f)

        stdout, stderr, rc = run_script(
            ["--tag", "v9.9.2", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--pr-fixtures", fixture_path,
             "--changelog", "CHANGELOG.md"],
            cwd=sandbox,
        )
        assert rc == 0, f"expected exit 0, got {rc}: {stderr}"
        assert "PASS" in stdout, f"unexpected: {stdout}"
        print("[PASS] test_pass_valid_merged_pr_ancestor")
    finally:
        subprocess.run(["rm", "-rf", sandbox], check=False)


def test_pass_release_note_prs_none():
    """PASS: section declares 'Release-note PRs: none' (no PRs required)."""
    sandbox = make_sandbox()
    try:
        commits, _ = git(["log", "--format=%H", "-n", "1"], sandbox)
        commits = commits.splitlines()

        cl = (
            "# Changelog\n\n## [v9.9.1] — 2026-10-15\n\n"
            "### Fixed\n\n- No PRs.\n\nRelease-note PRs: none\n"
        )
        write_changelog(sandbox, cl)
        subprocess.run(["git", "tag", "-a", "v9.9.1", "-m", "v9.9.1"], cwd=sandbox)

        stdout, stderr, rc = run_script(
            ["--tag", "v9.9.1", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--changelog", "CHANGELOG.md"],
            cwd=sandbox,
        )
        assert rc == 0, f"expected exit 0, got {rc}: {stderr}"
        assert "0 release-note" in stdout, f"unexpected: {stdout}"
        print("[PASS] test_pass_release_note_prs_none")
    finally:
        subprocess.run(["rm", "-rf", sandbox], check=False)


def test_fail_malformed_declaration():
    """FAIL: section has no or multiple 'Release-note PRs:' declarations."""
    sandbox = make_sandbox()
    try:
        commits, _ = git(["log", "--format=%H", "-n", "1"], sandbox)

        # No declaration at all
        cl = (
            "# Changelog\n\n## [v9.9.0] — 2026-10-15\n\n"
            "### Fixed\n\n- Some fix.\n"
        )
        write_changelog(sandbox, cl)
        subprocess.run(["git", "tag", "-a", "v9.9.0", "-m", "v9.9.0"], cwd=sandbox)

        _, stderr, rc = run_script(
            ["--tag", "v9.9.0", "--repo", sandbox,
             "--repository", "vinhphan812/mcp-java-sdk",
             "--changelog", "CHANGELOG.md"],
            env={"GH_TOKEN": "fake"},
            cwd=sandbox,
        )
        assert rc == 1, f"expected exit 1, got {rc}"
        assert "exactly one" in stderr or "malformed" in stderr, f"unexpected: {stderr}"
        print("[PASS] test_fail_malformed_declaration")
    finally:
        subprocess.run(["rm", "-rf", sandbox], check=False)


def test_fail_repository_mismatch():
    """FAIL: --repository argument does not match origin remote."""
    sandbox = make_sandbox()
    try:
        commits, _ = git(["log", "--format=%H", "-n", "1"], sandbox)

        cl = (
            "# Changelog\n\n## [v9.8.9] — 2026-10-15\n\n"
            "### Fixed\n\n- Some fix.\n\nRelease-note PRs: none\n"
        )
        write_changelog(sandbox, cl)
        subprocess.run(["git", "tag", "-a", "v9.8.9", "-m", "v9.8.9"], cwd=sandbox)

        _, stderr, rc = run_script(
            ["--tag", "v9.8.9", "--repo", sandbox,
             "--repository", "wrong-owner/mcp-java-sdk",
             "--changelog", "CHANGELOG.md"],
            env={"GH_TOKEN": ""},
            cwd=sandbox,
        )
        assert rc == 1, f"expected exit 1, got {rc}"
        assert "does not match origin" in stderr, f"unexpected: {stderr}"
        print("[PASS] test_fail_repository_mismatch")
    finally:
        subprocess.run(["rm", "-rf", sandbox], check=False)


def main():
    parser = argparse.ArgumentParser(description="Hermetic PR ancestry gate tests.")
    parser.add_argument("--repo", default=REPO)
    args = parser.parse_args()

    if os.path.basename(os.path.dirname(SCRIPT)) != "release":
        print("SKIP: run from repository root as python scripts/release/test_release_provenance.py")
        sys.exit(0)

    tests = [
        test_github_api_wrong_repository,
        test_github_api_not_merged,
        test_github_api_wrong_base_ref,
        test_github_api_null_merge_sha,
        test_github_api_merge_commit_not_in_repo,
        test_github_api_not_ancestor,
        test_github_api_stub_missing_fixture,
        test_pass_valid_merged_pr_ancestor,
        test_pass_release_note_prs_none,
        test_fail_malformed_declaration,
        test_fail_repository_mismatch,
    ]

    results = []
    for t in tests:
        try:
            t()
            results.append((t.__name__, True, ""))
        except AssertionError as e:
            results.append((t.__name__, False, str(e)))
        except Exception as e:
            results.append((t.__name__, False, f"EXCEPTION: {e}"))

    print(f"\n=== Results: {sum(r[1] for r in results)}/{len(results)} passed ===")
    for name, ok, detail in results:
        status = "PASS" if ok else f"FAIL: {detail}"
        print(f"  [{status}]  {name}")
    all_ok = all(r[1] for r in results)
    print(f"\nOverall: {'ALL PASS' if all_ok else 'SOME FAILURES'}")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
