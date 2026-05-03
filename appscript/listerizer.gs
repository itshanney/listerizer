function getNextUnreadArticle() {
  const apiUrl   = PropertiesService.getScriptProperties().getProperty('LISTERIZER_API_URL');
  const response = UrlFetchApp.fetch(apiUrl.concat("/unread/random"), {
      method: 'get',
      contentType: 'application/json',
      muteHttpExceptions: true
    });

  const jsonObject = JSON.parse(response.getContentText());
  Logger.log("Next Unread Article: " + JSON.stringify(jsonObject));
  return jsonObject;
}

function runListerizer() {
  // Get script properties
  const props = PropertiesService.getScriptProperties();
  const GEMINI_API_KEY = props.getProperty('GEMINI_API_KEY');
  const JSON_FOLDER_ID = props.getProperty('JSON_FOLDER_ID');

  if (!GEMINI_API_KEY || !JSON_FOLDER_ID) {
    Logger.log("Missing GEMINI_API_KEY or JSON_FOLDER_ID script property. Please add them in project settings.");
    return;
  }

  // Pick next unread article
  const nextArticle = getNextUnreadArticle();
  Logger.log(`Next article [${nextArticle.title}]: ` + nextArticle.url)

  // Fetch content from the URL
  let pageContent = "";
  try {
    const response = UrlFetchApp.fetch(nextArticle.url, { muteHttpExceptions: true });
    if (response.getResponseCode() !== 200) {
       Logger.log("Failed to fetch page. HTTP " + response.getResponseCode());
       return;
    }
    const htmlText = response.getContentText();
    // Rough HTML sanitization to remove scripts, styles, and tags
    pageContent = htmlText.replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, "")
                          .replace(/<style\b[^<]*(?:(?!<\/style>)<[^<]*)*<\/style>/gi, "")
                          .replace(/<[^>]+>/g, " ")
                          .replace(/\s+/g, ' ')
                          .trim();
  } catch(e) {
    Logger.log("Error fetching URL: " + e.toString());
    return;
  }

  // Ensure we don't blow perfectly fine token limits (Gemini 2.0 Flash easily handles up to 1M, but we'll cap just to be safe)
  const MAX_CHARS = 100000;
  if (pageContent.length > MAX_CHARS) {
    pageContent = pageContent.substring(0, MAX_CHARS) + "... [Truncated]";
  }

  // Gemini API summary
  const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent`;

  const prompt = `
  You are a Senior Technical Chief of Staff. Your task is to distill complex articles into high-density, "zero-fluff" intelligence briefings for a busy CTO/CEO.
  Context: Below is the scraped text from a bookmarked article.
  Objective: Provide a "Deep-Dive TL;DR" that ensures no critical technical or business detail is missed, while respecting the reader's time.
  Formatting Requirements:
  - The 'So What?': One sentence on why this matters to a tech leader right now
  - Core Thesis: The primary argument or discovery of the piece.
  - Critical Technical Details: Bulleted list of the "how." Include specific metrics, architectural choices, or data points mentioned. Do not generalize.
  - Business & Strategic Impact: How does this affect the industry landscape, Moores Law, unit economics, or talent acquisition?
  - The Gotcha: Any hidden risks, trade-offs, or dissenting views mentioned in the text.
  Constraint: Use professional, punchy language. Avoid introductory phrases like "The article discusses..." or "In conclusion...
  Article Text: ${pageContent}`;

  const payload = {
    contents: [{
      parts: [{
        text: prompt
      }]
    }],
    generationConfig: {
      temperature: 0.3
    }
  };

  const options = {
    method: 'post',
    contentType: 'application/json',
    payload: JSON.stringify(payload),
    muteHttpExceptions: true,
    headers: {
      'x-goog-api-key': GEMINI_API_KEY
    },
  };

  let summary = "";
  try {
    const geminiResponse = UrlFetchApp.fetch(geminiUrl, options);
    const geminiJson = JSON.parse(geminiResponse.getContentText());

    if (geminiJson.candidates && geminiJson.candidates.length > 0) {
      summary = geminiJson.candidates[0].content.parts[0].text;
      Logger.log(summary);
    } else {
      Logger.log("Gemini API returned an unexpected response: " + geminiResponse.getContentText());
      return;
    }
  } catch (e) {
    Logger.log("Gemini API error: " + e.toString());
    return;
  }

  // Send an email with the summary
  const userEmail = Session.getEffectiveUser().getEmail();
  const subject = `Listerizer - ${nextArticle.title}`;
  const body = `## ${nextArticle.title}\n[${nextArticle.url}](${nextArticle.url})\n\n---\n\n${summary}`;
  const showdownConverter = new showdown.Converter();
  const html = showdownConverter.makeHtml(body);

  MailApp.sendEmail({
    to: userEmail,
    subject: subject,
    htmlBody: html
  });

  Logger.log("Successfully processed and emailed TL;DR for: " + nextArticle.title);
}
