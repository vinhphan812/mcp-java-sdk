# Release Process

This document describes the end-to-end release workflow for the MCP Java SDK.
It covers pre-tag preparation, tagging, CI gates, and post-tag immutability rules.

> **Immutability rule**: Release notes (CHANGELOG.md, RELEASE-NOTES.md) are frozen
> once a tag is pushed. Do not edit them after publishing. Fixes for a released
> version go into the next version's unreleased section.

---

## Overview

| Phase | What | Where | Who |
|-------|------|-------|-----|
| Pre-tag | Update CHANGELOG, README version | Local | Author / Release manager |
| Pre-tag | Run CI gate checks | GitHub Actions | CI |
| Tag | Create signed tag at release commit | Local | Release manager |
| Post-tag | CI validates provenance, publishes artifacts | GitHub Actions | CI |
| Post-tag | GitHub Releases UI populated from tag | GitHub | CI / Release manager |

---

## Phase 1 — Pre-tag preparation

### 1a. Determine the release commit

Identify the commit that will carry the release. Typically the `master` branch HEAD
at the time of release.

```bash
git checkout master
git pull origin master
git log --oneline -5
```

### 1b. Update CHANGELOG.md

Add an `[Unreleased]` section to record changes since the last release.
When cutting a new release, move the unreleased content into a versioned section:

```markdown
## [Unreleased]
### Added
### Changed
### Fixed

---

## [v1.2.0] — YYYY-MM-DD
```

The version heading format must match: `## [vX.Y.Z] — YYYY-MM-DD`

Add a note recording the tag commit SHA (obtain after tagging):

```markdown
## [v1.2.0] — 2026-10-14

> Tag: `13ac3d6` (see `git rev-parse v1.2.0`)
```

### 1c. Update README.md version

The README must reference the version being released in two places:

1. **Gradle dependency snippet** (search for `io.github.vinhphan812.mcp:mcp-java-sdk:`):
   ```groovy
   dependencies {
       implementation("io.github.vinhphan812.mcp:mcp-java-sdk:1.2.0")
   }
   ```

2. **JAR filenames in the build output table** (search for `mcp-java-sdk-`):
   ```markdown
   | `build/libs/mcp-java-sdk-1.2.0.jar`          | Main artifact      |
   ```

### 1d. Run the hygiene checker

```bash
python scripts/docs/check_stale_classes.py
```

The positive assertion checks that README mentions the latest tag version
(derived from `git describe --tags --abbrev=0`). If this check fails,
update README before proceeding.

```bash
python scripts/docs/check_stale_classes.py --test
```

Runs unit fixtures for the version checker without touching the working tree.

### 1e. Run CI locally (optional)

```bash
./gradlew clean test
```

Ensure all tests pass before tagging.

---

## Phase 2 — Tagging

### 2a. Create the tag

Tag at the **release commit** (do not tag from a dirty tree).

```bash
git tag -a v1.2.0 -m "Release v1.2.0

See CHANGELOG.md for details.
Tag SHA: $(git rev-parse HEAD)
"
```

### 2b. Push the tag

```bash
git push origin v1.2.0
```

Pushing the tag triggers the GitHub Actions release workflow.

> **Never** `git tag -f` after pushing. If you made a mistake, create a new tag
> with a patch version bump instead.

---

## Phase 3 — Post-tag CI gates

GitHub Actions runs the following gates in sequence:

### Gate 1: `doc_hygiene` (`.github/workflows/doc-hygiene.yml`)

```bash
python scripts/docs/check_stale_classes.py
```

Fails if:
- Active documentation references deprecated class names without `[HISTORICAL]` markers.
- README version does not match the latest tag.

Passes with warnings for:
- Old version strings (1.0.1, 1.1.1) in historical contexts.
- Hard-coded test counts.

### Gate 2: `validate_docs` (`.github/workflows/validate-docs.yml`)

```bash
python scripts/docs/validate_docs.py
```

Checks:
- No broken local links in markdown files.
- No malformed table rows (`|| cell |`).

### Gate 3: `release_provenance` (post-tag, triggered by tag push)

```bash
python scripts/release/release_provenance.py --tag v1.2.0
```

Validates:
1. **CHANGELOG SHA check** — `CHANGELOG.md` contains an entry for the tag version
   with the commit SHA visible in the section.
2. **PR reachability check** — Every `(#N)` PR reference in the tag's CHANGELOG
   section corresponds to a commit that is an ancestor of the tag commit.
   PRs not reachable from the tag commit cause a hard failure.
3. **README version check** — Gradle snippet and JAR filenames in README
   match the tag version.
4. **Gradle dynamic version** — `build.gradle` uses `GITHUB_REF_NAME` for dynamic
   version derivation (no hard-coded version that would mismatch the tag).

If any gate fails, the release workflow stops and a maintainer must intervene.

---

## Phase 4 — Release artifacts

On successful CI, the following artifacts are published:

| Artifact | Destination |
|----------|-------------|
| `mcp-java-sdk-X.Y.Z.jar` | GitHub Packages Maven registry |
| `mcp-java-sdk-X.Y.Z-sources.jar` | GitHub Packages Maven registry |
| `mcp-java-sdk-X.Y.Z-javadoc.jar` | GitHub Packages Maven registry |
| `mcp-java-sdk-X.Y.Z-cyclonedx.json` | GitHub Packages Maven registry |
| Release notes | GitHub Releases (populated from tag annotation) |

The GitHub Releases description should be populated from `CHANGELOG.md`
entries between the previous tag and the current tag. Use:

```bash
git tag -l --format='%(contents:subject)%0a%0a%(contents:body)' vX.Y.Z
```

---

## Troubleshooting

### "README version does not match latest tag v1.2.0"

The positive assertion failed. Run:

```bash
git describe --tags --abbrev=0   # confirm current latest tag
grep -n "mcp-java-sdk:" README.md
```

Update README to the version from `git describe --tags --abbrev=0`.

### "PR #N is not reachable from tag commit"

The CHANGELOG entry for this version references a PR whose commits are not
ancestors of the tag. This can happen when:
- A PR was merged into master after the release commit was tagged.
- The PR was reverted or not yet merged.

**Fix**: Remove the PR reference from the CHANGELOG entry or rebase the release
on a commit that includes the PR.

### "Tag SHA not found in CHANGELOG"

Add the SHA to the version heading section:

```markdown
> Tag: `13ac3d6` (see `git rev-parse v1.2.0`)
```

### Gradle build fails with "sdkVersion not found"

The `sdkVersion` property requires either:
- `-PsdkVersion=X.Y.Z` on the command line, or
- `GITHUB_REF_NAME` environment variable (set by GitHub Actions).

For local builds without a tag, use:
```bash
./gradlew -PsdkVersion=1.2.0 build
```

---

## Scripts reference

| Script | Purpose |
|--------|---------|
| `scripts/docs/check_stale_classes.py` | Stale-class + README version hygiene |
| `scripts/docs/check_stale_classes.py --test` | Run version-checker fixtures |
| `scripts/docs/validate_docs.py` | Markdown link + table validation |
| `scripts/release/release_provenance.py --tag vX.Y.Z` | Release provenance CI gate |
