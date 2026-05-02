# Tech Spec: API Schema Extension — title, hasBeenRead, Upsert

**Date:** 2026-05-02
**Status:** Draft
**Implements:** [features/2026-05-02-api-schema-extension.md](../features/2026-05-02-api-schema-extension.md)

> **Codebase correction:** CLAUDE.md describes SQLite + JdbcTemplate. The actual stack is MySQL 9 + Spring Data JPA + Flyway. This spec reflects the real codebase.

---

## Overview

This is a CRUD extension to an existing single-service REST API. No new services, queues, or infrastructure are introduced. The changes span five layers — DB migration, JPA entity, request/response records, service logic, and integration tests — all in a single coordinated release. The only architectural decision with non-trivial impact is how to handle the selective upsert on duplicate URL: the existing try/catch-on-DataIntegrityViolationException pattern is preserved and extended with a conditional `repository.save()` update, rather than replaced with a raw MySQL `ON DUPLICATE KEY UPDATE` statement. See Tradeoff Log for rationale.

The `create_time` → `createTime` JSON rename is a breaking change. The one known affected caller outside this repo is the Chrome extension `export.js`, which must be updated in the same release window.

---

## Component Diagram

```
Chrome Extension (export.js)
  │  POST /items {url, createTime?, title?, hasBeenRead?}
  ▼
ItemController  (JAX-RS, /items)
  │  validates content-type, deserializes via Jackson
  ▼
ItemService
  │  validates url + optional createTime; resolves createTime to System.currentTimeMillis()/1000 if null
  │  on duplicate: reads existing row, applies ratchet logic, conditionally updates
  ▼
ItemRepository  (Spring Data CrudRepository<Item, Long>)
  │  save() → INSERT on new entity; merge() → UPDATE on existing entity with ID
  ▼
MySQL (items table)
```

**Each component's responsibility:**
- `ItemController` — HTTP surface: deserialize request, serialize response, map InsertResult → HTTP status
- `ItemService` — Business rules: URL validation, createTime defaulting, hasBeenRead ratchet, title fill-blank
- `ItemRepository` — Persistence: INSERT new rows, UPDATE existing rows, query by URL or ordered by ID
- `Item` — JPA entity: maps to `items` table; holds all persisted fields; minimally mutable (setters for `title` and `hasBeenRead` only)

---

## Data Model

### `items` table — after migration

| Column         | Type              | Nullable | Default | Notes                          |
|----------------|-------------------|----------|---------|--------------------------------|
| `id`           | BIGINT AUTO_INCREMENT | No   | —       | PK                             |
| `url`          | VARCHAR(2048)     | No       | —       | Unique key on first 255 chars  |
| `create_time`  | BIGINT            | No       | —       | Unix epoch seconds             |
| `title`        | VARCHAR(1024)     | Yes      | NULL    | New in V2                      |
| `has_been_read`| TINYINT(1)        | No       | 0       | New in V2; 0=false, 1=true     |

### Flyway migration: `V2__add_title_and_has_been_read.sql`

```sql
ALTER TABLE items
  ADD COLUMN title VARCHAR(1024) NULL,
  ADD COLUMN has_been_read TINYINT(1) NOT NULL DEFAULT 0;
```

Additive only. Existing rows get `title = NULL` and `has_been_read = 0`. No backfill needed. Safe to run against a live table without locking concerns (MySQL online DDL for column additions).

---

## API Contracts

### POST /items

**Request body** (all fields except `url` are now optional):
```json
{
  "url": "https://example.com/article",
  "createTime": 1744367400,
  "title": "Why Boring Technology Wins",
  "hasBeenRead": false
}
```

**Rules:**
- `url` — required, must be a valid URL with scheme and host. Returns `400` if missing or invalid.
- `createTime` — optional. If absent or null, server sets to `Math.floor(System.currentTimeMillis() / 1000)` at request time. If present, must be a non-negative integer; returns `400` if negative.
- `title` — optional. Stored as-is; null/absent means stored as NULL.
- `hasBeenRead` — optional. Absent or null treated as `false`.

