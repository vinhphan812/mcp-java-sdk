# Release Guardrails

This document describes the GitHub branch/tag protection and release provenance guardrails enforced for the mcp-java-sdk repository.

---

## 1. Branch Protection (master)

The `master` branch is protected via GitHub branch protection API:

| Setting | Value | Source |
|---------|-------|--------|
| Required Status Checks | Validate Documentation, build (11), build (17), SBOM and dependency vulnerability gate, MCP 2026-07-28 conformance, grype | API |
| Strict required_status_checks | true (must pass before merge) | API |
| Require linear history | true (no merge commits) | API |
| Allow force pushes | false | API |
| Allow branch deletions | false | API |
| Enforce admins | true | API |

**Verification command:**
```bash
gh api repos/vinhphan812/mcp-java-sdk/branches/master/protection
```

---

## 2. Tag Protection (Rulesets)

A repository ruleset protects release tags from modification or deletion:

| Property | Value |
|----------|-------|
| Ruleset Name | Tag Protection |
| Target | tag |
| Enforcement | active |
| Conditions | Include: `refs/tags/*`, Exclude: `refs/tags/v*.*.*-*` |
| Rules | `deletion` (block tag deletion), `update` (block force-push to tags) |

**Note:** Pre-release tags matching `v*.*.*-*` (e.g., `v1.0.0-beta1`) are excluded from protection to allow iterative tagging during development.

**Verification command:**
```bash
gh api repos/vinhphan812/mcp-java-sdk/rulesets
# or specific ruleset:
gh api repos/vinhphan812/mcp-java-sdk/rulesets/25088082
```

**Ruleset UI:** https://github.com/vinhphan812/mcp-java-sdk/rules/25088082

---

## 3. Release Provenance

The release workflow (`.github/workflows/release.yml`) enforces release provenance:

### 3.1 SHA Verification

Before building, the workflow verifies that the tag's commit SHA matches the current HEAD:

```yaml
- name: Verify commit SHA matches tag SHA
  run: |
    TAG_SHA=$(git rev-parse "${GITHUB_REF}")
    COMMIT_SHA=$(git rev-parse HEAD)
    if [ "$TAG_SHA" != "$COMMIT_SHA" ]; then
      echo "ERROR: Tag SHA does not match commit SHA"
      exit 1
    fi
```

If SHA mismatch is detected (e.g., from a force-push), the release fails immediately.

### 3.2 Annotated Tag Creation

The workflow creates an annotated tag with provenance metadata:

```yaml
- name: Create annotated tag with provenance metadata
  run: |
    TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    AUTHOR=$(git log -1 --format='%an <%ae>')
    git tag -a "v${TAG_NAME}" -m "Release v${TAG_NAME}
    Timestamp: ${TIMESTAMP}
    Author: ${AUTHOR}
    Commit: ${GITHUB_SHA}
    "
```

The annotated tag includes:
- **Version** — from the tag name (e.g., `v1.1.0`)
- **Timestamp** — UTC ISO 8601 format
- **Author** — name and email of the tagger
- **Commit SHA** — the exact commit being released

### 3.3 SBOM Generation and Publication

The workflow generates a CycloneDX SBOM and publishes it as a release artifact:

```yaml
- name: Build release JAR, test, and generate CycloneDX SBOM
  run: ./gradlew ... cyclonedxBom
- name: Create GitHub Release
  uses: softprops/action-gh-release@v2
  with:
    files: |
      build/libs/mcp-java-sdk-*.jar
      build/reports/bom.json
```

---

## 4. Required Status Checks

All status checks that must pass before merging to master:

| Check | Purpose |
|-------|---------|
| Validate Documentation | Markdown tables and links hygiene |
| build (11) | Build with Java 11 |
| build (17) | Build with Java 17 |
| SBOM and dependency vulnerability gate | CycloneDX SBOM + Grype vulnerability scan |
| MCP 2026-07-28 conformance | MCP spec conformance suite |
| grype | Grype vulnerability scan (HIGH/CRITICAL blocking) |

---

## 5. Verification Commands

### Check branch protection:
```bash
gh api repos/vinhphan812/mcp-java-sdk/branches/master/protection --jq '.required_status_checks.contexts'
```

### Check tag ruleset:
```bash
gh api repos/vinhphan812/mcp-java-sdk/rulesets/25088082 --jq '.rules'
```

### List release tags:
```bash
gh api repos/vinhphan812/mcp-java-sdk/tags --jq '.[].name'
```

### View latest release:
```bash
gh release view latest
```

---

## 6. Exceptions and Exclusions

- **No mandatory reviewer requirement** — vinhphan812 is the sole author/reviewer. Review-by-comment serves as architect acceptance.
- **Pre-release tags excluded** — tags matching `v*.*.*-*` (e.g., `v1.0.0-beta1`) are not protected to allow iterative development tagging.

---

## 7. Enforcement Summary

| Threat | Guardrail |
|--------|-----------|
| Merge without CI passing | Required status checks (enforced) |
| Merge with merge commits | Linear history requirement (enforced) |
| Force-push to master | `allow_force_pushes: false` (enforced) |
| Delete master branch | `allow_deletions: false` (enforced) |
| Force-push release tag | Tag ruleset: `update` rule (enforced) |
| Delete release tag | Tag ruleset: `deletion` rule (enforced) |
| Release from wrong commit | SHA verification step (enforced in CI) |
| No release provenance metadata | Annotated tag with version, timestamp, author, SHA (enforced in CI) |
| Unvetted dependencies | SBOM + Grype vulnerability gate (enforced in CI) |

---

_Last updated: 2026-10-14_
