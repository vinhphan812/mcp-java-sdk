#!/usr/bin/env python3
"""
Release provenance checker.

Validates that:
  1. The tag commit SHA is recorded in the release notes / CHANGELOG.
  2. Release notes reference only PRs that are reachable from the tag commit.

This script is intended to be run as a post-tag CI gate:
  python scripts/release/release_provenance.py --tag v1.2.3

It can also be run against the local repository:
  python scripts/release/release_provenance.py [--repo <path>]

Exit codes:
  0  = all provenance checks pass
  1  = one or more checks failed
  2  = usage / setup error
"""
import argparse
import os
import re
import subprocess
import sys


# ----------------------------------------------------------------------
# Git helpers
# ----------------------------------------------------------------------

def git(args, repo=".", capture=True):
    """Run a git command and return stdout (stripped). Raises on non-zero exit."""
    opts = {} if capture else {}
    try:
        result = subprocess.run(
            ["git"] + args,
            cwd=repo,
            capture_output=capture,
            text=True,
            check=True,
        )
        return result.stdout.strip() if capture else ""
    except subprocess.CalledProcessError as e:
        print(f"ERROR: git {' '.join(args)} failed: {e.stderr or e}", file=sys.stderr)
        sys.exit(2)


def tag_sha(tag):
    """Return the commit SHA for a given tag."""
    return git(["rev-list", "--max-count=1", tag])


def tag_date(tag):
    """Return the ISO-8601 date of a tag (YYYY-MM-DD)."""
    return git(["log", "-1", "--format=%ci", tag]).split()[0]


def merge_base(commit_a, commit_b):
    """Return the merge-base of two commits."""
    return git(["merge-base", commit_a, commit_b])


def is_ancestor(ancestor, descendant):
    """Return True if `ancestor` is an ancestor of `descendant`."""
    base = merge_base(ancestor, descendant)
    return base == git(["rev-parse", ancestor])


def commits_between(from_exclusive, to_inclusive):
    """Return list of commit SHAs from `from` (exclusive) to `to` (inclusive)."""
    if is_ancestor(from_exclusive, to_inclusive):
        return git(["log", "--format=%H", f"{from_exclusive}..{to_inclusive}"]).splitlines()
    # `from_exclusive` is not an ancestor — return all commits up to `to`
    return git(["log", "--format=%H", "-n", "500", to_inclusive]).splitlines()


def pr_from_commit(commit_sha):
    """Try to extract a PR number from a commit message (e.g. 'fix(#44): ...')."""
    msg = git(["log", "-1", "--format=%B", commit_sha])
    m = re.search(r'\(#(\d+)\)', msg)
    return int(m.group(1)) if m else None


def prs_in_range(sha_list):
    """Return sorted unique list of PR numbers mentioned in commits."""
    prs = set()
    for sha in sha_list:
        pr = pr_from_commit(sha)
        if pr:
            prs.add(pr)
    return sorted(prs)


def tag_message(tag):
    """Return the annotated tag message, or '' if lightweight."""
    try:
        return git(["tag", "-l", "--format=%(contents:subject)%0a%0a%(contents:body)", tag])
    except Exception:
        return ""


# ----------------------------------------------------------------------
# Provenance checks
# ----------------------------------------------------------------------

def check_tag_recorded_in_changelog(tag, tag_sha_val, changelog_path="CHANGELOG.md"):
    """
    Verify that CHANGELOG.md contains an entry for `tag` with the tag SHA.

    Looks for a heading like:
        ## [v1.2.3] — YYYY-MM-DD

    And checks that the SHA is mentioned somewhere in that section.
    """
    if not os.path.exists(changelog_path):
        return [], [f"CHANGELOG.md not found at {changelog_path}"]

    with open(changelog_path, "r", encoding="utf-8") as f:
        content = f.read()

    errors = []
    warnings = []

    # Normalise tag (strip leading 'v')
    version = tag.lstrip("v")

    # Find changelog section for this tag
    section_pattern = rf"(?:^|\n)##\s+\[v?{re.escape(version)}\].*?(?=\n##\s|\Z)"
    section_match = re.search(section_pattern, content, re.IGNORECASE | re.DOTALL)

    if not section_match:
        errors.append(
            f"No CHANGELOG entry found for {tag}. "
            f"Add a section: ## [{tag}] — YYYY-MM-DD"
        )
        return errors, warnings

    section_text = section_match.group(0)

    # Check for SHA presence
    if tag_sha_val[:8] not in section_text:
        warnings.append(
            f"Tag SHA {tag_sha_val[:8]} not found in CHANGELOG entry for {tag}. "
            f"Consider recording: '{tag_sha_val}' (commit SHA)"
        )
    else:
        print(f"  [OK] Tag SHA {tag_sha_val[:8]} found in CHANGELOG")

    return errors, warnings


