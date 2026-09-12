# Contributing to MCP Java SDK

Thank you for your interest in contributing.

## Getting started

```bash
git clone https://github.com/vinhphan812/mcp-java-sdk.git
cd mcp-java-sdk
./gradlew test
```

Verify the build is green before making changes.

## Development workflow

1. **Fork** the repository
2. **Branch** from `master`: `git checkout -b feat/my-feature`
3. **Implement** your change
4. **Test**: add JUnit 5 tests; run `./gradlew test`
5. **Build**: run `./gradlew clean build` — must pass with 0 warnings
6. **Commit**: use conventional commits (`feat:`, `fix:`, `docs:`, `refactor:`)
7. **Push**: `git push origin feat/my-feature`
8. **Open a PR** against `master`

## Code standards

- Java 8 source/target compatibility
- No new `@SuppressWarnings` for IntelliJ-specific inspection IDs (use `//noinspection` comments instead)
- New public API methods require Javadoc
- No `System.out.println` or `e.printStackTrace` in source
- Sensitive values (credentials, tokens) must never appear in source or tests

## Test standards

- Every new feature needs at least one JUnit 5 test
- Tests must pass in isolation and in CI
- Do not commit failing tests

## Documentation

- New public API surfaces require Javadoc
- Architectural decisions require an ADR in [docs/adr/](docs/adr/)
- Update [docs/IMPLEMENTATION-STATUS.md](docs/IMPLEMENTATION-STATUS.md) for new features
- Cross-link related documents with relative paths

## Reporting issues

- Search existing issues before creating a new one
- Include: Java version, OS, SDK version, minimal reproduction case
- For Android issues: include device/API level and Grizzly behaviour observed

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
