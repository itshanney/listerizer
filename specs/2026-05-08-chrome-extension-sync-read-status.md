# Chrome Extension: Bidirectional Sync via "Sync to Service" Button

**Date:** 2026-05-08  
**Author:** ems_architect  
**Status:** Revised (v2 — collapsed into existing "Sync to Service" flow)

---

## Overview

Extend the existing "Sync to Service" button to perform a **bidirectional sync in a single pass**: it already POSTs each Chrome reading list item to `POST /items`; the API response (`ItemResponse`) already returns `hasBeenRead` per item. After each successful POST, if the API response has `hasBeenRead: true` and Chrome's local copy has `hasBeenRead: false`, the extension calls `chrome.readingList.updateEntry` to bring Chrome in sync. No new button, no new API endpoint, no separate GET call — the POST response carries everything needed. The "Sync to Service" button becomes a true two-way sync in a single operation.

---

## Component Diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│  Chrome Extension (export.html / export.js)                          │
│                                                                      │
│  [Download JSON]  [Download CSV]  [Sync to Service]                  │
│                                           │                          │
│                              ┌────────────▼───────────────────┐      │
│                              │  syncToService() (enhanced)    │      │
│                              │  for each Chrome item:         │      │
│                              │    POST /items → ItemResponse  │      │
│                              │    if response.hasBeenRead &&  │      │
│                              │       !chrome.hasBeenRead:     │      │
│                              │      updateEntry(url, true)    │      │
│                              └────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────────────┘
                                           │
                                           ▼
                              ┌─────────────────────────┐
                              │  POST /items            │
                              │  Returns ItemResponse   │
                              │  { hasBeenRead, ... }   │
                              └─────────────────────────┘
                                           │
                                           ▼
                              ┌─────────────────────────┐
                              │  Listerizer REST API    │
                              │  (no changes)           │
                              └─────────────────────────┘
```

**Component responsibilities:**

| Component | Responsibility |
|---|---|
| `export.html` | No changes — existing "Sync to Service" button is reused |
| `export.js` `syncBtn` handler | Enhanced: after each POST, calls `updateEntry` if API response ratchets `hasBeenRead` to `true` |
| `POST /items` | Upserts item; returns `ItemResponse` with authoritative `hasBeenRead` value |
| `chrome.readingList.updateEntry` | Applies `hasBeenRead: true` to Chrome for items the API has marked read |
| REST API | No changes — existing upsert + response shape is sufficient |

---

## Data Model

No schema changes. The bidirectional sync is driven by the existing POST response.

**Chrome item** (input, from `readingListData`):

```
{ url: string, title: string, hasBeenRead: boolean, creationTime: number, lastUpdateTime: number }
```

**Request to API** (`POST /items` body — unchanged):

```
{ url: string, title: string, hasBeenRead: boolean, createTime: number }
```

**API response** (`ItemResponse` — unchanged):

```
{ id: long, url: string, createTime: long, title: string, hasBeenRead: boolean }
```

**Sync rule (one-way ratchet on Chrome side):** After a successful POST (2xx), if `response.hasBeenRead === true` AND the original Chrome item had `hasBeenRead === false`, call `chrome.readingList.updateEntry({ url, hasBeenRead: true })`. Never call `updateEntry` with `hasBeenRead: false`.

---

## API Contracts

No changes to the REST API. The existing endpoint already returns the authoritative `hasBeenRead` state:

```
POST https://listerizer.brickfolio.dev/items

Request body:
{ "url": "...", "title": "...", "hasBeenRead": false, "createTime": 1715000000 }

