# Android Emulator/Device Evidence Plan for GitHub #8

**Task:** t_897f5c84 — Establish reproducible Android emulator/device evidence plan for GitHub #8
**Date:** 2026-10-02
**Workspace:** D:\android\mcp-java-sdk
**Assignee:** dev-android

---

## 1. Environment Assessment

### 1.1 Tool Availability

| Tool       | Status          | Notes                                                              |
|------------|-----------------|--------------------------------------------------------------------|
| Java       | **AVAILABLE**   | Present on host; Java version detection caused segfault in bash   |
| Gradle     | **AVAILABLE**   | Gradle wrapper (`gradlew`) present in repo                         |
| Android SDK| **PARTIAL**     | `ANDROID_HOME` set but empty or not accessible from bash           |
| ADB        | **AVAILABLE**   | Version 1.0.41, installed at D:\platform-tools-latest-windows\...  |
| Emulator   | **NOT FOUND**   | `emulator` binary not on PATH; no AVDs configured                  |
| Platform-tools | **FOUND**   | D:\platform-tools-latest-windows\platform-tools\adb.exe            |

### 1.2 SDK Version from Codebase

| Property              | Value          | Source                                           |
|-----------------------|----------------|--------------------------------------------------|
| Java source level     | Java 11        | build.gradle: `sourceCompatibility = VERSION_11` |
| Java target level     | Java 11        | build.gradle: `targetCompatibility = VERSION_11` |
| Gradle wrapper        | 8.x            | gradle/wrapper/gradle-wrapper.properties         |
| CI Java versions      | 11, 17         | .github/workflows/ci.yml matrix                  |
| Grizzly bytecode      | v55 (Java 11)  | Verified in android-support-verification.md      |
| Guava artifact        | 33.3.0-jre     | build.gradle                                     |
| Gson version          | 2.11.0         | build.gradle                                     |

### 1.3 Current Android Support Claims (from codebase)

From `docs/android/android-support-verification.md`:

| Claim                                      | Status           |
|--------------------------------------------|------------------|
| Core packages Android-API-compatible        | CONDITIONAL      |
| Grizzly transport requires JVM 11+         | VERIFIED         |
| No Android imports in core                 | VERIFIED         |
| minSdk 22 compatible with desugaring       | VERIFIED (code)  |
| Full SDK runs on Android device            | NOT VERIFIED     |
| Portable core runs on Android              | NOT VERIFIED     |

---

## 2. Runtime Requirements Analysis

### 2.1 Minimum Android API Level

| Component                          | Min API | Evidence                                              |
|------------------------------------|---------|-------------------------------------------------------|
| Core packages (`annotations/api/core`) | **API 22** | Uses Java 8 APIs only; verified source scan         |
| McpProtocolHandler                 | **API 22** | javadoc: "Uses Java 8 APIs only (Android API 22 compatible)" |
| Guava 33.3.0-jre                   | **API 22** | bytecode v52 (Java 8); compatible but consider `-android` artifact |
| Gson 2.11.0                        | **API 22** | Pure Java; no JVM-specific features                  |
| Grizzly 4.0.2 transport            | **JVM 11+** | Bytecode v55; NOT compatible with Android runtime   |

### 2.2 Desugaring Requirements

For core packages on Android without a JVM 11 runtime:

```gradle
android {
    compileSdk 35  // or latest available

    defaultConfig {
        minSdk 22
        targetSdk 28
    }

    compileOptions {
        coreLibraryDesugaringEnabled true
    }

    dependencies {
        coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.4'
    }
}
```

Required for: `java.time` APIs, `java.util.concurrent.atomic`, `java.nio.file`, etc.

### 2.3 Android Runtime Compatibility Matrix

| SDK Component       | Standard JVM (11+) | Android + Desugaring (API 22+) | Android (no desugaring) |
|---------------------|:------------------:|:-------------------------------:|:------------------------:|
| `annotations/`      | YES                | YES                             | YES                      |
| `api/`              | YES                | YES                             | YES                      |
| `core/`             | YES                | YES                             | YES                      |
| Grizzly transport   | YES                | NO (bytecode v55)               | NO                       |
| Gson 2.11.0        | YES                | YES                             | YES                      |
| Guava 33.3.0-jre    | YES                | YES (use `-android` artifact)   | YES                      |

### 2.4 No @RequiresApi Annotations Found

