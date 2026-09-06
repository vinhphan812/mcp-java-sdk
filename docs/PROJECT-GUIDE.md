# MCP Java SDK — Project Guide

## 1. Mục tiêu

`mcp-java-sdk` là một MCP server SDK độc lập, portable, target Java 8. SDK được tách khỏi Android, Cruzr và các application service riêng của dự án gốc.

Implementation baseline là MCP `2025-11-25`. Tài liệu MCP `2026-07-28` chỉ được dùng làm tài liệu so sánh; SDK không tuyên bố hỗ trợ đầy đủ phiên bản này.

SDK hiện cung cấp một subset thực dụng gồm:

- JSON-RPC 2.0 request/notification dispatch;
- MCP initialize và protocol-version validation;
- tools, resources, resource templates và prompts;
- Grizzly Streamable HTTP-style transport;
- session header và lifecycle cơ bản;
- reflection registration bằng annotations;
- Java 8-compatible server bootstrap.

SDK không phải full MCP implementation. Các tính năng chưa có được liệt kê ở mục 8.

## 2. Cấu trúc thư mục

```text
src/main/java/io/github/vinhphan812/mcp/
├── annotations/       Annotation declarations
├── api/                Public configuration, handlers, registrar interfaces
├── core/               Registry, protocol dispatch, server facade
└── transport/          Grizzly HTTP adapter and lifecycle provider

src/test/java/io/github/vinhphan812/mcp/
└── Behavior and live HTTP tests

examples/src/main/java/.../examples/
└── GrizzlyExample.java

docs/
├── PROJECT-GUIDE.md
├── GRIZZLY-EXAMPLE.md
├── MCP-PORTING-PLAN.md
├── MCP-COMPATIBILITY-2026.md
└── audits/
    └── 2026-09-01-full-source-audit.md
```

## 3. Runtime và dependency

- Java source/target: 8
- Build tool: Gradle Wrapper
- Gson: `2.11.0`
- Grizzly HTTP server: `4.0.2`
- Default bind address: `127.0.0.1`
- Default port: `3011`
- Default endpoint: `/mcp`

Không đưa credentials, API keys, tokens, passwords hoặc connection strings vào source, test, example hay tài liệu. Khi cần mô tả giá trị nhạy cảm, dùng `[REDACTED]`.

## 4. Luồng khởi động server

```text
McpServerConfig
      ↓
McpServer.Builder
      ↓
McpServer
  ├── McpRegistry
  ├── McpProtocolHandler
  └── GrizzlyStreamableServerTransportProvider
          ↓
      McpGrizzlyHandler
```

Các provider được đăng ký trước `start()`:

```java
McpServer server = McpServer.builder()
        .config(McpServerConfig.builder()
                .serverName("my-server")
                .serverVersion("1.0.0")
                .protocolVersion("2025-11-25")
                .tools(true)
                .resources(true)
                .prompts(true)
                .build())
        .host("127.0.0.1")
        .port(3011)
        .endpoint("/mcp")
        .build()
        .register(new MyTools())
        .register(new MyResources());

server.start();
System.out.println(server.getUrl());
// shutdown: server.close()
```

Có thể dùng `port(0)` trong test để yêu cầu hệ điều hành cấp ephemeral port. Sau khi start, lấy port thực tế qua `server.getTransport().getActualPort()`.

Lifecycle:

- `register(...)`: đăng ký provider trước khi start;
- `registerAll(...)`: đăng ký nhiều provider theo thứ tự;
- `start()`: khởi động Grizzly;
- `isRunning()`: kiểm tra trạng thái;
- `getUrl()`: lấy endpoint khi server đang chạy;
- `stop()`/`close()`: dừng server và đóng sessions.

## 5. Đăng ký capability

Reflection registrar đọc các class annotations:

- `@Tools` + `@McpTool`;
- `@Resources` + `@McpResource`;
- `@Resources` + `@McpResourceTemplate`;
- `@Prompts` + `@McpPrompt`;
- `@McpParam` cho metadata argument/schema.

Contract hiện tại:

- tool method trả `Map<String, Object>`;
- prompt method trả `Map<String, Object>`;
- resource/resource-template method trả `String`;
- method nhận zero hoặc một compatible argument;
- tool/prompt argument thường là `Map<String, Object>`;
- resource argument thường là URI `String`.

