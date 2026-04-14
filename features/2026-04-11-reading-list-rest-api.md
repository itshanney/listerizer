# Reading List REST API

**Date:** 2026-04-11
**Status:** Reviewed

---

## Problem Statement

The Listerizer Chrome extension exports reading list data locally but has no persistent server-side storage. There is no way to retain, retrieve, or process reading list items outside of the browser session, which limits automation and integration with downstream tools (e.g., the Apps Script summarizer).

## Proposed Solution

A simple REST API service that accepts and returns JSON. It exposes at minimum one endpoint to store a reading list URL along with its creation date timestamp. The service stores data for a single individual (no multi-tenancy in this phase).

---

## User Stories

1. As a Chrome extension user, I want to POST a URL with a creation timestamp to the API, so that my reading list items are persisted outside of the browser.
2. As a Chrome extension user, I want to GET all stored reading list items, so that I can retrieve my full list from any client.
3. As the Apps Script automation, I want to GET the stored reading list, so that it can pick unread items for summarization without requiring a manual export step.

---

## Acceptance Criteria

### POST /items

- Accepts `Content-Type: application/json`.
- Request body must include `url` (string, valid URL format) and `create_time` (string, ISO 8601 datetime).
- Returns `201 Created` with the stored item (including a server-assigned `id`) on success.
- Returns `400 Bad Request` with an error message if `url` is missing or not a valid URL.
- Returns `400 Bad Request` with an error message if `create_time` is missing or not a valid ISO 8601 datetime.
- Returns `409 Conflict` if the same `url` already exists in the store.

### GET /items

- Returns `200 OK` with a JSON array of all stored items.
- Each item in the array includes `id`, `url`, and `create_time`.
- Returns an empty array `[]` (not a 404) when no items are stored.
- `create_time` should be returned in a human-readable ISO 8601 format.

### General

- All responses use `Content-Type: application/json`.
- Malformed JSON in the request body returns `400 Bad Request`.
- The service must handle at least 10 concurrent requests without error.

---

## Out of Scope

- Authentication and authorization (single-user, no auth in this phase).
- Multi-user or multi-tenant support.
- Deleting or updating items.
- Marking items as read/unread.
- Pagination of GET /items results.
- Syncing directly with Chrome's `readingList` API — the extension sends data explicitly via POST.
- Any UI or dashboard.

---

## Open Questions

1. **Persistence layer**: Data should be stored in a sqlite database locally on the server.
2. **Authentication**: There will be no authentication in this version.
3. **Duplicate handling**: Duplicates should be idempotent and allowed.
4. **Hosting target**: Server will run on a small Linux workstation.
5. **`create_time` ownership**: The server should accept the timestamp from the request and persist that.
6. **Timestamps**: Timestamps will support the standard seconds since the epoch format or ISO 8601 format.

---

## Dependencies

- The Chrome extension (`extension/export.js`) will need to be updated to POST to this API instead of (or in addition to) local export.
- The Apps Script (`extension/listerizer.gs`) may replace its Google Drive fetch step with a GET to this API — that integration is out of scope here but is a known downstream consumer.
