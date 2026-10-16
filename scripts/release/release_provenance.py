#!/usr/bin/env python3
"""Fail-closed release provenance checker.

The candidate release-note section in ``CHANGELOG.md`` must contain exactly one
reviewable declaration:

    Release-note PRs: none

or:

    Release-note PRs: #12, #34

For every declared PR, this checker queries the GitHub API for the repository
that is publishing the candidate.  The PR must be merged into ``master``, have
a non-null merge commit SHA, and that merge commit must be an ancestor of the
peeled candidate tag commit.  Every parse, API, repository, ref, object, or
ancestry failure blocks publication.

Fixture input exists only for hermetic unit tests; production workflow runs use
GitHub's API with the ephemeral workflow token and never print the token.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

SHA_RE = re.compile(r"^[0-9a-f]{40}$")
REPOSITORY_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
DECLARATION_RE = re.compile(r"^Release-note PRs:\s*(.+?)\s*$", re.MULTILINE)
PR_LIST_RE = re.compile(r"^#[1-9][0-9]*(?:, #[1-9][0-9]*)*$")


def run_git(args, repo):
    result = subprocess.run(
        ["git"] + args, cwd=repo, text=True, capture_output=True, check=False
    )
    if result.returncode:
        raise ValueError("git " + " ".join(args) + " failed")
    return result.stdout.strip()



def merge_base(commit_a, commit_b, repo):
    """Return the merge-base of two commits."""
    return run_git(["merge-base", commit_a, commit_b], repo)


def is_ancestor(ancestor, descendant, repo):
    """Return True if `ancestor` is an ancestor of `descendant`."""
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", ancestor, descendant],
        cwd=repo, text=True, capture_output=True, check=False
    )
    return result.returncode == 0


def canonical_origin(repo):
    raw = run_git(["remote", "get-url", "origin"], repo)
    if raw.startswith("git@github.com:"):
        raw = raw[len("git@github.com:") :]
    elif raw.startswith("https://github.com/"):
        raw = raw[len("https://github.com/") :]
    else:
        raise ValueError("origin is not a GitHub repository URL")
    return raw.removesuffix(".git")


def peeled_tag_commit(tag, repo):
    if not tag or not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?", tag):
        raise ValueError("tag is malformed")
    sha = run_git(["rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"], repo)
    if not SHA_RE.fullmatch(sha):
        raise ValueError("tag does not peel to a full commit SHA")
    return sha


def release_section(changelog, tag):
    try:
        text = open(changelog, encoding="utf-8").read()
    except OSError as exc:
        raise ValueError(f"cannot read release notes: {exc}") from exc
    version = re.escape(tag.removeprefix("v"))
    match = re.search(
        rf"(?m)^##\s+\[v?{version}\][^\n]*(?:\n|$)(.*?)(?=^##\s+\[|\Z)",
        text,
        re.DOTALL,
    )
    if not match:
        raise ValueError(f"release notes have no section for {tag}")
    return match.group(1)


def unreleased_prs(changelog):
    """Read the ## [Unreleased] section for Release-note PRs when no version section exists."""
    try:
        text = open(changelog, encoding="utf-8").read()
    except OSError as exc:
        raise ValueError(f"cannot read release notes: {exc}") from exc
    match = re.search(
        r"(?m)^##\s+\[Unreleased\](.*?)(?=^##\s+\[|\Z)",
        text,
        re.DOTALL,
    )
    if not match:
        return []
    return declared_prs(match.group(1))


def declared_prs(section):
    declarations = DECLARATION_RE.findall(section)
    if len(declarations) != 1:
        raise ValueError("release notes must contain exactly one 'Release-note PRs:' declaration")
    value = declarations[0]
    if value == "none":
        return []
    if not PR_LIST_RE.fullmatch(value):
        raise ValueError("release-note PR declaration is malformed; use 'none' or '#12, #34'")
    prs = [int(part[1:]) for part in value.split(", ")]
    if len(set(prs)) != len(prs):
        raise ValueError("release-note PR declaration contains duplicate PR numbers")
    return prs


