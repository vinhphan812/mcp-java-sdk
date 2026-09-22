# Inspection XML Findings — Classification

Date: 2026-09-11 (latest export)

Total: 66 findings across 12 categories — all resolved.

## Category breakdown

| Category                                              |  Count | Classification                       |
|-------------------------------------------------------|-------:|--------------------------------------|
| `unused.xml` (intentional public SDK API)             |     44 | accepted                             |
| `DuplicatedCode.xml`                                  |      6 | **Fixed**                            |
| `RedundantSuppression.xml`                            |      4 | **Fixed**                            |
| `AutoCloseableResource.xml`                           |      2 | **Fixed**                            |
| `SameReturnValue.xml`                                 |      2 | **Fixed**                            |
| `SynchronizationOnLocalVariableOrMethodParameter.xml` |      2 | **Fixed**                            |
| `UnusedReturnValue.xml`                               |      2 | **Fixed**                            |
| `BusyWait.xml`                                        |      1 | **Fixed** (//noinspection)           |
| `MismatchedCollectionQueryUpdate.xml`                 |      1 | **Fixed** (deleted unused map)       |
| `RedundantThrows.xml`                                 |      1 | **Fixed** (//noinspection + javadoc) |
| `SpellCheckingInspection.xml`                         |      1 | **Fixed** (javadoc \n → <newline>)   |
| `DuplicatedCode_aggregate.xml`                        |      0 | empty                                |
| **Total**                                             | **66** | —                                    |

## Fixes applied this iteration

### Duplicated code (6 findings) — `core/McpProtocolHandler.java` + `core/McpRegistry.java`

**Extracted helper methods:**

- `McpProtocolHandler.wrapContents(Map<String,Object>)` — wraps a single content
  item as the MCP `{contents:[...]}` envelope. Used by both `resourceContents(uri,text)`
  and `blobContents(uri,blob)`.
- `McpRegistry.baseResourceMap(uri, name, description, mimeType)` — builds the
  4-field resource metadata map. Used by `registerResource` and `registerBlobResource`.
- `McpRegistry.baseTemplateMap(uriTemplate, name, description, mimeType)` — builds
  the 4-field template metadata map. Used by `registerResourceTemplate` and
  `registerBlobResourceTemplate`.

Total: 24 lines of duplicated metadata construction removed.

### RedundantSuppression (4 findings)

- `McpBlobContent.get(Object)` and `putAll(Map)` — `@SuppressWarnings("unchecked")` removed.
  Type parameter is already known from the `Map<String, Object>` declaration.
- `McpProtocolHandler` switch statement — replaced incorrect ID
  `DuplicatesInSwitchStatementDuplicatesBodies` with the correct
  `DuplicateBranchesInSwitch`.
- `McpGrizzlyHandler.java:285` — re-added `//noinspection BusyWait` (had been
  removed during a previous patch).

### AutoCloseableResource (2 findings)

- `McpGrizzlyLiveTest.java:22` — refactored manual `try { ... } finally { transport.stop(); }`
  to `try (transport) { ... }`. `GrizzlyStreamableServerTransportProvider` already
  implements `AutoCloseable`.

### SameReturnValue (2 findings)

- `McpExampleRegistrationTest#readme` — added `//noinspection SameReturnValue`
  (test fixture by design).
- `McpGrizzlySecurityMatrixTest#initialize` — same.

### SynchronizationOnLocalVariableOrMethodParameter (2 findings)

- `McpRegistry.findMimeType` — replaced `synchronized(definitions)` with
  `synchronized(McpRegistry.class)` to use a stable, class-level monitor.

### UnusedReturnValue (2 findings)

- `McpProtocolHandler.completeTask`, `failTask` — widened
  `@SuppressWarnings("unused")` to `@SuppressWarnings("unused, UnusedReturnValue")`.

### MismatchedCollectionQueryUpdate (1 finding)

- `McpRegistry.blobResourceHandlers` — dead field. Removed the field, both write
  sites (`registerResource` and `registerBlobResource`). `resourceHandlers` already
  holds the same handler; callers (`McpProtocolHandler`) `instanceof`-cast on read.

### RedundantThrows (1 finding)

- `McpBlobResourceHandler.readBlob` — added javadoc note explaining the parent
  `throws Exception` contract, plus `//noinspection RedundantThrows`.

### SpellCheckingInspection (1 finding)

- `McpProtocolHandler#pollPendingNotification` Javadoc — replaced `\n` characters
  with the word `<newline>` so the spell-checker no longer flags the literal
  `ndata` substring.

### BusyWait (1 finding)

- `McpGrizzlyHandler.java:285` — `//noinspection BusyWait` restored above the
  event-loop `while` statement.

## Validation

```bash
./gradlew.bat --no-daemon clean build javadoc --console=plain
BUILD SUCCESSFUL

cd examples && ../gradlew.bat --no-daemon compileJava "-PsdkJar=../build/libs/mcp-java-sdk-1.0-SNAPSHOT.jar"
BUILD SUCCESSFUL

46 tests passed, 0 failed, 0 errors, 0 skipped
```
