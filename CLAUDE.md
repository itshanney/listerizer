# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Listerizer is a two-part system for managing a Chrome Reading List and generating AI-powered article summaries:

1. **Chrome Extension** (`extension/`) — Manifest V3 extension that exports the Chrome reading list to JSON or CSV.
2. **Google Apps Script** (`extension/listerizer.gs`) — Runs on a schedule to pick an unread item, fetch its content, summarize it via the Gemini API, and email the result.

## Architecture

### Data Flow

1. User triggers the Chrome extension → `background.js` opens `export.html` in a new tab.
2. `export.js` calls `chrome.readingList.query({})` and renders the list; user downloads as JSON or CSV.
3. The downloaded JSON is placed in a specific Google Drive folder (ID stored in script properties).
4. `runListerizer()` in `listerizer.gs` reads that file from Drive, picks the first unread item, strips the page HTML, sends it to Gemini, marks the item as read in the JSON file, and emails the summary via `MailApp`.

### Key Constraints

**Chrome Extension (Manifest V3)**
- `background.js` is a service worker — no persistent state, no DOM access.
- Only the `readingList` permission is declared; no `downloads` or other permissions are used (downloads are triggered via a blob URL + `<a>` click in `export.js`).

**Google Apps Script**
- Uses GAS-specific globals: `PropertiesService`, `DriveApp`, `UrlFetchApp`, `MailApp`, `Session`, `Logger`.
- No Node.js/npm — do not introduce `require()` or any module syntax.
- `showdown.js` is loaded as a GAS library for Markdown→HTML conversion before emailing.
- Required script properties: `GEMINI_API_KEY`, `JSON_FOLDER_ID`.
- The Drive file ID for the reading list JSON is currently hardcoded in `listerizer.gs:13`.
- Gemini model: `gemini-2.5-flash-lite` via `generativelanguage.googleapis.com/v1beta`.

## Spring Boot + Jersey REST API

A separate Java service lives at the repo root, built with Spring Boot 4 / Java 25 and Jersey as the JAX-RS implementation.

### Commands

```bash
gradle bootRun             # run the API server (port 5080)
gradle build               # compile + build listerizer.jar
gradle test                # run tests
```

### Architecture

**Request path:** HTTP → Jersey servlet → `ItemController` (JAX-RS) → `ItemService` → `ItemRepository` (JdbcTemplate) → SQLite

- `JerseyConfig` — registers all JAX-RS resources and exception mappers with Jersey's `ResourceConfig`.
- `ItemController` (`api`) — `POST /items` (upsert) and `GET /items` (list). Returns `201 Created` for new URLs, `200 OK` for duplicates.
- `ItemService` (`service`) — validates the URL, delegates to the repository.
- `ItemRepository` (`repository`) — uses `INSERT OR IGNORE` so duplicate URLs are a no-op; returns the existing row on conflict.
- `schema.sql` runs on every startup via `spring.sql.init.mode=always`; the `CREATE TABLE IF NOT EXISTS` is idempotent.

**Package structure:**

| Package | Contents |
|---|---|
| `dev.brickfolio.listerizer` | `JerseyConfig`, `ItemRequestModule`, `ValidationExceptionMapper`, `JacksonExceptionMapper`, `ErrorResponse`, `LiszterizerApiApplication` |
| `dev.brickfolio.listerizer.api` | `ItemController`, `ItemRequest`, `ItemResponse`, `CreateTimeDeserializer` |
| `dev.brickfolio.listerizer.service` | `ItemService`, `InsertResult`, `ValidationException` |
| `dev.brickfolio.listerizer.repository` | `ItemRepository` |
| `dev.brickfolio.listerizer.domain` | `Item` |

**Database:** SQLite, configured via `spring.datasource.url`. Override the path with the `LISTERIZER_DB_PATH` environment variable (defaults to `~/listerizer.db`). Pool size is forced to 1 (SQLite single-writer limit); WAL mode is enabled for concurrent reads.

**`create_time` handling:** `CreateTimeDeserializer` accepts either a Unix epoch integer or an ISO 8601 string and normalizes both to a UTC ISO 8601 string (e.g. `2026-04-11T10:30:00Z`) before storage.

**Exception mappers:** `ValidationExceptionMapper` and `JacksonExceptionMapper` translate domain/deserialization errors into JSON error responses; `ErrorResponse` is the shared response record.

### Configuration

| Property | Default | Override |
|---|---|---|
| `server.port` | `5080` | standard Spring env |
| `spring.datasource.url` | `~/listerizer.db` | `LISTERIZER_DB_PATH` env var |

## Development Notes

- **Loading the extension locally**: In Chrome, go to `chrome://extensions`, enable Developer Mode, and click "Load unpacked" pointing to the `extension/` directory.
- **Running the GAS script**: Deploy `listerizer.gs` in the [Google Apps Script editor](https://script.google.com), set the required script properties, add the `showdown` library, then run `runListerizer()` manually or via a time-based trigger.
- The Chrome extension and GAS script have no build step and no test suite — they are plain source files used directly.
