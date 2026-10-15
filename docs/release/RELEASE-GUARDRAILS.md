# Release Guardrails

This document describes the release checks implemented in `.github/workflows/release.yml`. It does not change GitHub rulesets and it does not authorize retagging, release editing, or package overwrites.

## Release trigger and tag policy

The workflow runs for tags matching `v*`, but only an exact `vMAJOR.MINOR.PATCH` tag is eligible to publish a production package and GitHub Release.

- An exact production tag must be an **annotated tag**. It must be created and pushed before the workflow starts.
- The workflow only inspects the triggering tag. It never creates, moves, deletes, or force-updates a tag.
- Lightweight exact tags fail the production gate.
- Tags with a suffix, including `v1.1.4-skills-fix`, are validation-only. They do not enter the publication job and cannot publish a production Maven coordinate.

Tag protection is governed separately by ruleset `25088082`; this workflow neither reads nor changes that ruleset.

## Provenance validation

Before any production publication, the validation job fails closed unless all of the following hold:

1. `GITHUB_REPOSITORY` is exactly `vinhphan812/mcp-java-sdk` and `origin` is exactly its HTTPS GitHub URL.
2. The triggering ref exists locally and can be inspected.
3. The workflow records the tag object ID and its object type separately.
4. `refs/tags/<tag>^{commit}` peels to a commit that equals both checkout `HEAD` and `GITHUB_SHA`.
5. A deterministic full fetch of `origin/master` succeeds.
6. The peeled tag commit is an ancestor of the fetched protected-master commit. Both SHA values are printed before the ancestry check.

The workflow contains a hermetic regression simulation proving both annotated and lightweight tags peel to the intended commit, that a master commit passes ancestry, and that a feature-only commit fails it. These are workflow-local tests; they do not create repository tags or releases.

## Version reuse policy

For an eligible production tag, the publication job refuses to continue if:

- a GitHub Release already exists for the tag; or
- Maven metadata already contains the intended package version.

An existing release or version is terminal for that version. Recovery requires a new patch version. Operators must never retarget a tag, edit a release into a replacement, or re-upload package assets for a reused version.

## Publication and post-release verification

After a clean Gradle build, test run, Javadoc generation, SBOM generation, and dependency scan, the job publishes the Maven coordinate and creates a GitHub Release with `--verify-tag`. It packages all of the following release assets:

- main JAR;
- sources JAR;
- Javadoc JAR;
- CycloneDX `bom.json`;
- `asset-sha256sums.txt`; and
- `provenance.json`.

The generated provenance JSON records the repository, tag, semantic version, tag object SHA, peeled tag commit SHA, protected-master SHA, and SHA-256 manifest filename. It is uploaded as a machine-readable workflow artifact together with the manifest.

The final verification step fails closed unless the release is public (not draft or prerelease), names the expected tag, includes every required asset, and the release tag still peels to the expected commit. It also requires Maven metadata to contain the published version and uses a temporary clean Gradle consumer project to resolve `io.github.vinhphan812.mcp:mcp-java-sdk:<version>` from GitHub Packages.

## Operational checks

Inspect an existing release without changing it:

```bash
gh release view v1.2.3 --repo vinhphan812/mcp-java-sdk
```

Inspect an existing tag's object and peeled commit:

```bash
git cat-file -t refs/tags/v1.2.3
git rev-parse refs/tags/v1.2.3
git rev-parse refs/tags/v1.2.3^{commit}
```

Inspect tag-protection rules without modifying them:

```bash
gh api repos/vinhphan812/mcp-java-sdk/rulesets/25088082
```

## Limits

These controls execute only when GitHub Actions runs with its configured token and GitHub Packages is reachable. They cannot retroactively validate, repair, delete, or replace historical releases, tags, or packages. GitHub repository and tag-protection settings remain separate administrative controls.
