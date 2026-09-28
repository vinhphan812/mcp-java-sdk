# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Deprecated

- `TlsConfig` class, constructor, and `defaults()` factory are deprecated. This class does not
  configure TLS; TLS termination is handled by a reverse proxy. No replacement SDK TLS API is
  planned. Removal is reserved for the next explicitly planned major release.
  See [ADR-0014](docs/adr/ADR-0014-tls-transport-contract.md) for the full decision rationale.

### Documentation

- `scheme("https")` is documented as an external TLS indicator (reverse-proxy termination), not in-process TLS.
  See [TRANSPORT-STREAMABLE-HTTP.md](docs/transport/TRANSPORT-STREAMABLE-HTTP.md).
