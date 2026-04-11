# Technical Design: Reading List REST API

**Date:** 2026-04-11
**Status:** Draft
**Source Spec:** [features/2026-04-11-reading-list-rest-api.md](../features/2026-04-11-reading-list-rest-api.md)

---

## Overview

This is a single-process, single-user CRUD REST service backed by a SQLite database. It exposes two HTTP endpoints — one to ingest reading list items and one to retrieve them — and is designed to run as a persistent process on a small Linux workstation. No authentication, no external dependencies, no background workers. The design is intentionally minimal: the scope does not justify additional infrastructure, and SQLite is the correct persistence layer for this scale and deployment target. The service must tolerate `create_time` in either Unix epoch (seconds) or ISO 8601 format and normalize to ISO 8601 before storage.

---

## Component Diagram

```
┌───────────────────────────────────────────────────────────┐
│  Chrome Extension / Apps Script / curl                    │
│  (HTTP clients — any JSON-capable HTTP client)            │
└────────────────────────┬──────────────────────────────────┘
                         │ HTTP/JSON
                         ▼
┌───────────────────────────────────────────────────────────┐
│  HTTP Server (single process)                             │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐  │
│  │  Router                                             │  │
│  │  POST /items → ItemHandler.create                   │  │
│  │  GET  /items → ItemHandler.list                     │  │
│  └──────────────────────┬──────────────────────────────┘  │
│                         │                                 │
│  ┌──────────────────────▼──────────────────────────────┐  │
│  │  ItemService                                        │  │
│  │  - Validates and normalizes input                   │  │
│  │  - Enforces idempotency on duplicate URLs           │  │
│  │  - Converts create_time to ISO 8601                 │  │
│  └──────────────────────┬──────────────────────────────┘  │
│                         │                                 │
│  ┌──────────────────────▼──────────────────────────────┐  │
│  │  Repository (SQLite via driver)                     │  │
│  │  - Owns all SQL                                     │  │
│  │  - Single file on local disk                        │  │
│  └──────────────────────┬──────────────────────────────┘  │
└────────────────────────┬──────────────────────────────────┘
                         │
                         ▼
              ┌──────────────────────┐
              │  SQLite file         │
              │  ~/listerizer.db     │
              └──────────────────────┘
```

**Component responsibilities:**

| Component | Responsibility |
|---|---|
| Router | Maps HTTP method + path to handler; returns 404 for unknown routes |
| ItemHandler | Parses HTTP request, calls ItemService, serializes HTTP response |
| ItemService | Validates input, normalizes `create_time`, enforces business rules |
| Repository | Executes SQL against SQLite; no business logic |
| SQLite file | Single source of truth for all reading list items |

---

## Data Model

### Table: `items`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | Server-assigned; never reused |
| `url` | TEXT | NOT NULL, UNIQUE | The reading list URL |
| `create_time` | TEXT | NOT NULL | ISO 8601, normalized at write time |

**Notes:**
- `UNIQUE` on `url` enforces idempotency at the database layer. A duplicate `INSERT` is handled by `INSERT OR IGNORE` (see Tradeoffs), not by returning a `409`.
- No `updated_at` or soft-delete columns — out of scope.
- No FTS index needed — retrieval is full-table scan returning all items.

---

## API Contracts

### `POST /items`

**Request**
```
Content-Type: application/json

{
  "url": "https://example.com/article",
  "create_time": "2026-04-11T10:30:00Z"   // ISO 8601 or Unix epoch seconds (integer)
}
```

**Response — 201 Created**
```json
{
  "id": 42,
  "url": "https://example.com/article",
  "create_time": "2026-04-11T10:30:00Z"
}
```

**Response — 200 OK (duplicate URL — idempotent)**
```json
{
  "id": 17,
  "url": "https://example.com/article",
  "create_time": "2026-03-01T08:00:00Z"
}
```
> Returns the existing record unchanged. The `create_time` in the response reflects the **original** stored value, not the value in the request. This is the defined idempotency contract.

**Response — 400 Bad Request**
```json
{
  "error": "invalid_request",
  "message": "url is required and must be a valid URL"
}
```

**Validation rules:**
- `url`: required; must be a syntactically valid URL (scheme + host at minimum).
- `create_time`: required; must parse as either ISO 8601 or an integer (Unix epoch seconds ≥ 0). Invalid string formats return `400`.
- Malformed JSON body → `400`.

---

### `GET /items`

**Response — 200 OK**
```json
[
  {
    "id": 1,
    "url": "https://example.com/article",
    "create_time": "2026-04-11T10:30:00Z"
  }
]
```
- Returns `[]` when no items exist.
- All `create_time` values are ISO 8601.
- No filtering, sorting, or pagination in this version. Order is by `id ASC` (insertion order).

---

## Critical Path Walkthrough

### Path 1: Store a new reading list item (happy path)

