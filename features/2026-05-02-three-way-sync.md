# Three-Way Reading List Sync

**Date:** 2026-05-02
**Status:** Draft

---

## Problem Statement

The reading list pipeline has three data stores — the Chrome Reading List, a Google Sheets spreadsheet managed by the Sheets-based GAS script, and the new Listerizer REST API — that are currently disconnected or unidirectional. The Chrome extension's "Sync to Service" button already sends items to the API but drops `title` and read-status, so the API cannot distinguish read from unread items. Items that have already been processed (marked read in the Google Sheet) have no path into the API. As the API becomes the source of truth, both gaps must be closed.

**Note on GAS scripts:** There are two `listerizer.gs` files in the repo. `appscript/listerizer.gs` reads from and writes to the Google Sheets spreadsheet (ID `1SqGoYhG9WKWbc096SBD_73J0gWusyQUM02SKkJD2jus`) and is the active pipeline. `extension/listerizer.gs` reads from a Google Drive JSON file and is the older version. This spec targets the Sheets-based script as the second data source.

## Proposed Solution

Two-phase work:

**Phase 1 — Extend the API schema** to store `title` (string) and `hasBeenRead` (boolean) alongside the existing `url` and `createTime`. All JSON field names are camelCase; the former `create_time` snake_case field is renamed to `createTime` in this release. This unblocks both sync paths.

**Phase 2A — Ongoing sync (Chrome → API)**: Update the Chrome extension's "Sync to Service" button to include `title` and `has_been_read` in the POST payload. The API upsert must update `title` and `hasBeenRead` on conflict (currently it ignores duplicates entirely — that behavior must change for read-status to propagate).

**Phase 2B — One-time migration (Google Sheets → API)**: A GAS function (run once, manually) reads all rows from the Google Sheet and POSTs each item to the API with its `title`, `url`, and `hasBeenRead` state. Because the Sheet does not store a creation timestamp, `createTime` is omitted; the server defaults to the current system time for those items. Items already in the API receive an update to `hasBeenRead` and `title` per the merge rule below.

**Phase 2C — GAS write-back (ongoing)**: After `runListerizer()` in `appscript/listerizer.gs` marks an item read in the Sheet and sends the email, it also POSTs the read-status update to the Listerizer API so the API reflects the change immediately rather than waiting for the next Chrome sync.

The canonical deduplication key is `url`. The merge rule when a URL already exists: always apply `hasBeenRead = true` if either source says read (read is terminal — items are never un-read); update `title` only if the stored title is blank.

---

## Google Sheets Schema (Source of Truth for Migration)

Spreadsheet ID: `1SqGoYhG9WKWbc096SBD_73J0gWusyQUM02SKkJD2jus`

| Column index | Field          | Type    |
|--------------|----------------|---------|
| 0            | `title`        | string  |
| 1            | `url`          | string  |
| 2            | `hasBeenRead`  | boolean |

Row 0 is treated as a header row; data begins at row index 1. Gemini summaries are **not** stored in the Sheet — they exist only in email history.

---

## User Stories

1. As a user, I want the ChromList sync to send title and read-status to the API, so that the API contains a complete and current record for every item.
2. As a user, I want my historically read items from Google Sheets migrated to the API, so that the full reading history exists in one place before I retire the Sheets-based workflow.
3. As the GAS script, I want to write an item's read-status back to the API after processing it, so that the API stays authoritative without requiring a manual Chrome sync.
4. As a user, I want duplicate URLs handled correctly during sync, so that re-running the sync or Chrome export does not corrupt or reset data.

---

## Acceptance Criteria

### API Schema

- `POST /items` accepts a `title` field (string, optional for backward compatibility).
- `POST /items` accepts an `hasBeenRead` field (boolean, defaults to `false` if absent).
- `GET /items` returns `title` and `hasBeenRead` for every item.
- A `POST /items` with a URL that already exists **updates** `hasBeenRead` to `true` if the request body contains `hasBeenRead: true`; it does not downgrade a `true` to `false`.
- A `POST /items` with a URL that already exists updates `title` only if the currently stored title is null or empty.

### Chrome Extension Sync

- The "Sync to Service" POST payload includes `title` (mapped from `item.title`) and `hasBeenRead` (mapped from `item.hasBeenRead`) for every item.
- After a successful sync, `GET /items` returns the correct `hasBeenRead` value for every item that Chrome marks as read.
- Syncing the same list twice does not change the result (idempotent).

### Google Sheets Migration Function

- A new GAS function (e.g., `migrateSheetToApi()`) iterates every data row in the Sheet and POSTs each item to `POST /items`.
- Items with `hasBeenRead = true` in the Sheet are reflected as `hasBeenRead: true` in the API after migration.
- Items already present in the API are not duplicated; their `hasBeenRead` and `title` follow the merge rule above.
- The function logs a count of: items created, items updated, items unchanged, items errored.
- Running the function a second time produces zero creates, zero updates, and zero errors (fully idempotent).

### GAS Write-back

- After `runListerizer()` marks an item read in the Sheet and sends the email, it POSTs to `POST /items` with `url` and `hasBeenRead: true`.
- A failure to POST to the API does not prevent the email from being sent (the API call is best-effort; the GAS script logs the error and continues).

---

## Out of Scope

- Syncing data from Listerizer API back to Chrome Reading List (Chrome's `readingList` API does not support writes from extensions).
- Storing Gemini summary text in the API (summaries currently exist only in email; a separate spec would define a `summary` field and GAS write-back path).
- Deleting items from the API when they are removed from the Chrome Reading List or Google Sheet.
- Real-time / event-driven sync (all sync is user-initiated or GAS-triggered).
- Pagination on `GET /items`.
- The legacy Drive JSON–based `extension/listerizer.gs` script.
- Authentication.

---

## Open Questions

1. **`createTime` for Sheets migration**: ~~Resolved~~ — `createTime` is optional on POST; the server defaults to current system time when absent. Items migrated from Sheets will carry the migration timestamp.

2. **API backward compatibility for `POST /items` on duplicate URLs**: The current behavior returns `200 OK` and silently ignores the duplicate. Changing to an upsert that updates `hasBeenRead`/`title` is a behavior change — is any existing consumer relying on the "ignore" behavior that would break?

3. **`hasBeenRead` downgrade protection**: Should an item ever move from `hasBeenRead: true` back to `false` (e.g., if a re-import from Chrome shows it as unread)? The proposed rule says no — read is terminal. Confirm this is the intended behavior.

4. **Summary persistence**: Gemini summaries are currently email-only and are not in the Sheet. Should a `summary` field be added to the API in this phase so GAS can write it back immediately after generation, or is that a separate spec?

---

## Dependencies

- Listerizer API schema extension (Phase 1) must complete before Chrome extension sync enhancement (Phase 2A) or GAS write-back (Phase 2C) can be built or tested.
- The `appscript/listerizer.gs` migration function requires the `LISTERIZER_API_URL` to be added as a GAS script property.
- Access to the Google Sheet (ID `1SqGoYhG9WKWbc096SBD_73J0gWusyQUM02SKkJD2jus`) from the GAS deployment that will run the migration.