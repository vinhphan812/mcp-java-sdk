# MCP 2026-07-28 Conformance Gate

The CI workflow runs the official `@modelcontextprotocol/conformance@0.2.0-alpha.12` runner against the frozen `2026-07-28` server requirements. Node/npm is CI-only runner tooling; it is not a Java SDK build or runtime dependency.

## Scope

The fixture uses the SDK's Streamable HTTP endpoint on an ephemeral loopback port. STDIO is deliberately out of scope. The fixture is compiled from `examples/` and is never packaged in the SDK JAR.

The gate invokes:

```bash
conformance server --url <fixture-url> --requirements 2026-07-28 \
  --expected-failures conformance-baseline.yml --output-dir results --verbose
```

`conformance-baseline.yml` is intentionally empty. Required scenario failures fail the CI job. Do not add Tasks or other required-wire scenario IDs to this file. Any future optional-extension exception must use an exact runner scenario ID and cite runner evidence showing that the fixture does not advertise the feature.

## Local reproduction

```bash
./gradlew --no-daemon jar --console=plain
./gradlew --no-daemon -p examples compileJava conformanceClasspath --console=plain
npm install --prefix .ci/conformance-npm --no-save --no-audit --no-fund \
  @modelcontextprotocol/conformance@0.2.0-alpha.12
java -cp "$(cat examples/build/conformance-classpath.txt)" \
  io.github.vinhphan812.mcp.ConformanceFixture
# In another terminal, use the URL printed by the fixture:
.ci/conformance-npm/node_modules/.bin/conformance server --url <fixture-url> \
  --requirements 2026-07-28 --expected-failures conformance-baseline.yml \
  --output-dir results --verbose
```

The CI job writes the runner output and fixture log to the `conformance-results` artifact, including when the runner fails. The npm installation gets one 15-second retry only when its logged error indicates a transient download/network/rate-limit failure; protocol conformance failures are never retried.