Response 201 (new item) or 200 (existing item):
{
  "id": 1,
  "url": "https://example.com/article",
  "createTime": 1715000000,
  "title": "Example Article",
  "hasBeenRead": true     ← may differ from what Chrome sent
}
```

`chrome.readingList.updateEntry` (Chrome extension API, covered by existing `readingList` permission):

```js
chrome.readingList.updateEntry({ url: string, hasBeenRead: boolean })
// Returns: Promise<void>
// Throws: if URL does not exist in Chrome's reading list
```

---

## Critical Path Walkthrough

### Happy path: some items need read-status updated in Chrome

1. User opens the Listerizer extension → `export.html` loads.
2. `DOMContentLoaded`: `chrome.readingList.query({})` returns 10 items; 3 have `hasBeenRead: false`.
3. User clicks **"Sync to Service"**.
4. Button is disabled; status shows "Syncing…".
5. For each of the 10 items, `export.js` POSTs to `/items`. Per item:
   - **Item A** (`hasBeenRead: false` in Chrome) → POST returns `{ hasBeenRead: true }` → call `chrome.readingList.updateEntry({ url: A, hasBeenRead: true })`.
   - **Item B** (`hasBeenRead: false` in Chrome) → POST returns `{ hasBeenRead: true }` → call `updateEntry`.
   - **Item C** (`hasBeenRead: false` in Chrome) → POST returns `{ hasBeenRead: false }` → skip `updateEntry`.
   - **Items D–J** (`hasBeenRead: true` in Chrome) → POST succeeds; no `updateEntry` needed (already true).
6. Results array is populated (same shape as before: `{ url, status, body }`).
7. `jsonOutput` updated with results JSON.
8. Status: "Synced 10 items successfully!" (existing logic).
9. Button re-enabled.

### Happy path: nothing needs Chrome-side update

Same as above, but all POST responses have `hasBeenRead` matching Chrome's local state. No `updateEntry` calls are made. Behavior is identical to the current "Sync to Service" implementation from the user's perspective.

### Error path: POST fails for an item

- The existing error handling records `{ url, status: 'error', error: err.message }` for that item.
- `updateEntry` is **not called** for failed POSTs — Chrome is only updated when the API confirms its state.
- Other items in the loop are not affected.

### Error path: `updateEntry` fails after a successful POST

This can happen if the item was removed from Chrome between the initial `query` and the `updateEntry` call.

- Wrap each `updateEntry` call in its own try/catch.
- Log the error with the URL for diagnosability.
- Record it alongside the POST result so the final summary can reflect it.
- Do not abort the loop.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risks Accepted |
|---|---|---|---|---|
| Button strategy | New "Sync Read Status" button, enhance existing "Sync to Service" button | Enhance existing button | Eliminates a second button and a separate `GET /items` call. The POST response already carries the authoritative `hasBeenRead` — there is no reason to make a second round-trip. | The "Sync to Service" button now does more than its label implies. Acceptable: the label can be updated, or the behavior is discoverable via the result output. |
| When to call `updateEntry` | On every POST response, only on 2xx, only on 201 | Only on 2xx | 201 = new item (API didn't know about it, so GAS couldn't have marked it read yet). 200 = existing item (API may have updated `hasBeenRead` since last sync). Calling `updateEntry` on both 200 and 201 is safe because the ratchet rule prevents regressing `hasBeenRead` from `true` to `false`. | Minimal. A brand-new item returned as `hasBeenRead: true` on a 201 would be unusual but handled correctly. |
| `updateEntry` failure isolation | Abort loop, log and skip, fail the whole sync | Log and skip per-item | One Chrome item being stale at sync time should not block the other 99. Idempotency means the next sync will retry it. | A failed `updateEntry` is silent to the user unless they inspect the console. If per-item failure visibility becomes important, add it to the results array. |
| Sync direction | Bidirectional (both directions in one pass), API→Chrome only (separate flow) | Bidirectional in one pass | Leverages the already-in-flight POST response; no additional network call. | Chrome→API and API→Chrome are now coupled in one button. If one direction needs to be skipped, the implementation must be extended. |
| Chrome `hasBeenRead: false` ratchet | Sync both directions, only false→true | Only false→true (Chrome) | Chrome marks items read when visited. Setting `hasBeenRead: false` via `updateEntry` would fight Chrome's own behavior. | An item marked read in Chrome but `false` in the API will remain read in Chrome. This is correct — Chrome's local read state is authoritative for the read→unread direction. |

---

## Operational Concerns

**Permissions:** No change. `readingList` covers `updateEntry`. `https://listerizer.brickfolio.dev/*` covers `POST /items`.

**No manifest changes:** No new permissions, no new host patterns.

**Idempotency:** Running "Sync to Service" twice is safe. The POST upserts are idempotent (API design). The `updateEntry` ratchet means calling it twice with `hasBeenRead: true` has no negative effect.

**Failure isolation:** Each item's POST and `updateEntry` are wrapped independently. One failure does not block others.

**Rollback:** No backend changes — rollback is reverting `export.js` only.

**Capacity:** Unchanged from the existing sync flow. One POST per reading list item; `updateEntry` adds a negligible in-process Chrome API call per item that needs it.

**Monitoring:** No server-side change needed. Client-side errors surface in `console.error` and in the `jsonOutput` results display.

---

## Out of Scope / Future Work

| Item | Reason deferred |
|---|---|
| Updating the button label to reflect bidirectional behavior | UX decision; the behavior is observable in the results output. Not a blocker. |
| Automatic background sync on a timer | Requires `alarms` permission; adds complexity. Revisit if manual sync is too cumbersome. |
| Syncing title updates from API → Chrome | `chrome.readingList.updateEntry` supports `title`. Could be layered on top of this same per-item response check later. |
| Removing Chrome items that were deleted from the API | Requires `chrome.readingList.deleteEntry`; different UX and risk profile. |
| Per-item `updateEntry` failure surfaced in the UI results | Current design logs to console only. If visibility is needed, add a `readStatusUpdated` / `readStatusError` field to the result objects. |
