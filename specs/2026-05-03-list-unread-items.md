# Tech Spec: List Unread Items — GET /items/unread

**Date:** 2026-05-03
**Status:** Draft

---

## Overview

This is a single filtered-collection read endpoint. It follows the exact same three-layer pattern as `GET /items`: one repository method, one service method, one controller method. No new infrastructure, no new exception mappers, no schema changes. The only decision worth documenting is the empty-collection response shape — `[]` rather than `404` — which is consistent with `GET /items` and intentionally different from `GET /items/unread/random` (a single-item resource, where "nothing found" means no resource exists).

---

## Component Diagram

```
GET /items/unread
      │
      ▼
ItemController  (@GET @Path("/unread"))
  │  maps List<Item> → List<ItemResponse>
  ▼
ItemService.listUnread()
  │  delegates, no business logic
  ▼
ItemRepository.findAllByHasBeenReadFalseOrderByIdAsc()
  │  Spring Data derived query → WHERE has_been_read = 0 ORDER BY id ASC
  ▼
MySQL (items table)
```

**Responsibilities:**
- `ItemController` — HTTP surface: invoke service, map domain objects to response records, serialize to JSON
- `ItemService` — thin delegation; no validation required (no input parameters)
- `ItemRepository` — derived query method; no native SQL needed

---

## Data Model

No schema changes. Reads from the existing `items` table, filtering on the existing `has_been_read` column.

Relevant columns: `id`, `url`, `create_time`, `title`, `has_been_read`.

---

## API Contracts

### GET /items/unread

**Request:** No body, no query parameters.

**Response — 200 OK (zero or more unread items):**
```json
[
  {
    "id": 3,
    "url": "https://example.com/article",
    "createTime": 1744367400,
    "title": "Why Boring Technology Wins",
    "hasBeenRead": false
  }
]
```

Returns `[]` when no unread items exist. Never returns `404` — an empty collection is a valid result.

Every item in the array has `hasBeenRead: false`. Items are ordered by `id` ascending (insertion order), consistent with `GET /items`.

**Error codes:** None beyond standard infrastructure failures (500). There are no invalid input states for this endpoint.

---

## Critical Path Walkthrough

### Path 1 — Unread items exist

1. `GET /items/unread` arrives at `ItemController.listUnread()`.
2. Controller calls `itemService.listUnread()`.
3. Service calls `repository.findAllByHasBeenReadFalseOrderByIdAsc()`.
4. Spring Data generates: `SELECT * FROM items WHERE has_been_read = 0 ORDER BY id ASC`.
5. MySQL returns matching rows.
6. Controller maps each `Item` to `ItemResponse` via the existing `toResponse()` helper.
7. Jersey serializes the list to JSON. Returns `200 OK`.

### Path 2 — No unread items exist

Steps 1–4 identical. MySQL returns an empty result set. Controller maps to an empty list. Returns `200 OK` with body `[]`.

---

## File-by-File Change Summary

| File | Change |
|---|---|
| `ItemRepository.java` | Add `findAllByHasBeenReadFalseOrderByIdAsc()` — Spring Data derived query, no annotation needed |
| `ItemService.java` | Add `listUnread()` — delegates to repository, returns `List<Item>` |
| `ItemController.java` | Add `@GET @Path("/unread")` method — calls `listUnread()`, maps and returns `List<ItemResponse>` |

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|---|---|---|---|---|
| Empty result response | `[]` (200 OK) vs `404 Not Found` | **`[]` with 200** | Consistent with `GET /items`; a collection endpoint returning empty is valid, not an error. `GET /items/unread/random` returns 404 because it returns a single resource — "nothing found" is meaningfully different there. | Callers must check array length rather than status code to detect the empty case |
| Repository method style | Spring Data derived query vs `@Query` native SQL | **Derived query** | `findAllByHasBeenReadFalseOrderByIdAsc()` expresses the intent without raw SQL; Spring Data generates the equivalent of the native query. Native SQL reserved for things derived queries can't express (e.g., `ORDER BY RAND()`). | Spring Data naming convention must be followed exactly; a typo silently produces a wrong query |
| Path | `GET /items/unread` vs `GET /items?hasBeenRead=false` | **Dedicated path** | Consistent with the existing `GET /items/unread/random` sub-path. Query parameters imply a general filter system that doesn't exist and isn't planned. | Path proliferation if more filter combinations are added later |

---

## Operational Concerns

**Deployment:** Additive-only change. No migration, no configuration change, no feature flag. Deploy as part of the next release.

**Rollback:** Removing the three new lines of code fully reverts the feature. No stored state to clean up.

**Monitoring:** No new metrics needed. Watch the existing `GET /items` error rate for any unexpected increase if this endpoint is placed behind a shared rate limiter in the future.

**Capacity:** Query adds a WHERE clause to a full table scan (no index on `has_been_read`). At personal reading list scale (hundreds of rows), this is negligible. If the table grows to tens of thousands of rows, an index on `has_been_read` would be worth adding — that is a future concern.

---

## Out of Scope / Future Work

- Filtering `GET /items` by `hasBeenRead` via query parameter — not needed while the dedicated path serves all current consumers.
- Pagination — not needed at current scale; `GET /items` is also unpaginated.
- An index on `has_been_read` — deferred until row count justifies it.