def check_prs_reachable_from_tag(tag_sha_val, changelog_path="CHANGELOG.md",
                                 min_release_prs=None):
    """
    Verify that every PR referenced in CHANGELOG under the `tag` section
    is reachable from the tag commit.

    PRs are extracted from commit messages via the '(#N)' convention.
    """
    if not os.path.exists(changelog_path):
        return [], []

    with open(changelog_path, "r", encoding="utf-8") as f:
        content = f.read()

    errors = []
    warnings = []

    # Find section for the tag
    # We need to know which tag version we're looking at
    # Use the sha to find the right section: look for all ## [vx.y.z] headings,
    # then find the one that contains commits reachable from this tag SHA.

    # Get all section headings
    headings = list(re.finditer(r"(?:^|\n)##\s+\[v?(\d+\.\d+\.\d+)\]", content))

    if not headings:
        return [], [f"No version headings found in {changelog_path}"]

    for i, heading in enumerate(headings):
        version = heading.group(1)
        start = heading.start()
        end = headings[i + 1].start() if i + 1 < len(headings) else len(content)
        section = content[start:end]

        # Check for PR references in this section
        pr_refs = re.findall(r"\(#(\d+)\)", section)
        if not pr_refs:
            continue

        # For each PR number, check if there's a commit in the repo that:
        # 1. Mentions that PR, AND
        # 2. Is an ancestor of the tag SHA (or IS the tag SHA itself)
        for pr_str in pr_refs:
            pr_num = int(pr_str)
            # Find commits mentioning this PR
            search_results = git(
                ["log", "--all", "--oneline", "--grep", f"(#{pr_num})", "-n", "5"],
                capture=True
            )
            if not search_results:
                warnings.append(
                    f"PR #{pr_num} referenced in CHANGELOG for {version} "
                    f"but no matching commits found in repository"
                )
                continue

            # Check each candidate commit
            reachable = False
            for line in search_results.splitlines():
                commit_sha = line.split()[0]
                # Is this commit an ancestor of the tag? (or the tag itself)
                if is_ancestor(commit_sha, tag_sha_val) or commit_sha == tag_sha_val:
                    reachable = True
                    break

            if not reachable:
                errors.append(
                    f"PR #{pr_num} referenced in CHANGELOG entry for {version} "
                    f"is not reachable from tag commit {tag_sha_val[:8]}. "
                    f"Release notes must not reference PRs outside the tag ancestry."
                )

    return errors, warnings


def check_readme_version(tag):
    """Verify README mentions the correct version matching the tag."""
    errors = []
    warnings = []

    readme_path = "README.md"
    if not os.path.exists(readme_path):
        return errors, warnings

    with open(readme_path, "r", encoding="utf-8") as f:
        content = f.read()

    version = tag.lstrip("v")

    patterns = [
        (r"io\.github\.vinhphan812\.mcp:mcp-java-sdk:" + re.escape(version),
         "Gradle dependency"),
        (r"mcp-java-sdk-" + re.escape(version) + r"\.jar",
         "JAR filename"),
    ]

    for pattern, location in patterns:
        if re.search(pattern, content):
            print(f"  [OK] README {location} matches v{version}")
        else:
            errors.append(
                f"README {location} does not match v{version}; "
                f"update before tagging"
            )

    return errors, warnings


