# Release Guardrails

This document describes the release provenance guardrails enforced in `.github/workflows/release.yml`.
It does not change GitHub rulesets and does not authorize retagging, release editing, or package overwrites.

---

## Release trigger and tag policy

The workflow runs for tags matching `v*`, but only an exact `vMAJOR.MINOR.PATCH` tag is eligible
to publish a production package and GitHub Release.

- An exact production tag must be an **annotated tag**. Lightweight tags are validation-only.
- Tags with a suffix (e.g. `v1.2.3-skills-fix`) are validation-only. They do not enter the
  publication job and cannot publish a production Maven coordinate.
- Tag protection is governed separately by ruleset `25088082`; this workflow neither reads nor
  changes that ruleset.

### Validation tag ancestry rule (H4)

The ancestry check is a **precondition for ALL tags** and runs **before** SemVer classification.
A suffixed tag that is not a master ancestor fails at the ancestry step and never reaches the
production-vs-validation classification gate. The workflow structure is:

```
1. Origin identity check      ← all tags
2. Tag object existence       ← all tags
3. Tag peel == HEAD == GITHUB_SHA  ← all tags
4. Fetch origin/master        ← all tags
5. Tag is ancestor of master  ← all tags  ← H4: runs BEFORE classification
6. SemVer classification      ← all tags  (production / validation-only)
```

A suffixed tag that fails step 5 is rejected before step 6 is evaluated.

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

## 3. Provenance validation

Before any production publication, `validate-release-provenance` fails closed unless all of the following hold:

1. `GITHUB_REPOSITORY` is exactly `vinhphan812/mcp-java-sdk` and `origin` normalises to its canonical HTTPS URL.
   Normalisation strips trailing `.git` and converts `git@github.com:owner/repo` SSH form to HTTPS (H1 fix).
2. The triggering ref exists locally and can be inspected.
3. The workflow records the tag object ID and its object type separately.
4. `refs/tags/<tag>^{commit}` peels to a commit that equals both checkout `HEAD` and `GITHUB_SHA`.
5. A deterministic full fetch of `origin/master` succeeds.
6. The peeled tag commit is an ancestor of the fetched protected-master commit. Both SHA values are printed
   before the ancestry check. This is a precondition for ALL tags and runs before SemVer classification (see H4 above).

The job contains a hermetic regression simulation proving annotated and lightweight tags peel to the intended
commit, that a master commit passes ancestry, and that a feature-only commit fails it. These are workflow-local
tests; they do not create repository tags or releases.

## 4. Version reuse policy

For an eligible production tag, `publish-production-release` refuses to continue if:

- a GitHub Release already exists for the tag; or
- Maven metadata already contains the intended package version (checked via `xml.etree.ElementTree`, not string/regex — H2 fix).

An existing release or version is terminal for that version. Recovery requires a new patch version. Operators
must never retarget a tag, edit a release into a replacement, or re-upload package assets for a reused version.

## 5. Publication and post-release verification

After Gradle build, test run, Javadoc generation, SBOM generation, and dependency scan, the job publishes
the Maven coordinate and creates a GitHub Release with `--verify-tag`. It packages all of: main JAR,
sources JAR, Javadoc JAR, CycloneDX `bom.json`, `asset-sha256sums.txt`, and `provenance.json`.

The final verification step fails closed unless the release is public (not draft or prerelease), names the
expected tag, includes every required asset, and the release tag still peels to the expected commit. It
also requires Maven metadata to contain the published version (checked via Python XML parser — H2 fix) and
uses a temporary clean Gradle consumer project to resolve `io.github.vinhphan812.mcp:mcp-java-sdk:<version>`.

## 6. PR dry-run coverage (H3)

A separate `dry-run-provenance-guards` job runs only on `pull_request` events. It exercises the guard
logic against the PR branch without touching external state:

| Guard | Covered in dry-run? |
|-------|-------------------|
| SemVer tag classification (production vs suffixed) | Yes |
| Master ancestry check | Yes (hermetic simulation) |
| Maven version-reuse pre-check | Yes (if token available) |
| Tag object type (annotated vs lightweight) | No — needs an actual tag |
| SHA equality: tag peel vs GITHUB_SHA | No — needs triggering tag ref |
| Post-publish Maven metadata verification | No — needs published package |
| Release asset completeness | No — needs published release |
| Clean consumer resolution | No — needs published Maven artifact |

The `Dry-run provenance guards (PR validation)` check appears on the PR as a green CI gate. It makes
no external state changes.

## 7. Required status checks

All status checks that must pass before merging to master:

| Check | Purpose |
|-------|---------|
| Validate Documentation | Markdown tables and links hygiene |
| build (11) | Build with Java 11 |
| build (17) | Build with Java 17 |
| SBOM and dependency vulnerability gate | CycloneDX SBOM + Grype vulnerability scan |
| MCP 2026-07-28 conformance | MCP spec conformance suite |
| grype | Grype vulnerability scan (HIGH/CRITICAL blocking) |
| Dry-run provenance guards (PR validation) | Provenance guard dry-run (PR only) |

## 8. Verification commands

```bash
# Inspect branch protection (read-only)
gh api repos/vinhphan812/mcp-java-sdk/branches/master/protection

# Inspect tag-protection ruleset (read-only)
gh api repos/vinhphan812/mcp-java-sdk/rulesets/25088082

# Inspect an existing release (read-only)
gh release view v1.2.3 --repo vinhphan812/mcp-java-sdk

# Inspect tag object and peeled commit (read-only)
git cat-file -t refs/tags/v1.2.3
git rev-parse refs/tags/v1.2.3
git rev-parse refs/tags/v1.2.3^{commit}

# List release tags (read-only)
gh api repos/vinhphan812/mcp-java-sdk/tags --jq '.[].name'
```

## 9. Limits

These controls execute only when GitHub Actions runs with its configured token and GitHub Packages is
reachable. They cannot retroactively validate, repair, delete, or replace historical releases, tags,
or packages. GitHub repository and tag-protection settings remain separate administrative controls.

_Last updated: 2026-10-15 (H1–H4 fixes applied)_
