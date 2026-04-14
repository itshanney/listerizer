# Technical Design: Reading List REST API

**Date:** 2026-04-11
**Status:** Draft
**Stack:** Java 25, Spring Boot 4, Gradle, SQLite, port 5080
**Source Spec:** [features/2026-04-11-reading-list-rest-api.md](../features/2026-04-11-reading-list-rest-api.md)

---

## Overview

This is a single-process, single-user CRUD REST service built with **Java 25** and **Spring Boot 4**, backed by a SQLite database, listening on **port 5080**. 
* It exposes two HTTP endpoints — one to ingest reading list items and one to retrieve them — and is designed to run as a persistent process on a small Linux workstation. 
* No authentication, no external dependencies, no background workers. 
* Spring Boot is used for its embedded Tomcat server, `@RestController` routing, Jackson JSON serialization, and Spring Data JDBC for repository access; no part of the Spring ecosystem beyond these is introduced. 
* The service must tolerate `create_time` in either Unix epoch (seconds) or ISO 8601 format and normalize to Unix epoch (seconds) before storage.

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
│  Spring Boot 4 Application (Java 25, port 5080)           │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐  │
│  │  ItemController (@RestController)                   │  │
│  │  POST /items → ItemController.create                │  │
│  │  GET  /items → ItemController.list                  │  │
│  └──────────────────────┬──────────────────────────────┘  │
│                         │                                 │
│  ┌──────────────────────▼──────────────────────────────┐  │
│  │  ItemService (@Service)                             │  │
│  │  - Validates and normalizes input                   │  │
│  │  - Enforces idempotency on duplicate URLs           │  │
│  │  - Converts create_time to ISO 8601                 │  │
│  └──────────────────────┬──────────────────────────────┘  │
│                         │                                 │
│  ┌──────────────────────▼──────────────────────────────┐  │
│  │  ItemRepository (Spring Data JDBC)                  │  │
│  │  - Owns all SQL via JdbcTemplate or CrudRepository  │  │
│  │  - SQLite dialect; single file on local disk        │  │
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
| Embedded Tomcat (Spring Boot) | Accepts HTTP connections on port 5080; routes to `@RestController` |
| `ItemController` | Parses HTTP request via Jackson, calls `ItemService`, serializes HTTP response |
| `ItemService` | Validates input, normalizes `create_time`, enforces business rules |
| `ItemRepository` | Executes SQL against SQLite via Spring Data JDBC; no business logic |
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
2. Spring Boot routes `POST /items` → `ItemController.create`.
3. Jackson deserializes JSON into `ItemRequest` record; malformed JSON → Spring returns `400` automatically via `HttpMessageNotReadableException`.
4. Controller passes `ItemRequest` to `ItemService.create`.
5. Service validates `url` format (using `java.net.URI` or similar) → invalid → throws `ValidationException` → controller returns `400`.
6. Service parses `create_time`: if the JSON value is a number, treat as Unix epoch seconds and convert via `Instant.ofEpochSecond`; if a string, parse with `DateTimeFormatter.ISO_OFFSET_DATE_TIME`; failure → `400`.
7. Service calls `ItemRepository.insert(url, normalizedCreateTime)`.
8. Repository executes `INSERT OR IGNORE INTO items (url, create_time) VALUES (?, ?)` via `JdbcTemplate`.
   - New URL: row inserted; repository returns the new row via `last_insert_rowid()` SELECT.
   - Duplicate URL: `IGNORE` fires; repository detects zero rows affected (via `update()` return value), fetches existing row by URL, returns it.
9. Service returns `ItemResponse` record to controller.
10. Controller returns `ResponseEntity<ItemResponse>`:
    - New item → `201 Created`.
    - Existing item → `200 OK`.
11. Jackson serializes response; Tomcat sends to client.

### Path 2: Retrieve all items (happy path)

1. Client issues `GET /items`.
2. Spring Boot routes → `ItemController.list`.
3. Controller calls `ItemService.list`.
4. Service calls `ItemRepository.findAll`.
5. Repository executes `SELECT id, url, create_time FROM items ORDER BY id ASC` via `JdbcTemplate`.
6. Returns array (possibly empty).
7. Handler serializes to JSON → `200 OK`.

### Path 3: Store item — malformed `create_time`

1. Client POSTs `{"url": "https://example.com", "create_time": "not-a-date"}`.
2. Steps 1–5 pass.
3. At step 6, service cannot parse `"not-a-date"` as ISO 8601 or integer; throws `ValidationException`.
4. `@ExceptionHandler` in `ItemController` (or a `@ControllerAdvice`) catches `ValidationException`.
5. Controller returns `400 Bad Request` with `{"error": "invalid_request", "message": "create_time must be ISO 8601 or Unix epoch seconds"}`.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risks Accepted |
|---|---|---|---|---|
| **Persistence** | SQLite, Postgres, flat JSON file | SQLite | Single-user, single-host, zero ops overhead. File-based backup trivial. | Not suitable if concurrency or replication ever needed. |
| **Duplicate URL handling** | 409 Conflict, silent ignore, upsert timestamp | Return existing record (idempotent 200) | Spec says "idempotent and allowed." Returning the existing record gives the client confirmation of the stored state. | Client cannot update `create_time` on a duplicate without a DELETE+POST. Acceptable per spec scope. |
| **`create_time` normalization** | Store as-received, normalize to ISO 8601, store as Unix int | Normalize to ISO 8601 string at write time | Single canonical format in DB; no conversion logic on read. Spec requires human-readable ISO 8601 in responses. | Epoch integers lose their original format (irreversible), but the spec confirms server owns normalization. |
| **Language & framework** | Python/Flask, Go/Chi, Java/Spring Boot | Java 25 + Spring Boot 4 | Explicit technology choice by project owner. Spring Boot provides embedded Tomcat, Jackson, and Spring Data JDBC — covering routing, serialization, and data access with no additional glue code. | Spring Boot JARs are large (~20MB); startup time ~2–3s. Neither matters for a long-running workstation service. SQLite is not a first-class Spring Data dialect — requires `sqlite-jdbc` driver and manual `JdbcTemplate` usage rather than full JPA. |
| **Authentication** | API key, OAuth, none | None | Spec explicitly excludes auth in this version. | Service is unauthenticated — must only bind to localhost or a private network interface. |
| **Process model** | Single process, multi-process, containerized | Single process | Matches deployment target (small Linux workstation). SQLite write concurrency is sufficient for single-user load. | SQLite WAL mode should be enabled to allow concurrent reads during writes. |

---

## Operational Concerns

### Technology Stack
| Layer | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4 |
| HTTP server | Embedded Tomcat (included with Spring Boot) |
| JSON | Jackson (included with Spring Boot) |
| Data access | Spring Data JDBC + `JdbcTemplate` |
| SQLite driver | `org.xerial:sqlite-jdbc` |
| Build tool | Gradle |

### Startup & Deployment
- Service is packaged as a Spring Boot fat JAR (`gradle bootJar`) and launched as a systemd unit on the Linux workstation: `java -jar build/libs/listerizer-api.jar`.
- On first start, Spring Boot runs `schema.sql` from the classpath (via `spring.sql.init.mode=always`) to create the table: `CREATE TABLE IF NOT EXISTS items (...)`.
- Configurable via `application.properties` or environment variable overrides (Spring Boot convention):
  - `server.port=5080` (default; override with `SERVER_PORT` env var)
  - `spring.datasource.url=jdbc:sqlite:/home/user/listerizer.db` (override with env var)

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
