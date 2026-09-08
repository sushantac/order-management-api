# 11. AI Integration via MCP (Model Context Protocol)

> **Bonus PR #36 (transport re-implemented on the official SDK in PR #37)** — the
> Order Management API learns to be *used by an AI assistant*. This document is
> the business-facing guide: what MCP is, what the server exposes, and what an
> assistant may (and may not) do.

## What this adds

MCP (**Model Context Protocol**) is an open standard (Anthropic, 2024) that
lets an AI assistant discover and call *tools* on a server, the same way a
human calls a REST API. Instead of teaching the assistant a bespoke prompt +
JSON convention, you point it at an MCP server and it reads `tools/list` to
find out what it can do.

This API ships an MCP server on top of the existing REST API, built on the
**official Model Context Protocol Java SDK** (`mcp-spring-webmvc` stateless
transport; Boot was upgraded to 3.4.1/Spring 6.2.1 for it):

```
POST /mcp
Content-Type: application/json
Accept: application/json, text/event-stream   # MCP Streamable-HTTP requires both
Authorization: Bearer <JWT>  (or X-API-Key)   # same security as the REST API
```

The transport is the MCP **Streamable HTTP** transport in its stateless (no
session) mode: one `POST`, one JSON-RPC request (or notification) per call, no
Server-Sent Events, no JSON-RPC batches. The SDK handles the protocol:
`initialize`, `ping`, `tools/list`, `tools/call`, `notifications/*`.

## What the assistant can do (tools)

All tools are **read-only by design**. An assistant can look at the catalogue
and at order *status*; it cannot create, change or cancel anything.

| Tool | Purpose | Arguments | PII |
|---|---|---|---|
| `api_health` | Confirm the API is up (no DB touched) | none | none |
| `product_search` | Search the catalogue by name | `query` (optional), `maxResults` (default 10, max 50) | none |
| `order_status` | Public facts of one order | `orderId` (required) | deliberately none |

### The PII boundary in `order_status`

An order belongs to a customer whose email/name is **personal data**. The tool
returns only *order facts* — order id, order number, status, total, date — and
never touches the customer association. If a client needs customer data it
calls the human-facing REST endpoints (`GET /api/v1/orders/{id}`), which are
subject to the masking/GDPR rules in
[06-privacy-gdpr-and-pii.md](06-privacy-gdpr-and-pii.md). The tool signature
makes accidental leakage structurally impossible.

## Trying it

```bash
# 1. Handshake
curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
       "params":{"protocolVersion":"2024-11-05","capabilities":{},
                 "clientInfo":{"name":"curl"}}}'

# 2. What can I do?
curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 3. Call a tool
curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call",
       "params":{"name":"product_search",
                 "arguments":{"query":"widget","maxResults":5}}}'
```

When `app.security.enabled=true`, every request must present a bearer JWT or
`X-API-Key`, exactly like the REST endpoints.

## Decisions and honest limits

- **Only tools, no resources/prompts.** A subset is a feature: the surface an
  assistant can reach is small and reviewed.
- **Write tools are out of scope.** A future guarded write tool would require
  confirmation + side-effect discipline (outbox, idempotency keys, audit) —
  see the order-placement stack in `01-orders.md`.
- **Official SDK, honest limits.** PR #36 first shipped a hand-rolled JSON-RPC
  subset (the repo was on Spring Boot 3.2, before the MCP Spring modules which
  need Spring 6.2+). PR #37 upgraded Boot to 3.4.1 and re-implemented the same
  server on the **official `mcp-spring-webmvc` SDK**, keeping the same tools.
  The SDK's stateless transport is strict about the Streamable-HTTP contract:
  requests need `Accept: application/json, text/event-stream`, there are no
  JSON-RPC batches, and notifications are acknowledged with HTTP 202.
- **What we still do not enable:** resources/prompts, sessions/SSE, and JSON-RPC
  batches. Claude Desktop-style clients usually want sessions + SSE; if a
  sessionful integration is needed, swap the stateless transport for the SDK's
  `WebMvcStreamableServerTransportProvider` — the tool beans stay unchanged.

## Tests

`McpServerSdkIntegrationTest` (7 tests): the app runs on a **real Tomcat port**
and is exercised end-to-end with the **official MCP Java client** over the real
Streamable-HTTP transport against real Postgres (Testcontainers). It verifies
the handshake + `tools/list` contract and schemas, catalogue search,
`order_status` PII guarantee, unknown-order and invalid-argument tool errors,
`api_health`, and the transport's Accept-header + notification semantics.