Search across `src/main/java/` found zero `@RequiresApi` or `@TargetApi` annotations. The codebase makes no compile-time enforcement of minimum API level — runtime compatibility is implicit (Java 8 surface only).

---

## 3. Sample-App Requirements

### 3.1 Existing Application

The **CruzrEnglishAssistant** (D:\android\CruzrEnglishAssistant) is the consuming application:

| Property    | Value       | Source                                    |
|-------------|-------------|-------------------------------------------|
| compileSdk  | 35          | build.gradle                              |
| minSdk      | 22          | build.gradle                              |
| targetSdk   | 28          | build.gradle                              |
| AGP version | 8.7.3       | build.gradle                              |

### 3.2 Required Permissions

For MCP server hosting on Android, the following may be required:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### 3.3 Required Activities and Service Bindings

A minimal test application would need:

| Component           | Purpose                                              |
|---------------------|------------------------------------------------------|
| `MainActivity`      | Entry point; start/stop MCP server via UI button   |
| `McpServerService`  | Bound/foreground service hosting the MCP server     |
| HTTP server binding | Port 8080 or dynamic port for JSON-RPC calls       |

### 3.4 Transport Modes

The SDK supports two transport modes relevant to Android:

| Transport             | File                                      | Android Compatible? | Port Required? |
|-----------------------|-------------------------------------------|--------------------|---------------|
| Grizzly HTTP          | `transport/HttpTransportProvider.java`    | NO (JVM 11+)       | Yes           |
| STDIO                 | N/A — not implemented                    | N/A — not implemented | N/A          |

STDIO transport is not implemented. For Android testing, HTTP transport is the only available option (requires JVM).

---

## 4. Server Evidence Checklist

### 4.1 Server Startup Evidence

```
EXPECTED LOG LINES (test assertions against logcat / service log):
[Log] MCP Server initializing...
[Log] Registry bound: <N> tools, <M> resources, <K> prompts
[Log] Transport started: HTTP:port
[Log] Server ready — listening for requests
```

**Verification commands:**
```bash
# Via logcat
adb logcat -d | grep -i "MCP\|mcp-server\|mcpServer"

# Via shell on device
adb shell "logcat -d | grep -i 'mcp'"
```

### 4.2 Tool-Call Invocation Evidence

```
JSON-RPC request:
POST / HTTP/1.1
Content-Type: application/json

{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"<tool_name>","arguments":{}}}

EXPECTED RESPONSE:
{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"<result>"}]}}
```

### 4.3 Graceful Shutdown Evidence

```
EXPECTED LOG LINES:
[Log] Shutdown signal received
[Log] Closing <N> active sessions
[Log] Transport stopped
[Log] Server shutdown complete
[Process exit] exit code 0
```

### 4.4 Lifecycle Events

| Event              | Evidence                              |
|--------------------|---------------------------------------|
| Start              | Log line: "Server ready"             |
| Stop               | Log line: "Server shutdown complete"  |
| Restart            | Two start + two stop log sequences    |
| Crash recovery     | Process restart + "Server ready" again |

---

## 5. Dependency Packaging Verification

### 5.1 APK Dependency Inspection

After building the Android APK, verify runtime dependency availability:

```bash
# 1. List APK contents for JAR/AAR dependencies
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "gson|guava|grizzly"

# 2. Check merged DEX for class presence
$D8_BIN/d8 --list app/build/outputs/apk/debug/app-debug.apk | grep "com.google.gson\|com.google.common\|org.glassfish.grizzly"

# 3. Verify at runtime via reflection (Android instrumentation test)
```

### 5.2 Runtime Verification Test Class

```java
// RuntimeDependencyTest.java — instrumentation test
@RunWith(AndroidJUnit4.class)
public class RuntimeDependencyTest {
    @Test
    public void gson_isAccessible() throws Exception {
        Class<?> gsonClass = Class.forName("com.google.gson.Gson");
        Object gson = gsonClass.getConstructor().newInstance();
        String json = gsonClass.getMethod("toJson", Object.class).invoke(gson, Map.of("key", "value")).toString();
        assertTrue(json.contains("key"));
    }

    @Test
    public void guava_ListenableFuture_isAccessible() throws Exception {
        Class<?> clazz = Class.forName("com.google.common.util.concurrent.ListenableFuture");
        assertNotNull(clazz);
    }

    @Test
    public void mcpCoreClasses_loadable() throws Exception {
        Class<?> handler = Class.forName("io.github.vinhphan812.mcp.core.McpProtocolHandler");
        assertNotNull(handler);
    }
}
```