Registrar kiểm tra return type và ném `IllegalArgumentException` nếu method vi phạm contract. Với production lớn, nên cân nhắc generated registrar thay cho reflection để giảm startup cost và tăng tính deterministic.

## 6. Protocol flow

Client gửi `initialize` với JSON-RPC 2.0:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-11-25",
    "capabilities": {},
    "clientInfo": {"name": "client", "version": "1.0.0"}
  }
}
```

Sau đó client gửi notification:

```json
{"jsonrpc":"2.0","method":"notifications/initialized"}
```

Notification không có `id` và không nhận JSON-RPC response body. Các request thông thường có `id` và nhận response hoặc error.

Các method chính:

- `initialize`;
- `tools/list`, `tools/call`;
- `resources/list`, `resources/read`;
- `resources/templates/list`;
- `prompts/list`, `prompts/get`.

Resource template không dùng route custom. Client resolve URI, ví dụ `demo://users/42`, rồi gọi `resources/read`.

Lỗi giao thức dùng JSON-RPC error, gồm invalid request/params và method hoặc resource không tồn tại. SDK không coi lỗi nghiệp vụ là success result chứa chuỗi lỗi.

## 7. HTTP transport

Endpoint xử lý:

- `POST /mcp`: JSON-RPC request hoặc notification;
- `GET /mcp`: session-bound event stream cơ bản;
- `DELETE /mcp`: kết thúc session.

Header liên quan:

- POST cần `Content-Type: application/json`;
- `Accept` cần phù hợp với `application/json` và/hoặc `text/event-stream`;
- protocol header: `Mcp-Protocol-Version: 2025-11-25`;
- session header: `Mcp-Session-Id` sau khi session được cấp;
- optional authentication: `Authorization: Bearer [REDACTED]`.

Security defaults:

- example bind loopback;
- Origin không xác định/không được allow sẽ bị từ chối;
- Bearer token được so sánh constant-time;
- secret do `Supplier<String>` cung cấp và không được ghi ra log/source.

Production deployment phải bổ sung reverse proxy/request limit, TLS, origin allowlist phù hợp và secret provider ngoài source control.

## 8. Phạm vi chưa hỗ trợ

Các phần sau chưa được implement hoặc chưa được chứng minh đầy đủ:

- pagination;
- `Last-Event-ID` và replay/resumable SSE;
- binary resource `blob`;
- completion;
- logging;
- progress/cancellation;
- sampling;
- elicitation;
- tasks;
- STDIO transport;
- richer tool annotations/output schemas;
- asynchronous API;
- full event-stream response semantics cho mọi POST negotiation.

Không mô tả SDK là full MCP parity khi các giới hạn này còn tồn tại.

## 9. Build, test và example

Từ project root:

```bash
./gradlew clean test build --console=plain
```

Test hiện bao phủ:

- configuration defaults/validation;
- JSON-RPC dispatch và error/notification behavior;
- annotation registration;
- in-process Grizzly HTTP smoke path.

Example hiện chưa được khai báo thành Gradle source set/application task riêng. Chạy example cần compile/run với classpath Gradle phù hợp hoặc bổ sung task build riêng trong phạm vi khác.

Hướng dẫn request chi tiết nằm trong `docs/GRIZZLY-EXAMPLE.md`.

## 10. Audit và release checklist

Trước khi phát hành:

1. chạy `./gradlew clean test build --console=plain`;
2. kiểm tra test reports và exit code;
3. chạy static search loại Android/application imports;
4. kiểm tra không có secret/credential thật;
5. review `docs/audits/2026-09-01-full-source-audit.md` và cập nhật ngày/phạm vi;
6. thêm project license và dependency notices/SBOM — hiện là gap đã ghi nhận;
7. kiểm tra origin, TLS, body-size limit, authentication và reverse proxy;
8. xác nhận client interoperability bằng runtime HTTP probes.

## 11. Tài liệu tham chiếu

- `README.md`: quick overview;
- `docs/GRIZZLY-EXAMPLE.md`: transport/example walkthrough;
- `docs/MCP-COMPATIBILITY-2026.md`: compatibility matrix và remediation history;
- `docs/MCP-PORTING-PLAN.md`: portable extraction plan;
- `docs/IMPLEMENTATION-STATUS.md`: current completed, incomplete, and unverified areas.
- `docs/audits/2026-09-01-full-source-audit.md`: full source audit.
