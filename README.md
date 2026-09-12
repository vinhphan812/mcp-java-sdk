# MCP Java SDK

Single portable Java 8 artifact containing MCP protocol core plus optional Grizzly Streamable HTTP transport and server bootstrap. An Android application can use this SDK to host an MCP server inside the phone app process and expose the server over HTTP, provided the selected transport and all runtime dependencies work on the target Android version. Android API 21 compatibility is not claimed without device/emulator evidence. Application services, credential storage, and Android/ROSA robot integrations are excluded from this SDK.

## Build

```bash
./gradlew clean test build
```

Build outputs `mcp-java-sdk-*.jar`, `mcp-java-sdk-*-sources.jar`, and `mcp-java-sdk-*-javadoc.jar` under `build/libs`.

Override version for a local build with `./gradlew -PsdkVersion=1.2.3 build`. The Gradle configuration derives a release version from a `v*` tag in the release workflow; ordinary builds default to `1.0-SNAPSHOT`. No tagged release has been executed yet.

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

`GITHUB_TOKEN` needs `read:packages` for consumers. The configured release workflow would use its repository `GITHUB_TOKEN` with `packages: write`; publishing is restricted to `v*` tags. No Maven Central publication is configured, and no GitHub Packages publication has been executed.

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

The workflows are configured for CI tests/builds and tag-triggered package publication/release creation. They have not produced a tagged release in the current repository state. Do not run `publish` locally unless `GITHUB_ACTOR` and a GitHub token with `write:packages` are configured.

## Hosting an MCP server on Android

This SDK can be embedded in an Android application to host an MCP server on the phone. The application creates an `McpServer`, registers its tools/resources/prompts, starts the server in the app process, and exposes the configured HTTP endpoint to an allowed client on the same device or network.

This is a supported architectural use case, not a claim that every Android version can run the bundled Grizzly transport. Before production use, verify the following on the target device or emulator:

- the SDK and Grizzly runtime dependencies resolve and load in the Android app;
- the selected Android API level supports the required Java bytecode, desugaring, networking, and thread behaviour;
- the app network/security policy permits the chosen bind address and port;
- the server remains alive under the Android application lifecycle and is stopped cleanly;
- the endpoint is protected with appropriate origin, authentication, network, and lifecycle controls.

The SDK has not been verified here as Android API 21-compatible, and no Android device/emulator startup test is included in the current evidence. Grizzly is a JVM/server-oriented transport dependency; if it is not compatible with the target Android runtime, use or implement another transport through the public API rather than treating the Grizzly transport as Android-compatible by assumption.

## Scope and licensing

The API includes annotations, handlers, configuration, reflection registration, concurrent registry, JSON-RPC protocol handling, session state, and the Grizzly HTTP transport. Applications can provide another transport through the public API. WebSocket is not included in this package; a robot or application may use an external WebSocket bridge or adapter when its integration requires one.

This repository is distributed under the Apache License 2.0; see the root `LICENSE` file. Gson `2.11.0` and Grizzly `4.0.2` are resolved from Maven Central; review their upstream notices before redistribution. CI, package, and release workflows are configured, but no tagged release or GitHub Packages publication has been executed.

Documentation:

- `docs/PROJECT-GUIDE.md` — architecture, API, protocol, transport, and release guide.
- `docs/API-REFERENCE.md` — complete public API surface.
- `docs/IMPLEMENTATION-STATUS.md` — completed, incomplete, and unverified areas.
- `docs/GRIZZLY-EXAMPLE.md` — standalone Grizzly example and HTTP requests.
- `docs/MCP-COMPATIBILITY-2026.md` — MCP baseline and compatibility status.
