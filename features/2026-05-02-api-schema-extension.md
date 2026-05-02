# API Schema Extension: title, hasBeenRead, and Upsert Behavior

**Date:** 2026-05-02
**Status:** Draft
**Parent:** [Three-Way Reading List Sync](2026-05-02-three-way-sync.md) — Phase 1

---

## Problem Statement

The Listerizer API stores only `url` and `createTime` per item, making it impossible for any caller to record or retrieve whether an item has been read or what its title is. Additionally, the current duplicate-URL behavior (silent ignore, return existing row unchanged) prevents callers from updating an item's read-status without a separate endpoint. These two gaps block all three sync paths defined in the parent spec.

## Proposed Solution

Extend the `items` schema with two new fields — `title` (nullable string) and `hasBeenRead` (boolean, default `false`) — and change the `POST /items` duplicate-URL behavior from "ignore" to a selective upsert: if the incoming request asserts `hasBeenRead: true`, update the stored record; if the stored title is blank, accept the incoming title. Read-status is a one-way ratchet — it never moves from `true` back to `false`.

All JSON field names are camelCase throughout the API (`createTime`, `hasBeenRead`). The existing `create_time` snake_case field name is renamed to `createTime` as part of this release. This is a breaking change for any caller currently reading `create_time` from the response.

`createTime` remains required on insert. If the caller does not supply it, the server defaults to the current system time (Unix epoch seconds). Items migrated from Google Sheets — which have no known creation timestamp — will receive the time of migration.

---

## User Stories

1. As the Chrome extension, I want to POST a reading list item with its title and read-status, so that the API reflects both whether I've read it and what it's called.
2. As the GAS script, I want to POST a URL with `hasBeenRead: true` after generating a summary, so that the API is immediately updated without waiting for the next Chrome sync.
3. As any API consumer, I want `GET /items` to return `title` and `hasBeenRead` for every item, so that I can build read/unread views without a separate data source.
4. As the Sheets migration function, I want to POST items without a `createTime`, so that items with no known timestamp are accepted and receive the migration timestamp automatically.

---

## Acceptance Criteria

### POST /items — New Fields

- Accepts a `title` field (string). If absent or `null`, the stored title is `null` (no error).
- Accepts a `hasBeenRead` field (boolean). If absent or `null`, defaults to `false`.
- If `createTime` is absent or `null`, the server uses the current system time (Unix epoch seconds) as the stored value. If `createTime` is present, it must be a non-negative integer or the request returns `400 Bad Request`.
- Returns `201 Created` on a new URL with the stored item, including `title`, `hasBeenRead`, and `createTime`.

### POST /items — Duplicate URL Upsert

- A `POST /items` for a URL that already exists returns `200 OK`.
- If the request contains `hasBeenRead: true` and the stored value is `false`, the stored value is updated to `true`.
- If the request contains `hasBeenRead: false` and the stored value is `true`, the stored value is **not** changed (read is terminal; no downgrade).
- If the stored `title` is `null` or empty and the request contains a non-empty `title`, the stored title is updated.
- If the stored `title` is already non-empty, it is **not** overwritten regardless of what the request contains.
- `createTime` and `url` are never updated on a duplicate — only `title` and `hasBeenRead` are eligible for upsert.
- The response body on a duplicate reflects the state of the record **after** the upsert is applied.

### GET /items

- Each item in the response array includes `title` (string or `null`) and `hasBeenRead` (boolean).
- Each item uses `createTime` (camelCase) in place of the former `create_time`.
- Items where `hasBeenRead` was never set are returned with `hasBeenRead: false`.
- Items where `title` was never set are returned with `title: null`.

### General

- All JSON field names in requests and responses are camelCase. Snake_case field names (`create_time`) are no longer accepted or returned.
- Malformed JSON, missing `url`, or a `createTime` that is present but negative returns `400 Bad Request`.

---

## Out of Scope

- A dedicated `PATCH /items/{id}` or `PUT /items/{id}` endpoint — upsert via `POST /items` is the only write path in this phase.
- Bulk insert or batch endpoints.
- Filtering or querying `GET /items` by `hasBeenRead` or any other field.
- Deleting items or resetting `hasBeenRead` to `false` by any means.
- Any change to the `url` validation rules.
- Authentication.

---

## Dependencies

- A database migration adding `title` (nullable VARCHAR) and `has_been_read` (boolean, default `false`) columns to the `items` table must be written and applied before any application code changes are deployed.
- The `ItemRequest`, `ItemResponse`, and `Item` domain class must all be updated in the same release to avoid a partial-state window.
- All existing callers (`export.js` sync button) must be updated to use `createTime` (camelCase) in POST payloads after this ships — the snake_case field name will no longer be accepted.
- Downstream consumers of this spec (Chrome extension sync enhancement, GAS write-back, Sheets migration function) are blocked until this phase ships.