**Response — 201 Created (new URL):**
```json
{
  "id": 42,
  "url": "https://example.com/article",
  "createTime": 1744367400,
  "title": "Why Boring Technology Wins",
  "hasBeenRead": false
}
```

**Response — 200 OK (duplicate URL, post-upsert state):**
Same shape. `createTime` and `id` always reflect the original insert values. `title` and `hasBeenRead` reflect the state after applying upsert rules.

**Error — 400 Bad Request:**
```json
{
  "error": "invalid_request",
  "message": "url is required and must be a valid URL"
}
```

---

### GET /items

No change to request. Response items now include `title` and `hasBeenRead`, and `create_time` is renamed to `createTime`:

```json
[
  {
    "id": 1,
    "url": "https://example.com/article",
    "createTime": 1744367400,
    "title": "Why Boring Technology Wins",
    "hasBeenRead": true
  }
]
```

Items with no title stored return `"title": null`. Items with `has_been_read = 0` return `"hasBeenRead": false`.

---

## Critical Path Walkthrough

### Path 1 — New item insert

1. Chrome extension POSTs `{url, createTime, title, hasBeenRead: false}`.
2. Jackson deserializes to `ItemRequest`. Unknown fields silently ignored.
3. `ItemService.validateRequest()`: checks url is valid, createTime is ≥ 0 (or null → resolved to now).
4. `ItemService.create()` calls `repository.save(new Item(url, resolvedCreateTime, title, false))`.
5. Hibernate issues `INSERT INTO items (url, create_time, title, has_been_read) VALUES (...)`.
6. MySQL inserts row; JPA returns entity with generated `id`.
7. `InsertResult(item, isNew=true)` → `ItemController` returns `201 Created`.

### Path 2 — Duplicate URL, upgrading hasBeenRead

1. GAS script POSTs `{url: "https://example.com", hasBeenRead: true}` (no title, no createTime).
2. `validateRequest`: url valid, createTime null → resolved to server time (but won't be stored since it's a dup).
3. `ItemService.create()` → `repository.save(new Item(...))` → Hibernate INSERT attempt.
4. MySQL unique constraint fires → Spring throws `DataIntegrityViolationException`.
5. Catch block: `repository.findByUrl(url)` → returns existing `Item` (id=7, hasBeenRead=false, title="Some Title").
6. `request.hasBeenRead()` is `true` AND `existing.hasBeenRead()` is `false` → `existing.setHasBeenRead(true)`.
7. `request.title()` is null → skip title update.
8. `needsUpdate = true` → `repository.save(existing)` → Hibernate calls `entityManager.merge()` → UPDATE.
9. Returns `InsertResult(updated, isNew=false)` → `200 OK` with post-upsert state.

### Path 3 — Duplicate URL, no-op (already read)

1. Chrome sync POSTs same URL again with `hasBeenRead: false`.
2. INSERT attempt → `DataIntegrityViolationException`.
3. Existing row has `hasBeenRead=true`.
4. `request.hasBeenRead()` is `false` → ratchet rule blocks downgrade → no field update.
5. `request.title()` is non-null and existing title is already set → no title update.
6. `needsUpdate = false` → skip `repository.save()`.
7. Returns `200 OK` with existing state unchanged (no DB write).

---

## File-by-File Change Summary

