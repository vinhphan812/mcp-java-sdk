# Security Policy

## Supported Versions

| Version | Supported          | Notes                                    |
|---------|--------------------|------------------------------------------|
| 1.x     | :white_check_mark: | Current stable release line              |
| < 1.0   | :x:                | No security guarantees                   |

## Reporting a Vulnerability

We take security issues seriously. If you discover a vulnerability in this SDK,
please report it responsibly so we can protect all users.

**Please do NOT file a public GitHub issue for security vulnerabilities.**

### How to Report

1. **Email** — Send a description of the vulnerability to the maintainer directly
   via GitHub. Use the "Security" tab on the repository to report privately.

2. **What to include**:
   - Clear description of the issue and its impact
   - Steps to reproduce (proof-of-concept or test case)
   - Affected version(s)
   - Any proposed mitigations or fixes you are aware of

3. **Response timeline**:
   - Acknowledgement: within 48 hours
   - Initial assessment: within 7 days
   - Fix timeline: varies by severity; critical issues are prioritised

### Severity Policy

Dependency vulnerability scan results are classified as follows:

| Severity   | CI gate  | Action                                          |
|------------|----------|-------------------------------------------------|
| CRITICAL   | **Fail** | Blocks merge and release; must be fixed or excepted |
| HIGH       | **Fail** | Blocks merge and release; must be fixed or excepted |
| MEDIUM     | Warn only | Printed in workflow log; does not block; tracked |
| LOW        | Info only | Informational; no blocking action               |
| UNKNOWN    | Info only | Informational; investigate if persistent        |

## Dependency Management

### SBOM (Software Bill of Materials)

This project generates a **CycloneDX 1.5 JSON SBOM** on every commit and
attaches it to every GitHub Release.

- **Tool**: `org.cyclonedx.bom` Gradle plugin (v1.10.0)
- **Output**: `build/bom.json`
- **Contents**: All direct and transitive production (`implementation`) dependencies
- **Reproducibility**: SBOM uses the `sdkVersion` Gradle property (or `GITHUB_REF_NAME`
  in CI); local builds without `-PsdkVersion` produce a `1.0-SNAPSHOT` SBOM.

### Vulnerability Scanning

- **Tool**: [Grype](https://github.com/anchore/grype) via `anchore/sbom-action`
- **Trigger**: Every push to `master` and every pull request (CI); every tag push (release)
- **Database**: Updated automatically by the GitHub Action
- **Results**: Uploaded as SARIF to the GitHub Security tab (`Security > Code scanning`)

### Acceptable Exceptions

When a vulnerability cannot be remediated immediately (e.g., no patched version of a
third-party library is available), a **documented exception** must be created.

See [SECURITY_EXCEPTIONS.md](./SECURITY_EXCEPTIONS.md) for the current exception registry.

### Adding / Updating Exceptions

1. Add an entry to `SECURITY_EXCEPTIONS.md` with:
   - Dependency and affected version(s)
   - CVE identifier and severity
   - Rationale (why it cannot be patched now)
   - Expiry date (target remediation date or `N/A` if unfixable)
   - Date of exception grant
2. Create a GitHub issue to track the remediation and link it from the exception entry.
3. Review exceptions at least every 90 days; prune expired entries.