1. Chrome extension POSTs JSON body to `POST /items`.
2. Router matches `POST /items` → `ItemHandler.create`.
3. Handler parses JSON; missing/malformed body → `400` immediately.
4. Handler passes `{url, create_time}` to `ItemService.create`.
5. Service validates `url` format → invalid → `400`.
6. Service parses `create_time`: if integer, treat as Unix epoch and convert to ISO 8601; if string, validate ISO 8601; invalid → `400`.
7. Service calls `Repository.insert(url, normalizedCreateTime)`.
8. Repository executes `INSERT OR IGNORE INTO items (url, create_time) VALUES (?, ?)`.
   - New URL: row inserted; repository returns `{id, url, create_time}` via `SELECT` on `last_insert_rowid()`.
   - Duplicate URL: `IGNORE` fires; repository detects zero rows affected, fetches existing row by URL, returns it.
9. Service returns result to handler.
10. Handler serializes to JSON:
    - New item → `201 Created`.
    - Existing item → `200 OK`.
11. Response sent.

### Path 2: Retrieve all items (happy path)

1. Client issues `GET /items`.
2. Router → `ItemHandler.list`.
3. Handler calls `ItemService.list`.
4. Service calls `Repository.findAll`.
5. Repository executes `SELECT id, url, create_time FROM items ORDER BY id ASC`.
6. Returns array (possibly empty).
7. Handler serializes to JSON → `200 OK`.

### Path 3: Store item — malformed `create_time`

1. Client POSTs `{"url": "https://example.com", "create_time": "not-a-date"}`.
2. Steps 1–5 pass.
3. At step 6, service cannot parse `"not-a-date"` as ISO 8601 or integer.
4. Service returns validation error.
5. Handler returns `400 Bad Request` with `{"error": "invalid_request", "message": "create_time must be ISO 8601 or Unix epoch seconds"}`.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risks Accepted |
|---|---|---|---|---|
| **Persistence** | SQLite, Postgres, flat JSON file | SQLite | Single-user, single-host, zero ops overhead. File-based backup trivial. | Not suitable if concurrency or replication ever needed. |
| **Duplicate URL handling** | 409 Conflict, silent ignore, upsert timestamp | Return existing record (idempotent 200) | Spec says "idempotent and allowed." Returning the existing record gives the client confirmation of the stored state. | Client cannot update `create_time` on a duplicate without a DELETE+POST. Acceptable per spec scope. |
| **`create_time` normalization** | Store as-received, normalize to ISO 8601, store as Unix int | Normalize to ISO 8601 string at write time | Single canonical format in DB; no conversion logic on read. Spec requires human-readable ISO 8601 in responses. | Epoch integers lose their original format (irreversible), but the spec confirms server owns normalization. |
| **HTTP framework** | Raw stdlib, lightweight framework (e.g., Express, Flask, Chi) | Lightweight framework | Reduces boilerplate for routing, JSON parsing, error middleware without introducing complexity. Specific choice is implementation detail. | Minimal; any widely-used framework is fine for this scale. |
| **Authentication** | API key, OAuth, none | None | Spec explicitly excludes auth in this version. | Service is unauthenticated — must only bind to localhost or a private network interface. |
| **Process model** | Single process, multi-process, containerized | Single process | Matches deployment target (small Linux workstation). SQLite write concurrency is sufficient for single-user load. | SQLite WAL mode should be enabled to allow concurrent reads during writes. |

---

## Operational Concerns

### Startup & Deployment
- Service is a single binary or script launched as a systemd unit (or equivalent) on the Linux workstation.
- On first start, the service creates the SQLite database file and runs schema migrations (CREATE TABLE IF NOT EXISTS).
- Configurable via environment variables (at minimum): `PORT` (default 8080), `DB_PATH` (default `~/listerizer.db`).

### Failure Modes
| Failure | Behavior |
|---|---|
| SQLite file missing on start | Service creates it; not an error |
| SQLite file corrupt | Service fails to start with a clear error message; manual recovery required |
| Disk full on write | Repository returns error → handler returns `500 Internal Server Error` |
| Process crash | systemd restarts; SQLite transactions mean no partial writes |
| Port already in use | Process exits with error message; check systemd logs |

### Monitoring
- At this scale: structured log lines per request (`method`, `path`, `status`, `duration_ms`) written to stdout, captured by systemd journal.
- No external monitoring required for v1. If needed later, expose `GET /health` → `200 OK {"status":"ok"}`.

### Backup
- SQLite database is a single file. Backup = copy the file. A cron job running `sqlite3 listerizer.db ".backup listerizer.db.bak"` daily is sufficient.

### Capacity
- Single-user reading list. Expect hundreds to low thousands of items. SQLite handles millions of rows; this is not a capacity concern.

---

## Out of Scope / Future Work

- **Authentication**: Intentionally deferred. If the service ever leaves localhost, an API key header (`X-API-Key`) is the natural next step — no redesign required, just a middleware layer.
- **Delete / update endpoints**: No spec requirement. Adding `DELETE /items/{id}` is additive and does not affect this design.
- **Read/unread status**: A future `read_at` column (nullable timestamp) would be additive.
- **Pagination**: Not needed at current scale. `LIMIT`/`OFFSET` on `GET /items` is the natural extension.
- **Apps Script integration**: The Apps Script replacing its Google Drive fetch with `GET /items` is a downstream change covered by a separate spec.
- **Chrome extension update**: Wiring `export.js` to POST to this service instead of local export is a separate implementation task.