### 5.3 Expected APK Dependency Locations

| Dependency        | Expected location in APK                              |
|-------------------|-------------------------------------------------------|
| Gson 2.11.0       | `classes.dex` (merged) or `libs/gson-2.11.0.jar`    |
| Guava 33.3.0-jre  | `classes.dex` (merged) or `libs/guava-33.3.0.jar`   |
| Grizzly           | **NOT present** (excluded for Android)               |
| MCP core classes  | `classes.dex` (primary DEX)                          |

---

## 6. Command & Artifact Inventory

### 6.1 Build Commands

```bash
# Android build
cd D:\android\CruzrEnglishAssistant
./gradlew assembleDebug                      # Build debug APK
./gradlew assembleRelease                    # Build release APK

# SDK library build (for embedding in Android)
cd D:\android\mcp-java-sdk
./gradlew jar                                # Build mcp-java-sdk-*.jar
./gradlew test                               # Run unit tests (49 tests)
```

### 6.2 Device Test Commands

```bash
# Connect to device
adb connect <device-ip>:5555
adb devices

# Install APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Start app / service
adb shell am start-service com.example.app/.McpServerService
adb shell am stopservice com.example.app/.McpServerService

# Log capture
adb logcat -c                               # Clear logcat
adb logcat -d > device-logcat.txt            # Dump all logs
adb logcat -s MCPService:D *:S               # Filter MCP service logs only

# Run instrumentation tests
adb shell am instrument -w com.example.app.test/androidx.test.runner.AndroidJUnitRunner

# Pull APK build artifacts
adb pull app/build/outputs/apk/ .
```

### 6.3 Artifact Locations

| Artifact                        | Location                                        |
|---------------------------------|-------------------------------------------------|
| Debug APK                       | `app/build/outputs/apk/debug/app-debug.apk`     |
| Release APK                     | `app/build/outputs/apk/release/app-release.apk` |
| Device logcat dump              | `device-logcat.txt`                             |
| Test results (JUnit XML)         | `app/build/test-results/testDebugUnitTest/`     |
| Instrumentation test results    | `app/build/outputs/apk/debug/results/`           |
| SDK JAR                         | `build/libs/mcp-java-sdk-*.jar`                 |
| SDK CycloneDX SBOM              | `build/reports/bom.json`                        |

---

## 7. Device Availability Check

### 7.1 Current Status

```
EMULATOR CHECK:
  Command: emulator -list-avds
  Result: emulator binary not found on PATH
  AVDs:   none configured

CONNECTED DEVICE CHECK:
  Command: adb devices
  Result:  no devices returned (device list empty)
  ADB:     version 1.0.41 available at D:\platform-tools-latest-windows

ANDROID_HOME:
  Path:   $ANDROID_HOME (set but contents not accessible from bash)
  Status: PARTIAL — platform-tools present, emulators not configured
```

### 7.2 Blocker Statement

**BLOCKER: No Android emulator or physical device is currently available.**

This task is blocked because no Android device or emulator has been detected. The evidence plan documented in this file cannot be executed without one of the following:

### 7.3 Provisioning Requirements

To proceed with evidence collection, provision ONE of the following:

#### Option A: Android Emulator (Recommended for CI/Automation)

```
1. Install Android SDK command-line tools:
   - Download from: https://developer.android.com/studio#command-line-tools-only
   - Set ANDROID_HOME, ANDROID_SDK_ROOT
   - Add $ANDROID_HOME/cmdline-tools/latest/bin to PATH

2. Create an AVD:
   $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-35"
   $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "system-images;android-35;google_apis;x86_64"
   $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd -n "mcp-test-api35" --package "system-images;android-35;google_apis;x86_64"

3. Launch emulator:
   emulator -avd mcp-test-api35 -no-window -no-audio &
   adb wait-for-device

4. Verify:
   adb devices  # should show: emulator-5554   device
```

**Minimum emulator requirements:**
- API level 35 (compileSdk)
- API level 22 minimum (minSdk)
- x86_64 or arm64-v8a system image
- Google Play APIs (for Google Guava/AndroidX compatibility)
- 4 GB RAM, 2 CPU cores

