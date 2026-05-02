# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Listerizer is a three-part system for managing a Chrome Reading List and generating AI-powered article summaries:

1. **Chrome Extension** (`extension/`) — Manifest V3 extension that exports the Chrome reading list to JSON or CSV, and syncs items to the Listerizer REST API.
2. **Google Apps Script** (`appscript/listerizer.gs`) — Active pipeline. Reads unread items from a Google Sheets spreadsheet, fetches page content, summarizes via the Gemini API, marks the item as read in the Sheet, and emails the result.
3. **Spring Boot REST API** (repo root) — The source of truth for reading list items. Accepts upserts from the Chrome extension and will receive write-backs from the GAS script.

> `extension/listerizer.gs` is a legacy script that reads from a Google Drive JSON file. It is superseded by `appscript/listerizer.gs` and should not be modified.

## Architecture

### Data Flow

1. User triggers the Chrome extension → `background.js` opens `export.html` in a new tab.
2. `export.js` calls `chrome.readingList.query({})` and renders the list; user can download as JSON or CSV, or click "Sync to Service" to POST all items to the REST API.
3. `appscript/listerizer.gs` runs on a schedule: reads the Google Sheets spreadsheet, picks the first row where `hasBeenRead` is false, fetches the page, generates a Gemini summary, marks the row as read (`hasBeenRead = true`), and emails the summary.

### Key Constraints

**Chrome Extension (Manifest V3)**
- `background.js` is a service worker — no persistent state, no DOM access.
- Only the `readingList` permission is declared; no `downloads` or other permissions are used (downloads are triggered via a blob URL + `<a>` click in `export.js`).
- The "Sync to Service" button in `export.js` POSTs to `https://listerizer.brickfolio.dev/items`.

**Google Apps Script (`appscript/listerizer.gs`)**
- Uses GAS-specific globals: `PropertiesService`, `SpreadsheetApp`, `UrlFetchApp`, `MailApp`, `Session`, `Logger`.
- No Node.js/npm — do not introduce `require()` or any module syntax.
- `showdown.js` is loaded as a GAS library for Markdown→HTML conversion before emailing.
- Required script properties: `GEMINI_API_KEY`, `JSON_FOLDER_ID`.
- Google Sheets spreadsheet ID: `1SqGoYhG9WKWbc096SBD_73J0gWusyQUM02SKkJD2jus`. Sheet columns: index 0 = title, index 1 = url, index 2 = hasBeenRead (boolean). Row 0 is a header row; data begins at index 1.
- Gemini model: `gemini-2.5-flash-lite` via `generativelanguage.googleapis.com/v1beta`.

## Spring Boot + Jersey REST API

A Java service at the repo root, built with Spring Boot 4 / Java 25, Jersey (JAX-RS), Spring Data JPA, and Flyway.

### Commands

```bash
gradle bootRun              # run the API server (port 5080)
gradle build                # compile + build listerizer.jar
gradle test                 # run unit tests
gradle integrationTest      # run integration tests (requires Docker for Testcontainers)
```

### Architecture

**Request path:** HTTP → Jersey servlet → `ItemController` (JAX-RS) → `ItemService` → `ItemRepository` (Spring Data JPA) → MySQL

- `JerseyConfig` — registers all JAX-RS resources and exception mappers with Jersey's `ResourceConfig`.
- `ItemController` (`api`) — `POST /items` (upsert) and `GET /items` (list). Returns `201 Created` for new URLs, `200 OK` for duplicates.
- `ItemService` (`service`) — validates the URL and `create_time`, delegates to the repository. On a duplicate-URL `DataIntegrityViolationException`, fetches the existing row and returns it.
- `ItemRepository` (`repository`) — extends `CrudRepository<Item, Long>`. `save()` issues INSERT for new entities; `findByUrl()` is used for conflict resolution.
- Flyway manages schema migrations. Migration files live in `src/main/resources/db/migration/` and follow the `V{n}__{description}.sql` naming convention. The current schema is defined in `V1__create_items_table.sql`.

**Package structure:**

| Package | Contents |
|---|---|
| `dev.brickfolio.listerizer` | `JerseyConfig`, `ValidationExceptionMapper`, `JacksonExceptionMapper`, `ErrorResponse`, `LiszterizerApiApplication` |
| `dev.brickfolio.listerizer.api` | `ItemController`, `ItemRequest`, `ItemResponse` |
| `dev.brickfolio.listerizer.service` | `ItemService`, `InsertResult`, `ValidationException` |
| `dev.brickfolio.listerizer.repository` | `ItemRepository` |
| `dev.brickfolio.listerizer.domain` | `Item` |

**Database:** MySQL 9. Connection configured via environment variables (see Configuration). Hikari connection pool, max size 5.

**`create_time` handling:** stored and returned as a Unix epoch seconds integer (`long`). The JSON field is currently `create_time` (snake_case, via `@JsonProperty`). `ItemService` validates that the value is non-null and ≥ 0.

**Exception mappers:** `ValidationExceptionMapper` and `JacksonExceptionMapper` translate domain/deserialization errors into JSON `{"error": "invalid_request", "message": "..."}` responses.

### Configuration

| Property | Default | Override |
|---|---|---|
| `server.port` | `5080` | standard Spring env |
| `spring.datasource.url` | `jdbc:mysql://localhost:3306/listerizer` | `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DB` env vars |
| `spring.datasource.username` | `listerizer` | `MYSQL_USER` env var |
| `spring.datasource.password` | *(none)* | `MYSQL_PASSWORD` env var |

### Testing

- Unit tests: `src/test/` — pure JUnit 5, no Spring context.
- Integration tests: `src/integrationTest/` — full Spring Boot context with a real MySQL instance via Testcontainers (`MySQLContainer`). Requires Docker. Integration tests run after unit tests and use the `test` Spring profile.

## Development Notes

- **Loading the extension locally**: In Chrome, go to `chrome://extensions`, enable Developer Mode, and click "Load unpacked" pointing to the `extension/` directory.
- **Running the GAS script**: Deploy `appscript/listerizer.gs` in the [Google Apps Script editor](https://script.google.com), set the required script properties, add the `showdown` library, then run `runListerizer()` manually or via a time-based trigger.
- The Chrome extension and GAS script have no build step and no test suite — they are plain source files used directly.
