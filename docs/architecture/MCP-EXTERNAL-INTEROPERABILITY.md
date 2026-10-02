# MCP External Interoperability Matrix

Status: Harness and scheduled CI are installed; first scheduled/dispatch run is required to populate pass/fail evidence.

## Pinned external clients/SDKs

| Client/SDK | Version | Transport | Driver | Evidence artifact |
|---|---:|---|---|---|
| `modelcontextprotocol/python-sdk` | `1.0.0` (matrix pin) | HTTP + STDIO | `harness/test_http_client.py`, `harness/test_stdio_client.py` | JSON result + log per client/version |
| `modelcontextprotocol/claude-code` | `latest` channel (tracked by scheduled CI) | HTTP | `harness/test_http_client.py` | JSON result + log per client/version |

The harness sends requests through an independent non-Java client process (Python) and does not import Java classes or JUnit fixtures. The SDK itself is built by Gradle in a separate job and is only exposed to the client process as a server endpoint/classpath.

## Required method evidence

The matrix covers, when the corresponding transport is enabled:

- `initialize` and protocol-version negotiation
- `server/discover` and 2026 routing headers (`Mcp-Method`, `Mcp-Name`)
- `tools/list` and a real non-Java `tools/call`
- `resources/list` and `resources/read`
- `prompts/list` and `prompts/get`
- notifications (no-response behavior)
- `tasks/create` (2026)
- unknown method/tool and malformed routing failure paths

## Failure attribution

Every test result records:

- external client name and version;
- protocol version;
- transport (`http` or `stdio`);
- MCP method/test identifier;
- HTTP status or STDIO status;
- response/error details and duration.

`harness/compile_matrix.py` aggregates all JSON results into the CI-uploaded Markdown matrix. The workflow fails the gate if any client/transport result fails, while preserving the JSON and log artifacts for diagnosis.

## CI schedule and artifacts

Workflow: `.github/workflows/interop.yml`

- Runs on pull requests, pushes to the protocol feature branch, manual dispatch, and daily at 06:00 UTC.
- Builds Java 11 and Java 17 artifacts independently.
- Runs HTTP and STDIO jobs independently with fail-fast disabled.
- Uploads per-client JSON/log artifacts for 14 days.
- Uploads the compiled compatibility matrix for 90 days.

This document intentionally does not claim interoperability until the first external CI run produces passing artifacts.
