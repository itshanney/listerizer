# Random Unread Item Endpoint

**Date:** 2026-05-03
**Status:** Draft

---

## Problem Statement

The Listerizer API exposes `GET /items` which returns the full list, but provides no way for a consumer to ask "give me one unread article to process next." Today, both the GAS script and the scheduled summarization job must either scan all items client-side or embed their own selection logic. As the API becomes the source of truth, article selection should live in the API itself — not be reimplemented in every consumer.

## Proposed Solution

A new read-only endpoint — `GET /items/unread/random` — that selects one item at random from all items where `hasBeenRead = false` and returns it as a single JSON object. The endpoint has no side effects: it does not mark the item as read. The caller is responsible for marking the item read via the existing `POST /items` upsert once processing is complete.

If no unread items exist, the endpoint returns `404 Not Found` with a structured error body so callers can branch cleanly without null-checking a response body.

---

## User Stories

1. As the GAS script, I want to call one API endpoint to receive a random unread article, so that I can generate a summary without fetching and filtering the full item list.
2. As the scheduled summarization job, I want the API to handle random unread selection, so that I do not embed selection logic in the job itself.
3. As any API consumer, I want a `404` response (not an empty object or empty array) when there are no unread articles, so that I can handle the "nothing to do" case without inspecting a response body.

---

## Acceptance Criteria

### Happy Path

- `GET /items/unread/random` returns `200 OK` when at least one item with `hasBeenRead = false` exists.
- The response body is a single JSON object (not an array) with the same fields as a `POST /items` response: `id`, `url`, `createTime`, `title`, `hasBeenRead`.
- The returned item's `hasBeenRead` field is `false`.
- When multiple unread items exist, repeated calls to the endpoint do not always return the same item — across 10 calls against a store of 10+ unread items, at least 2 distinct items must be returned.
- The endpoint does not modify any data. After calling `GET /items/unread/random`, `GET /items` returns the same result as before the call.

### Empty Store

- `GET /items/unread/random` returns `404 Not Found` when no items exist in the store.
- `GET /items/unread/random` returns `404 Not Found` when items exist but all have `hasBeenRead = true`.
- The `404` response body follows the existing error shape: `{"error": "not_found", "message": "..."}`.

### General

- The endpoint returns `Content-Type: application/json`.
- The endpoint does not accept a request body; any body sent is ignored.
- The endpoint is `GET` only. `POST`, `PUT`, `PATCH`, `DELETE` to `/items/random/unread` return `405 Method Not Allowed`.

---

## Out of Scope

- Marking the selected item as read — the caller uses `POST /items` with `hasBeenRead: true` after processing.
- Filtering by any field other than `hasBeenRead` (e.g., by date range, by title keyword).
- Returning more than one item per request.
- Any selection strategy other than uniform random (e.g., oldest-first, least-recently-selected).
- A seed or deterministic-random option for reproducible results.
- Authentication.

---

## Implementation Notes

- **Random selection:** Use `ORDER BY RAND()` in MySQL. Sufficient at personal reading list scale (tens to hundreds of rows). Weighted selection or exclusion windows are out of scope.

---

## Dependencies

- The `has_been_read` column must exist on the `items` table (shipped in the API schema extension spec dated 2026-05-02).
- The scheduled summarization job spec (2026-05-03) and the GAS write-back path (three-way sync spec 2026-05-02) are the primary downstream consumers of this endpoint; both are blocked on this shipping first if they intend to use it.