def check_gradle_version(tag, repo="."):
    """Verify sdkVersion in build.gradle derives from the tag."""
    errors = []
    warnings = []

    gradle_path = os.path.join(repo, "build.gradle")
    if not os.path.exists(gradle_path):
        warnings.append(f"build.gradle not found at {gradle_path}")
        return errors, warnings

    with open(gradle_path, "r", encoding="utf-8") as f:
        content = f.read()

    version = tag.lstrip("v")

    # sdkVersion is derived; check it uses the GITHUB_REF_NAME pattern
    if "GITHUB_REF_NAME" in content or "releaseVersion" in content:
        print(f"  [OK] build.gradle uses dynamic version from GITHUB_REF_NAME")
    else:
        # Check for hard-coded version
        m = re.search(r'version\s*=\s*["\']([^"\']+)["\']', content)
        if m and m.group(1) != version:
            errors.append(
                f"build.gradle has hard-coded version '{m.group(1)}' "
                f"which does not match tag v{version}"
            )

    return errors, warnings


# ----------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Validate release provenance (tag SHA, CHANGELOG, PR reachability)."
    )
    parser.add_argument(
        "--tag", "-t", default=None,
        help="Tag to validate (default: latest annotated tag from git describe --tags)"
    )
    parser.add_argument(
        "--repo", "-r", default=".",
        help="Path to repository root (default: current directory)"
    )
    parser.add_argument(
        "--skip-changelog", action="store_true",
        help="Skip CHANGELOG SHA check"
    )
    parser.add_argument(
        "--skip-pr-reachability", action="store_true",
        help="Skip PR reachability check"
    )
    parser.add_argument(
        "--skip-readme", action="store_true",
        help="Skip README version check"
    )
    parser.add_argument(
        "--skip-gradle", action="store_true",
        help="Skip Gradle version check"
    )
    args = parser.parse_args()

    # Resolve tag
    tag = args.tag
    if not tag:
        # Use latest tag from git
        try:
            tag = git(["describe", "--tags", "--abbrev=0"], repo=args.repo)
        except SystemExit:
            print("ERROR: Could not determine latest tag. Pass --tag explicitly.", file=sys.stderr)
            sys.exit(2)

    print(f"\n=== Release Provenance Check ===")
    print(f"Tag: {tag}")

    # Get tag metadata
    try:
        tag_sha_val = tag_sha(tag)
        tag_date_val = tag_date(tag)
    except SystemExit:
        print(f"ERROR: Tag '{tag}' not found in repository.", file=sys.stderr)
        sys.exit(2)

    print(f"Tag SHA: {tag_sha_val}")
    print(f"Tag date: {tag_date_val}")
    print()

    all_errors = []
    all_warnings = []

    # Check 1: CHANGELOG records tag SHA
    if not args.skip_changelog:
        print("--- CHANGELOG SHA check ---")
        errs, warns = check_tag_recorded_in_changelog(tag, tag_sha_val)
        all_errors.extend(errs)
        all_warnings.extend(warns)
        print()

    # Check 2: PRs in CHANGELOG are reachable from tag
    if not args.skip_pr_reachability:
        print("--- PR reachability check ---")
        errs, warns = check_prs_reachable_from_tag(tag_sha_val)
        all_errors.extend(errs)
        all_warnings.extend(warns)
        print()

    # Check 3: README version matches tag
    if not args.skip_readme:
        print("--- README version check ---")
        errs, warns = check_readme_version(tag)
        all_errors.extend(errs)
        all_warnings.extend(warns)
        print()

    # Check 4: Gradle version (dynamic)
    if not args.skip_gradle:
        print("--- Gradle version check ---")
        errs, warns = check_gradle_version(tag, args.repo)
        all_errors.extend(errs)
        all_warnings.extend(warns)
        print()

    # Report
    if all_warnings:
        print("\nWarnings (non-blocking):")
        for w in all_warnings:
            print(f"  {w}")

    if all_errors:
        print("\nFAIL: Provenance checks failed:")
        for e in all_errors:
            print(f"  {e}")
        print()
        print("Immutability rule: do not edit CHANGELOG after publishing a release.")
        sys.exit(1)
    else:
        print("\nPASS: All provenance checks passed!")
        sys.exit(0)


if __name__ == "__main__":
    main()
