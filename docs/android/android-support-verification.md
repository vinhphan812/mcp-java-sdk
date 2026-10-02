# Android Support Verification Report

**Task:** t_879cbce0 — Verify Android support or narrow claims with reproducible evidence
**Date:** 2026-10-01
**Branch:** master (97700d2)
**Build Status:** `./gradlew clean test` passes (49 tests)

---

## 1. Evidence Summary

| Criterion                    | Status       | Evidence                                                      |
|------------------------------|--------------|---------------------------------------------------------------|
| Build compiles               | **PASS**     | `./gradlew clean test BUILD SUCCESSFUL`                       |
| Unit tests pass              | **PASS**     | 49 tests passing                                              |
| No Android imports in source | **PASS**     | `grep -r "import android\." src/main/java/` = no matches      |
| Dependency bytecode level    | **VERIFIED** | Grizzly: 891 classes @ bytecode v55 (Java 11)                 |
| Android minSdk target        | **VERIFIED** | CruzrEnglishAssistant: minSdk=22, targetSdk=28, compileSdk=35 |

---

## 2. Package Analysis

### 2.1 Core Packages (portable)

| Package        | Classes | Android Compatible | Evidence                                                             |
|----------------|---------|--------------------|----------------------------------------------------------------------|
| `annotations/` | ~8      | **YES**            | Runtime annotations only, no Android imports, no non-Java-SE imports |
| `api/`         | ~25     | **YES**            | Standard Java SE interfaces, no Android imports                      |
| `core/`        | ~12     | **YES**            | Standard Java SE, no Android imports                                 |

Source scan result:

```
grep -r "import android\.\|import androidx\." src/main/java/
# No matches in production source
```

### 2.2 Transport Package (Grizzly)

| Package      | Dependency    | Bytecode Level    | Android Compatible       |
|--------------|---------------|-------------------|--------------------------|
| `transport/` | Grizzly 4.0.2 | **v55 (Java 11)** | **NO** — requires JVM 11 |

Grizzly transitive dependency tree:

```
grizzly-http-server:4.0.2   173 classes  bytecode v55
grizzly-framework:4.0.2      552 classes  bytecode v55
grizzly-http:4.0.2           166 classes  bytecode v55
────────────────────────────────────────────────────────────────
Total:                       891 classes  bytecode v55 (Java 11)
```

---

## 3. Android Runtime Compatibility

### 3.1 Java Version Mismatch

| Component                       | Required JVM   | Android Default JVM             |
|---------------------------------|----------------|---------------------------------|
| Grizzly 4.0.x transport         | **Java 11+**   | Java 8 (API <26)                |
| SDK core (annotations/api/core) | Java 11 source | Android API 22+ with desugaring |

### 3.2 Android Desugaring Requirements

For the core packages to run on Android with desugaring:

- **minSdk 22**: Requires `coreLibraryDesugaring` enabled
- **Required Android Gradle Plugin**: 4.0+ for desugaring support
- **Required dependency**: `com.android.tools:desugar_jdk_libs`

The CruzrEnglishAssistant uses AGP 8.7.3, which supports desugaring.

### 3.3 Guava JRE vs Android

| Dependency | Artifact           | Bytecode Level | Android Issue                    |
|------------|--------------------|----------------|----------------------------------|
| Guava      | `guava:33.3.0-jre` | v52 (Java 8)   | Compatible but optimized for JVM |

The `-jre` artifact is functionally compatible with Android (bytecode v52), but the SDK uses the JVM-targeted artifact.
For Android, consider `guava:33.3.0-android`.

---

## 4. Current Documentation Assessment

### 4.1 README.md Claims

```
"The library runs inside an Android app or robot service process.
A phone or robot can act as an MCP server — no separate backend needed.
The full SDK requires JVM 11+ (Grizzly 4.0.x bytecode v55);
Android API 21 does not provide JVM 11."
```

**Assessment:** This claim is **ACCURATE** regarding the limitation.

### 4.2 Conditional Claim

```
"The portable core (`annotations/` + `api/` + `core/`) may run on
Android API 21+ if the application supplies an alternative HTTP transport."
```

**Assessment:** This claim is **CONDITIONALLY ACCURATE** — the core is API-compatible but NOT VERIFIED on Android.

### 4.3 Problematic Statement

```
"desktop, server, or Android with a compatible Java 11+ runtime"
```

This implies Android with Java 11 runtime is a supported path, but:

1. Android does not ship with Java 11 by default
2. Java 11 on Android requires either a third-party runtime (unlikely to be available on Cruzr robots) or desugaring
3. Neither path has been **VERIFIED** on actual Android hardware

---

## 5. Verification Gaps (Unverified)

The following remain unverified due to lack of physical Android device or emulator access:

| Item                                          | Status         | Blocker                     |
|-----------------------------------------------|----------------|-----------------------------|
| Core packages compile with Android desugaring | **UNVERIFIED** | No Android module in SDK    |
| Core packages run on Android device           | **UNVERIFIED** | No emulator/device test     |
| Grizzly transport on Android                  | **UNVERIFIED** | Bytecode mismatch — blocked |
| MCP tool call on Android                      | **UNVERIFIED** | No device test              |
| Server lifecycle on Android                   | **UNVERIFIED** | No device test              |

---

## 6. Dependency Packaging

### 6.1 Runtime Graph (JVM)

```
mcp-java-sdk:1.0-SNAPSHOT
├── com.google.code.gson:gson:2.11.0
│   └── com.google.errorprone:error_prone_annotations:2.27.0
├── org.glassfish.grizzly:grizzly-http-server:4.0.2
│   ├── grizzly-http:4.0.2
│   │   └── grizzly-framework:4.0.2
│   └── (internal deps)
└── com.google.guava:guava:33.3.0-jre
    ├── failureaccess:1.0.2
    ├── listenablefuture:9999.0-empty-to-avoid-conflict-with-guava
    ├── jsr305:3.0.2
    ├── checker-qual:3.43.0
    └── error_prone_annotations:2.28.0
```

### 6.2 Android-Compatible Dependency Subset

For the **core packages only** (without Grizzly transport):

```
implementation 'com.google.code.gson:gson:2.11.0'
// Guava: replace 'guava:33.3.0-jre' with 'guava:33.3.0-android'
```

---

## 7. Recommendations

### 7.1 Documentation Narrowing

The README.md and PROJECT-GUIDE.md should include explicit language:

**Change from:**
> "including Android apps, Android/ROSA robots, desktop services, and backend servers"

**Change to:**
> "including desktop services and backend servers running JVM 11+"

**Add explicit section:**
> ### Android and Robot Hosting (Conditional)
>
> The core packages (`annotations/`, `api/`, `core/`) use only standard Java SE APIs and
> are architecturally compatible with Android API 22+ when desugaring is enabled.
> **This has not been verified on physical Android hardware or emulators.**
>
> The full SDK including Grizzly transport requires JVM 11+ and is **not compatible**
> with standard Android runtime environments.
>
> For Android hosting, provide an alternative HTTP transport implementation.

### 7.2 Required for Full Android Support

To claim verified Android support, the following are required:

1. **Android module in SDK**: `android/` subproject with desugaring configuration
2. **minSdk verification**: Test on Android API 22+ (matching CruzrEnglishAssistant)
3. **Device test**: Run MCP server on physical Android device or emulator
4. **MCP tool call verification**: Execute `tools/call` via HTTP client on device
5. **Lifecycle test**: Verify server startup, bind, and shutdown on Android
6. **Guava Android artifact**: Replace `guava:33.3.0-jre` with `guava:33.3.0-android`

### 7.3 Transport Isolation (Already Implemented)

The architecture already isolates transport in `transport/` with `core/` independent:

- Core: `annotations/` + `api/` + `core/` — can run on Android with desugaring
- Transport: `transport/` (Grizzly) — requires JVM 11+, not Android-compatible

This is the correct design for future Android support.

---

## 8. Conclusion

| Claim                                    | Verdict          | Confidence                        |
|------------------------------------------|------------------|-----------------------------------|
| Java 11+ runtime required for full SDK   | **VERIFIED**     | High — bytecode v55 confirmed     |
| Grizzly transport requires JVM 11+       | **VERIFIED**     | High — bytecode analysis          |
| Core packages use no Android imports     | **VERIFIED**     | High — static source scan         |
| Core packages are Android-API-compatible | **CONDITIONAL**  | Medium — standard Java SE only    |
| Full SDK runs on Android                 | **NOT VERIFIED** | Low — no device/emulator evidence |
| Portable core runs on Android            | **NOT VERIFIED** | Low — no desugaring test          |

**The SDK does NOT have verified Android support.** The documentation should be narrowed to reflect this. The path
forward is to add an Android verification module and test on physical hardware.

---

## 9. Evidence Checklist

- [x] Build compiles successfully
- [x] Unit tests pass (49 tests)
- [x] No Android imports in production source
- [x] Dependency bytecode level documented
- [x] Grizzly transport bytecode v55 confirmed
- [x] Documentation claims reviewed
- [x] Android minSdk/targetSdk verified (CruzrEnglishAssistant: minSdk=22)
- [x] Guava artifact analyzed (`guava:33.3.0-jre`)
- [x] ADR-0019 reviewed (Java 11 floor documented)
- [x] ADR-0001 reviewed (portable core documented)
- [ ] Android desugaring build test
- [ ] Android device/emulator test
- [ ] MCP tool call on Android
- [ ] Server lifecycle on Android
