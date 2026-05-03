// Spreadsheet and column layout — must match appscript/listerizer.gs
const MIGRATION_SPREADSHEET_ID = '1SqGoYhG9WKWbc096SBD_73J0gWusyQUM02SKkJD2jus';
const COL_TITLE         = 0;
const COL_URL           = 1;
const COL_HAS_BEEN_READ = 2;
const HEADER_ROW_COUNT  = 1;

/**
 * One-time migration: pushes every row from the Google Sheet into the
 * Listerizer REST API. Safe to run multiple times — the API upserts on URL,
 * so re-running produces no duplicate records and only ratchets hasBeenRead
 * forward (never backward).
 *
 * Requires the LISTERIZER_API_URL script property to be set before running.
 *
 * createTime is intentionally omitted from the payload; the server defaults
 * to the time of migration for Sheet rows that have no creation timestamp.
 */
function migrateSheetToApi() {
  const props  = PropertiesService.getScriptProperties();
  const apiUrl = props.getProperty('LISTERIZER_API_URL');
  Logger.log("apiUrl: " + apiUrl);

  if (!apiUrl) {
    Logger.log('ERROR: LISTERIZER_API_URL script property is not set. Aborting.');
    return;
  }

  const sheet = SpreadsheetApp.openById(MIGRATION_SPREADSHEET_ID).getActiveSheet();
  const rows  = sheet.getDataRange().getValues();

  let created  = 0;
  let existing = 0; // 200 OK — row was already in the API (updated or unchanged; indistinguishable)
  let skipped  = 0;
  let errored  = 0;

  for (let i = HEADER_ROW_COUNT; i < rows.length; i++) {
    const url        = rows[i][COL_URL];
    const title      = rows[i][COL_TITLE];
    const hasBeenRead = rows[i][COL_HAS_BEEN_READ];

    if (!url || String(url).trim() === '') {
      skipped++;
      Logger.log(`Row ${i + 1}: skipped — empty URL.`);
      continue;
    }

    const payload = buildPayload(url, title, hasBeenRead);
    Logger.log("Posting to API: " + JSON.stringify(payload));
    const result  = postToApi(apiUrl, payload, i + 1);

    if (result === 201)      { created++;  }
    else if (result === 200) { existing++; }
    else                     { errored++;  }
  }

  Logger.log(
    `Migration complete. ` +
    `Created: ${created}, Existing (updated or already synced): ${existing}, ` +
    `Skipped: ${skipped}, Errored: ${errored}.`
  );
}

function buildPayload(url, title, hasBeenRead) {
  return {
    url: String(url).trim(),
    // Send null for blank titles so the API treats the field as absent rather
    // than storing an empty string, which would block a future title fill-in.
    title: (title && String(title).trim() !== '') ? String(title).trim() : null,
    hasBeenRead: hasBeenRead === true
  };
}

function postToApi(apiUrl, payload, rowNumber) {
  try {
    const response = UrlFetchApp.fetch(apiUrl, {
      method: 'post',
      contentType: 'application/json',
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    });

    const statusCode = response.getResponseCode();

    if (statusCode === 201) {
      Logger.log(`Row ${rowNumber}: created — ${payload.url}`);
    } else if (statusCode === 200) {
      Logger.log(`Row ${rowNumber}: existing — ${payload.url}`);
    } else {
      Logger.log(
        `Row ${rowNumber}: ERROR ${statusCode} — ${payload.url} — ` +
        response.getContentText()
      );
    }

    return statusCode;
  } catch (e) {
    Logger.log(`Row ${rowNumber}: ERROR (network) — ${payload.url} — ${e.toString()}`);
    return -1;
  }
}