def load_fixtures(path):
    try:
        data = json.load(open(path, encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot parse PR fixture file: {exc}") from exc
    if not isinstance(data, dict) or not all(isinstance(key, str) for key in data):
        raise ValueError("PR fixture file must be an object keyed by PR number")
    return data


def github_pr(repository, number, api_url, token):
    request = urllib.request.Request(
        f"{api_url.rstrip('/')}/repos/{repository}/pulls/{number}",
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = json.load(response)
    except (urllib.error.URLError, urllib.error.HTTPError, json.JSONDecodeError) as exc:
        raise ValueError(f"GitHub API lookup for PR #{number} failed") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"GitHub API returned malformed data for PR #{number}")
    return payload


def field(payload, path, number):
    value = payload
    for key in path:
        if not isinstance(value, dict) or key not in value:
            raise ValueError(f"PR #{number} API payload is malformed ({'.'.join(path)} missing)")
        value = value[key]
    return value


def verify_pr(payload, repository, candidate_sha, repo, number):
    api_repo = field(payload, ("base", "repo", "full_name"), number)
    api_ref = field(payload, ("base", "ref"), number)
    api_url = field(payload, ("url",), number)
    expected_url = f"https://api.github.com/repos/{repository}/pulls/{number}"
    if api_repo != repository or api_url != expected_url:
        raise ValueError(f"PR #{number} belongs to the wrong repository")
    if api_ref != "master":
        raise ValueError(f"PR #{number} targets '{api_ref}', not protected master")
    if payload.get("state") != "closed" or payload.get("merged") is not True or not payload.get("merged_at"):
        raise ValueError(f"PR #{number} is not merged")
    merge_sha = payload.get("merge_commit_sha")
    if not isinstance(merge_sha, str) or not SHA_RE.fullmatch(merge_sha):
        raise ValueError(f"PR #{number} has no valid merge commit SHA")
    try:
        run_git(["cat-file", "-e", f"{merge_sha}^{{commit}}"], repo)
    except ValueError as exc:
        raise ValueError(f"PR #{number} merge commit is unavailable locally") from exc
    if not is_ancestor(merge_sha, candidate_sha, repo):
        raise ValueError(f"PR #{number} merge commit is not an ancestor of candidate tag")


def main():
    parser = argparse.ArgumentParser(description="Fail-closed release-note PR provenance gate.")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repo", default=os.environ.get("TEST_REPO", "."))
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--changelog", default="CHANGELOG.md")
    parser.add_argument("--github-api-url", default=os.environ.get("GITHUB_API_URL", "https://api.github.com"))
    parser.add_argument("--pr-fixtures", help="Hermetic test fixture JSON; never used by the release workflow")
    args = parser.parse_args()

    try:
        if not args.repository or not REPOSITORY_RE.fullmatch(args.repository):
            raise ValueError("repository input is malformed")
        if canonical_origin(args.repo) != args.repository:
            raise ValueError("repository input does not match origin")
        candidate_sha = peeled_tag_commit(args.tag, args.repo)
        try:
            section = release_section(os.path.join(args.repo, args.changelog), args.tag)
            numbers = declared_prs(section)
        except ValueError as exc:
            if "release notes have no section for" in str(exc):
                numbers = unreleased_prs(os.path.join(args.repo, args.changelog))
            else:
                raise
        fixtures = load_fixtures(args.pr_fixtures) if args.pr_fixtures else None
        token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
        if fixtures is None and numbers and not token:
            raise ValueError("GitHub token is unavailable")
        for number in numbers:
            if fixtures is not None:
                if str(number) not in fixtures:
                    raise ValueError(f"PR #{number} fixture is missing")
                payload = fixtures[str(number)]
                if not isinstance(payload, dict):
                    raise ValueError(f"PR #{number} fixture is malformed")
            else:
                payload = github_pr(args.repository, number, args.github_api_url, token)
            verify_pr(payload, args.repository, candidate_sha, args.repo, number)
            print(f"[OK] Release-note PR #{number} is merged into master and precedes candidate")
        print(f"PASS: {len(numbers)} release-note PR(s) verified for {args.tag} at {candidate_sha}")
    except ValueError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
