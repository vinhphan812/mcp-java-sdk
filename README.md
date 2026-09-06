# MCP Java SDK

Single portable Java 8 artifact containing MCP protocol core plus optional Grizzly Streamable HTTP transport and server bootstrap. Android, Cruzr robot, application services, and credential storage are excluded. Android API 21 compatibility is not claimed without device/emulator evidence.

## Build

```bash
./gradlew clean test build
```

Build outputs `mcp-java-sdk-*.jar`, `mcp-java-sdk-*-sources.jar`, and `mcp-java-sdk-*-javadoc.jar` under `build/libs`.

Override version for a local build with `./gradlew -PsdkVersion=1.2.3 build`. Release tag `v1.2.3` automatically publishes version `1.2.3`; ordinary builds default to `1.0-SNAPSHOT`.

## GitHub Packages dependency

Create a GitHub Packages Maven repository token with `read:packages` and authenticate before resolving the dependency. The repository must be declared in Gradle; credentials must come from environment variables or Gradle properties, never source control:

```groovy
repositories {
    maven {
        url = uri('https://maven.pkg.github.com/vinhphan812/mcp-java-sdk')
        credentials {
            username = System.getenv('GITHUB_ACTOR')
            password = System.getenv('GITHUB_TOKEN')
        }
    }
}

dependencies {
    implementation 'io.github.vinhphan812.mcp:mcp-java-sdk:1.2.3'
}
```

`GITHUB_TOKEN` needs `read:packages` for consumers. Release workflow uses its repository `GITHUB_TOKEN` with `packages: write`; publishing is restricted to `v*` tags. No Maven Central publication is configured.

## CI and release

CI runs on pushes and pull requests targeting `master`:

```bash
./gradlew clean test build
```

To publish one artifact and create a GitHub Release with main, sources, and Javadoc JARs:

```bash
git tag v1.2.3
git push origin v1.2.3
```

GitHub Actions performs tests, build, GitHub Packages publication, and release creation. Do not run `publish` locally unless `GITHUB_ACTOR` and a GitHub token with `write:packages` are configured.

## Scope and licensing

The API includes annotations, handlers, configuration, reflection registration, concurrent registry, JSON-RPC protocol handling, session state, and optional Grizzly transport. Applications can provide another transport through the public API.

Source files retain a restrictive personal/educational-use notice and no root `LICENSE` exists. No license was invented; public redistribution and release remain blocked until copyright holder grants or selects a project license. Gson `2.11.0` and Grizzly `4.0.2` are resolved from Maven Central; review their upstream notices before redistribution.

Documentation:

- `docs/PROJECT-GUIDE.md` — architecture, API, protocol, transport, testing, and release guide.
- `docs/IMPLEMENTATION-STATUS.md` — completed, incomplete, and unverified areas.
- `docs/GRIZZLY-EXAMPLE.md` — standalone Grizzly example and HTTP requests.
- `docs/MCP-COMPATIBILITY-2026.md` — MCP baseline and compatibility status.
