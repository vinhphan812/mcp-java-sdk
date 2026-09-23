# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Removed

- `TlsConfig` class removed. TLS termination should be handled by a reverse proxy.
  If you need TLS, configure it at the proxy level (nginx, Apache, cloud LB, K8s ingress).
  See [ADR-0014](docs/adr/ADR-0014-tls-transport-contract.md) for the full decision rationale.

### Documentation

- Added guidance on TLS setup via reverse proxy in the [transport documentation](docs/guides/TRANSPORT-SSE.md).
- `scheme("https")` is now documented as an external TLS indicator, not in-process TLS.
