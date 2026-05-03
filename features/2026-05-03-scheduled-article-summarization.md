@# Scheduled Article Summarization

**Date:** 2026-05-03
**Status:** Draft

---

## Problem Statement

The Listerizer reading list accumulates unread articles, but today the only pipeline to process them runs in Google Apps Script — requiring a separate GAS project, manual credential management, and a Google Sheets data store that duplicates the REST API's authoritative database. Moving the summarization job into the Spring Boot service eliminates the GAS dependency, consolidates configuration, and ensures the pipeline operates against live API data rather than a secondary copy.

## Proposed Solution

A scheduled background job embedded in the Spring Boot service that, on a configurable interval (default 10 hours):

1. Selects one item at random from all items in the database where `hasBeenRead = false`.
2. Fetches the raw page content of that item's URL via an outbound HTTP GET.
3. Submits the page content to the Gemini API with a configurable prompt to produce a TL;DR summary.
4. Sends an email containing the summary and article metadata to a configurable recipient address.
5. Marks the item `hasBeenRead = true` in the database.

All tunable parameters — interval, Gemini API key, Gemini model, summarization prompt, and recipient email — are supplied via environment variables or application configuration. No hardcoded values.

---

## User Stories

1. As a Listerizer user, I want the service to periodically email me a summary of a random unread article, so that I make progress on my reading list without manually deciding what to read next.
2. As a Listerizer operator, I want to configure how often summaries are sent, so that I can tune the cadence to match how quickly I can consume summaries.
3. As a Listerizer operator, I want to configure the summarization prompt, so that I can control the tone, length, and format of the summary without redeploying code.
4. As a Listerizer operator, I want to configure the recipient email address, so that I can direct summaries to any inbox without a code change.
5. As a Listerizer user, I want the article marked as read after a summary is sent, so that the same article is not summarized again.

---

## Acceptance Criteria

### Scheduling

- The job runs automatically on a configurable fixed-delay interval. The default interval is 10 hours.
- The interval is controlled by a single configuration value (e.g., `SUMMARIZER_INTERVAL_HOURS`). Changing this value and restarting the service changes the cadence.
- The first execution occurs after one full interval has elapsed from service startup, not immediately on boot.

### Article Selection

- The job queries the database for all items where `hasBeenRead = false`.
- If one or more unread items exist, one is selected at random (uniform distribution).
- If no unread items exist, the job logs a message and exits without sending an email or making any external call. It does not mark any item as read.

### Web Fetch

- The job issues an HTTP GET to the selected item's URL with a reasonable timeout (≤ 15 seconds).
- If the fetch returns a non-2xx HTTP status or times out, the job logs the error, does **not** mark the item as read, does **not** send an email, and exits for this cycle. The item remains eligible for selection in a future cycle.
- The raw response body (HTML or plain text) is passed to the Gemini API as the article content. No JavaScript rendering is performed.

### Gemini Summarization

- The job calls the Gemini API with the page content and a configurable prompt template.
- The prompt template is a plain string supplied via configuration (e.g., `SUMMARIZER_PROMPT`). The service inserts the fetched article text into the prompt before sending.
- The Gemini API key and model name are each independently configurable via environment variables.
- If the Gemini API returns an error or the response contains no text, the job logs the error, does **not** mark the item as read, does **not** send an email, and exits for this cycle.

### Email Delivery

- The job sends an email to a configurable recipient address (e.g., `SUMMARIZER_EMAIL_TO`).
- The email subject includes the article title (or URL if title is absent).
- The email body contains at minimum: the article URL, the article title (if present), and the Gemini-generated summary.
- The email is sent via SMTP. SMTP host, port, username, and password are configurable via environment variables.
- If the email send fails, the job logs the error and does **not** mark the item as read.

### Read-Status Update

- The item is marked `hasBeenRead = true` only after the email is successfully sent.
- A failure at any prior step (fetch, Gemini, email) leaves `hasBeenRead = false` unchanged.

### Configuration Reference

The following values must be configurable without a code change:

| Parameter | Description | Default |
|---|---|---|
| `SUMMARIZER_INTERVAL_HOURS` | Hours between job runs | `10` |
| `SUMMARIZER_PROMPT` | Prompt template sent to Gemini with article text | (required, no default) |
| `SUMMARIZER_GEMINI_API_KEY` | Gemini API key | (required, no default) |
| `SUMMARIZER_GEMINI_MODEL` | Gemini model identifier | (required, no default) |
| `SUMMARIZER_EMAIL_TO` | Recipient email address | (required, no default) |
| SMTP host/port/user/pass | Standard SMTP credentials | (required, no default) |

---

## Out of Scope

- Retry logic with backoff for transient failures (fetch, Gemini, SMTP) — a failed cycle simply waits for the next scheduled run.
- Processing more than one article per scheduled cycle.
- Any selection strategy other than random (e.g., oldest first, by tag).
- Choosing which unread items are eligible for summarization (all unread items are eligible).
- JavaScript rendering or headless browser execution for JS-gated pages.
- Storing the generated summary in the database.
- Any user-facing API endpoint to trigger the job on demand.
- HTML formatting of the email body — plain text is sufficient.
- Deduplication of summaries within a short window (addressed by marking read after send).
- Moving or deprecating the existing GAS pipeline — that is a separate cleanup decision.

---

## Open Questions

1. **Prompt template format:** Should the article text be appended after the prompt string, or should the prompt contain an explicit placeholder (e.g., `{{article}}`) that the service replaces? The placeholder approach gives the operator more control over where content appears.
2. **Page content extraction:** Should the service pass the full raw HTML to Gemini, or attempt to strip tags and extract visible text first? Gemini can process HTML, but token usage will be higher and the model may be distracted by nav/footer boilerplate.
3. **Article length limits:** Is there a maximum page size (bytes or tokens) above which the service should truncate before sending to Gemini? Very long pages may exceed model context limits or generate unexpectedly large API costs.
4. **SMTP vs. transactional email:** Should the service use direct SMTP, or is a transactional email provider (e.g., SendGrid, Mailgun) preferred? This affects the credential model and deliverability characteristics.
5. **Failure visibility:** Is logging to stdout sufficient for failed cycles, or is there a monitoring/alerting expectation (e.g., an endpoint to check last-run status, a metric)?
6. **Startup behavior:** Should the service skip the first-run delay and execute immediately on boot (useful for testing), controlled by a flag?

---

## Dependencies

- The `items` table must have the `has_been_read` column (shipped in the API schema extension spec dated 2026-05-02) before this feature can query for unread items or mark them read.
- A Gemini API key must be provisioned and available as a secret in the deployment environment.
- SMTP credentials (or a transactional email service account) must be provisioned.
- The Spring Boot service must be deployed in an environment with outbound HTTP access to both external article URLs and the Gemini API endpoint.
