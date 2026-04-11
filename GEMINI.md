# Listerizer - AI Assistant Instructions

## Project Context
Listerizer is a two-part system designed to manage and export a user's Chrome Reading List, and systematically summarize unread articles.
1. **Chrome Extension (Manifest V3)**: Contains a background service worker (`background.js`), a popup/options page (`export.html`, `export.js`, `export.css`), and is responsible for exporting the Chrome reading list to a JSON format.
2. **Google Apps Script (`listerizer.gs`)**: Fetches the exported reading list from Google Drive, picks unread items, extracts the page content, uses the Gemini API (`gemini-2.5-flash-lite`) to summarize the content into a high-density "zero-fluff" intelligence briefing, and emails the result to the user.

## Tech Stack
- **Frontend Chrome Extension**: HTML, CSS, Vanilla JavaScript, Chrome Extension APIs (Manifest V3, `readingList` permission).
- **Backend / Automation**: Google Apps Script (GAS), Google Drive API, `UrlFetchApp`, `MailApp`.
- **AI Integration**: Gemini API (`generativelanguage.googleapis.com/v1beta`).

## Guidelines for AI Assistants (like myself)
- **Code Modifications**: When modifying the Chrome extension, keep in mind Manifest V3 rules (e.g., service workers instead of background pages, strict content security policies, limits on synchronous operations).
- **Google Apps Script**: When updating `listerizer.gs`, use GAS-specific globals like `PropertiesService`, `UrlFetchApp`, `DriveApp`, `MailApp`, and `Logger.log`. Avoid standard Node.js libraries as GAS runs its own environment.
- **Dependencies**: The Apps Script relies on `showdown.js` for Markdown to HTML conversion.
- **Styling**: Stick to the vanilla CSS approach used in `export.css` for any UI updates to the Chrome Extension unless requested otherwise.
- **API Payloads**: When modifying the Gemini API prompt in `listerizer.gs`, ensure the output format remains concise and bulleted for "zero-fluff" readings.

## Project Structure
All source files live in the `extension/` subdirectory:
- `extension/manifest.json`: Configuration for the Chrome extension.
- `extension/background.js`: Service worker for the extension.
- `extension/export.html` / `extension/export.js` / `extension/export.css`: UI for exporting the reading list.
- `extension/listerizer.gs`: Google Apps Script logic for summarization and emailing.
