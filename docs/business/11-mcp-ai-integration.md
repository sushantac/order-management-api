# 11. AI Integration via MCP (Model Context Protocol)

> **Bonus PR #36** — the Order Management API learns to be *used by an AI
> assistant*. This document is the business-facing guide: what MCP is, what the
> server exposes, and what an assistant may (and may not) do.

## What this adds

MCP (**Model Context Protocol**) is an open standard (Anthropic, 2024) that
lets an AI assistant discover and call *tools* on a server, the same way a
human calls a REST API. Instead of teaching the assistant a bespoke prompt +
JSON convention, you point it at an MCP server and it reads `tools/list` to
find out what it can do.

This API now ships a small MCP server on top of the existing REST API:

```
POST /mcp
Content-Type: application/json          # JSON-RPC 2.0 over HTTP
Authorization: Bearer <JWT>  (or X-API-Key)   # same security as the REST API
```

It implements the **JSON-only mode** of the MCP *Streamable HTTP* transport:
one `POST`, one JSON-RPC exchange, no Server-Sent Events. Supported methods:
`initialize`, `ping`, `tools/list`, `tools/call`, and `notifications/*`.

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
curl -s http://localhost:8080/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
       "params":{"protocolVersion":"2024-11-05","capabilities":{},
                 "clientInfo":{"name":"curl"}}}'

# 2. What can I do?
curl -s http://localhost:8080/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 3. Call a tool
curl -s http://localhost:8080/mcp -H 'Content-Type: application/json' \
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
- **Hand-rolled transport, by design.** The pinned Spring Boot 3.2 line predates
  the official MCP Spring starters (which need Spring 6.2+). This PR implements
  the JSON-RPC subset directly (~200 lines, fully tested). Production upgrade
  path: run the official `mcp-spring-*` SDK on a supported Boot line behind the
  same tool abstraction — the JSON-RPC surface and tool contracts stay the same.

## Tests

`McpControllerIntegrationTest` (10 tests, real Postgres via Testcontainers):
handshake, `tools/list`, catalogue search, `order_status` PII guarantee,
unknown order / invalid arguments as tool errors, JSON-RPC `-32601`/`-32602`
errors, notifications and batch requests.
