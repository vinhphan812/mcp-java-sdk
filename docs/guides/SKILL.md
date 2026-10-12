# MCP Skills Extension

The Skills extension allows servers to advertise and negotiate rich, structured functional capabilities, moving beyond simple tool registration.

## Overview

Unlike standard `tools`, Skills are negotiated using the `io.modelcontextprotocol/skills` extension identifier during handshake.

## Registration

Servers register skills using the `ExtensionRegistry`:

```java
ExtensionRegistry registry = new ExtensionRegistry();
registry.register(new SkillDefinition("data-analyze", ...));
```

## Negotiation

During `initialize`, the server advertises support for the skills extension:

```json
{
  "capabilities": {
    "extensions": {
      "io.modelcontextprotocol/skills": {
        "version": "1.0",
        "description": "Rich functional capabilities"
      }
    }
  }
}
```

## Dispatch

Skills are dispatched through standard `tools/call` procedures but are identified by registered skill namespaces rather than flat tool names.