#### Option B: Physical Android Device

```
1. Enable USB/WiFi debugging on the device
2. Connect via ADB:
   adb connect <device-ip>:5555
   # OR
   adb devices  # USB-connected device

3. Verify device is accessible:
   adb shell "echo 'device OK'"
```

#### Option C: Robo Testing (No Device Required)

```
1. Use Robolectric for JVM-based Android unit tests:
   ./gradlew test    # Runs unit tests on JVM
   
2. Robolectric can simulate Android runtime:
   - API level 22..35
   - Resources, activities, services
   - No hardware/UI interaction
   - Cannot test native code or hardware-specific features
```

**Limitation:** Robolectric cannot verify:
- Actual network binding on a real device
- Real Android runtime behavior (ART vs. JVM differences)

---

## 8. Step-by-Step Evidence Collection Procedure

Once a device/emulator is available, execute in this order:

### Phase 1: Environment Verification

```bash
# 1. Verify device connection
adb devices
# Expected: <serial>    device

# 2. Check Android version
adb shell getprop ro.build.version.sdk
# Expected: >= 22

# 3. Verify ADB is functional
adb shell echo "ADB_OK"
# Expected: ADB_OK
```

### Phase 2: Build

```bash
# 4. Build Android APK with MCP SDK embedded
cd D:\android\CruzrEnglishAssistant
./gradlew clean assembleDebug
# Artifact: app/build/outputs/apk/debug/app-debug.apk

# 5. Verify APK contains MCP classes
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "vinhphan812/mcp"
# Expected: list of .class entries in DEX
```

### Phase 3: Instrumentation Tests

```bash
# 6. Run dependency verification instrumentation tests
./gradlew connectedDebugAndroidTest
# Or via ADB directly:
adb shell am instrument -w \
  -e class RuntimeDependencyTest#gson_isAccessible \
  com.example.app.test/androidx.test.runner.AndroidJUnitRunner

# 7. Run MCP server lifecycle test
./gradlew connectedDebugAndroidTest \
  -PtestClass=McpServerLifecycleTest
```

### Phase 4: Server Evidence

```bash
# 8. Start MCP server service
adb shell am start-service com.example.app/.McpServerService

# 9. Capture startup logs
adb logcat -c
adb shell am start-service com.example.app/.McpServerService
sleep 5
adb logcat -d | grep -iE "MCP|mcp-server|server ready" > server-startup-log.txt
cat server-startup-log.txt

# 10. Invoke tool call via HTTP (if HTTP transport enabled)
curl -X POST http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"test_tool","arguments":{}}}'
# Expected: {"jsonrpc":"2.0","id":1,"result":{...}}

# 11. Capture shutdown evidence
adb shell am stopservice com.example.app/.McpServerService
adb logcat -d | grep -iE "shutdown|stopped" > server-shutdown-log.txt
```

### Phase 5: Evidence Compilation

```bash
# 12. Collect all artifacts
adb pull /sdcard/Android/data/com.example.app/files/ ./
```

---

## 9. Gap Summary

| Item                                                        | Status     | Blocker                        |
|-------------------------------------------------------------|------------|--------------------------------|
| Environment assessment                                      | DONE       | —                              |
| Runtime requirements analysis                               | DONE       | —                              |
| Sample-app requirements (CruzrEnglishAssistant)             | DONE       | —                              |
| Server evidence checklist                                   | PLANNED    | No device/emulator available   |
| Dependency packaging verification                            | PLANNED    | No device/emulator available   |
| Command & artifact inventory                                | DONE       | —                              |
| Device availability check                                  | **BLOCKED**| No emulator or device detected |

---

## 10. References

| Document                                              | Location                                         |
|-------------------------------------------------------|--------------------------------------------------|
| Android support verification                          | `docs/android/android-support-verification.md`   |
| Project guide                                         | `docs/guides/PROJECT-GUIDE.md`                   |
| Previous work (t_879cbce0)                           | Kanban task t_879cbce0                           |
| CI workflow                                           | `.github/workflows/ci.yml`                       |
| Grizzly bytecode analysis                             | `docs/android/android-support-verification.md` §2 |
| ADR-0001 (portable core)                              | `docs/adr/ADR-0001-*.md`                         |
| ADR-0019 (Java 11 floor)                              | `docs/adr/ADR-0019-*.md`                         |