| File | Change |
|---|---|
| `V2__add_title_and_has_been_read.sql` | **New** — ALTER TABLE to add two columns |
| `Item.java` | Add `title` (String), `hasBeenRead` (boolean) fields + `@Column` annotations + setters for both |
| `ItemRequest.java` | Add `title` (String), `hasBeenRead` (Boolean) components; change `createTime` to `Long` nullable; remove `@JsonProperty("create_time")` |
| `ItemResponse.java` | Add `title` (String), `hasBeenRead` (boolean) components; remove `@JsonProperty("create_time")` |
| `ItemService.java` | Relax createTime validation (null → server time); add conditional UPDATE logic in duplicate catch block |
| `ItemController.java` | Update `toResponse()` to map `title` and `hasBeenRead` |
| `ItemApiIntegrationTest.java` | Rename `create_time` → `createTime` in all request bodies and response assertions; update `post_missing_create_time_returns_400` → expect 201; add tests for new fields and upsert behavior |
| `export.js` | Rename `create_time` → `createTime` in POST payload |

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|---|---|---|---|---|
| Selective upsert implementation | (A) Try/catch + conditional `repository.save()`; (B) MySQL `INSERT ... ON DUPLICATE KEY UPDATE` via JdbcTemplate | **A** | Consistent with existing code pattern; readable; TOCTOU window is benign (both ratchet operations are idempotent under concurrent writes) | Two DB round-trips on duplicate (INSERT attempt + SELECT + conditional UPDATE) vs. one for option B |
| `createTime` field naming | Keep `create_time` (snake_case); rename to `createTime` (camelCase) | **camelCase** | Product decision — consistent with `title`, `hasBeenRead`, and Chrome/GAS source field names; eliminates need for `@JsonProperty` annotations | Breaking change: all callers must update simultaneously |
| `createTime` when absent | Reject (400); default to 0; default to server time; allow null storage | **Server time** | Product decision — semantically correct for Sheets migration; avoids nullability in the DB schema | `createTime` for Sheets-migrated items reflects migration time, not original save time. Accepted. |
| `Item` entity mutability | Full immutability (new object on update); add setters only for mutable fields | **Setters for `title` and `hasBeenRead` only** | Minimal change to existing design; JPA merge works cleanly; avoids builder boilerplate | Entity is partially mutable; convention must be upheld by callers |
| `@JsonProperty` annotations | Remove (rely on Jackson defaults); keep explicitly | **Remove** | Jackson's default behavior for Java records serializes component names as-is (camelCase). No annotation needed. Fewer moving parts. | Relies on Jackson default config not being overridden elsewhere |

---

## Operational Concerns

**Deployment order:**
1. Run Flyway migration (additive DDL, safe on live table — no downtime).
2. Deploy new application jar (new field names take effect).
3. Update Chrome extension to use `createTime` in POST payload.

Steps 2 and 3 should happen in quick succession. The window between step 2 deploying and step 3 being loaded in Chrome is low-risk: the extension's `export.js` sends `create_time` (snake_case), which the new API will not recognize — `createTime` will default to server time. Items already in the API are unaffected. The Chrome extension is side-loaded and updated manually, so this window is brief.

**Rollback:**
- Application rollback: redeploy previous jar. Old code ignores the new columns (they have defaults; NOT NULL with DEFAULT 0 for `has_been_read` means old INSERTs that omit the column still succeed). Safe.
- DB rollback: `ALTER TABLE items DROP COLUMN title, DROP COLUMN has_been_read;` — only needed if the migration itself caused a problem, not needed for app rollback.

**Monitoring:** No new endpoints. Existing error rate metrics on `POST /items` and `GET /items` cover this. Watch for a spike in 400s immediately after deploy (would indicate `createTime` field rename is breaking a caller).

**Capacity:** Two new columns add ~9 bytes per row on average (4 for boolean + 1–255 for title). Negligible at current scale.

---

## Out of Scope / Future Work

- **`PATCH /items/{id}`** — a proper update endpoint would eliminate the upsert-via-POST pattern. Deferred; current callers don't need it.
- **Filtering `GET /items` by `hasBeenRead`** — needed once the GAS script reads from the API instead of the Sheet. Deferred to a follow-on spec.
- **`summary` field** — Gemini summaries are email-only today. A future spec will add a `summary` (TEXT) column and GAS write-back path.
- **CLAUDE.md correction** — The file incorrectly describes SQLite + JdbcTemplate + schema.sql. The real stack is MySQL + Spring Data JPA + Flyway. Should be updated separately.
