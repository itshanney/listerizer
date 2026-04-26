package dev.brickfolio.listerizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test Plan Summary
 * -----------------
 * Covered:
 *   POST /items
 *     - Happy path: 201 Created with id, url, create_time in response body
 *     - Duplicate URL: 200 OK returning the original record (original create_time, same id)
 *     - create_time as epoch integer: normalized to ISO 8601 UTC in response
 *     - create_time as ISO 8601 with offset: normalized to UTC in response
 *     - Missing url field: 400 with error/message body
 *     - Invalid url (no scheme): 400
 *     - Missing create_time field: BUG — returns 500 instead of 400 (test documents current behavior)
 *     - Malformed JSON: 400
 *     - Empty body: 400
 *     - Empty JSON object: 400
 *
 *   GET /items
 *     - Empty store: 200 with []
 *     - Populated store: 200 with array in insertion order
 *     - Response schema: each item has id, url, create_time
 *     - Content-Type of response is application/json
 *     - create_time returned in ISO 8601 UTC format
 *
 * Not covered:
 *   - Concurrent requests (would require a thread-pool test harness)
 *   - Very large payloads
 *   - SQLite file-not-found / corruption scenarios (operational, not unit-testable)
 *
 * Known bug documented by test:
 *   post_missing_create_time_returns_500_documenting_known_bug — missing create_time bypasses
 *   CreateTimeDeserializer (absent field ≠ invalid value), so createTime is null in ItemRequest.
 *   ItemService does not validate createTime for null, so null is passed to the repository
 *   and hits SQLite's NOT NULL constraint → unhandled DataIntegrityViolationException → 500.
 *   Fix: null-check createTime in ItemService.create() and throw ValidationException.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ItemApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        jdbc.update("DELETE FROM items");
    }

    // -------------------------------------------------------------------------
    // POST /items — happy path
    // -------------------------------------------------------------------------

    @Test
    void post_new_item_returns_201_created() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "create_time": "2026-04-11T10:30:00Z"}
                """);

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void post_new_item_response_body_contains_id_url_and_create_time() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "create_time": "2026-04-11T10:30:00Z"}
                """);

        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.has("id")).isTrue();
        assertThat(body.get("id").asLong()).isPositive();
        assertThat(body.get("url").asText()).isEqualTo("https://example.com");
        assertThat(body.get("create_time").asText()).isEqualTo("2026-04-11T10:30:00Z");
    }

    @Test
    void post_create_time_as_epoch_integer_is_normalized_to_iso8601_utc() throws Exception {
        // 1000000000 seconds since epoch = 2001-09-09T01:46:40Z
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "create_time": 1000000000}
                """);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("create_time").asText()).isEqualTo("2001-09-09T01:46:40Z");
    }

    @Test
    void post_create_time_with_offset_is_normalized_to_utc() throws Exception {
        // +05:00 means 15:30 local = 10:30 UTC
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "create_time": "2026-04-11T15:30:00+05:00"}
                """);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("create_time").asText()).isEqualTo("2026-04-11T10:30:00Z");
    }

    // -------------------------------------------------------------------------
    // POST /items — duplicate URL (idempotency)
    // -------------------------------------------------------------------------

    @Test
    void post_duplicate_url_returns_200_ok() throws Exception {
        post("""
                {"url": "https://example.com", "create_time": "2026-04-11T10:30:00Z"}
                """);

        HttpResponse<String> duplicate = post("""
                {"url": "https://example.com", "create_time": "2099-12-31T00:00:00Z"}
                """);

        assertThat(duplicate.statusCode()).isEqualTo(200);
    }

    @Test
    void post_duplicate_url_returns_original_record_not_the_new_values() throws Exception {
        HttpResponse<String> first = post("""
                {"url": "https://example.com", "create_time": "2026-04-11T10:30:00Z"}
                """);
        HttpResponse<String> second = post("""
                {"url": "https://example.com", "create_time": "2099-12-31T00:00:00Z"}
                """);

        JsonNode firstBody  = objectMapper.readTree(first.body());
        JsonNode secondBody = objectMapper.readTree(second.body());

        assertThat(secondBody.get("id").asLong()).isEqualTo(firstBody.get("id").asLong());
        assertThat(secondBody.get("create_time").asText()).isEqualTo("2026-04-11T10:30:00Z");
    }

    // -------------------------------------------------------------------------
    // POST /items — validation errors → 400
    // -------------------------------------------------------------------------

    @Test
    void post_missing_url_field_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"create_time": "2026-04-11T10:30:00Z"}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_null_url_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": null, "create_time": "2026-04-11T10:30:00Z"}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_blank_url_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "", "create_time": "2026-04-11T10:30:00Z"}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_url_without_scheme_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "example.com/article", "create_time": "2026-04-11T10:30:00Z"}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_invalid_create_time_string_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "create_time": "not-a-date"}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_negative_epoch_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "create_time": -1}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_400_error_body_contains_error_and_message_fields() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "not-a-url", "create_time": "2026-04-11T10:30:00Z"}
                """);

        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.has("error")).isTrue();
        assertThat(body.has("message")).isTrue();
        assertThat(body.get("error").asText()).isEqualTo("invalid_request");
    }

    @Test
    void post_malformed_json_returns_400() throws Exception {
        HttpResponse<String> response = post("{this is not json}");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_empty_body_returns_400() throws Exception {
        HttpResponse<String> response = post("");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_empty_json_object_returns_400() throws Exception {
        HttpResponse<String> response = post("{}");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    /**
     * BUG: Missing create_time bypasses CreateTimeDeserializer (absent field ≠ invalid value).
     * ItemRequest.createTime() is null, ItemService does not validate it, and the null
     * propagates to SQLite's NOT NULL constraint → DataIntegrityViolationException with no
     * exception mapper → 500 Internal Server Error.
     *
     * Fix: add null-check for createTime in ItemService.create() and throw ValidationException.
     * When fixed, update this assertion from 500 to 400.
     */
    @Test
    void post_missing_create_time_returns_500_documenting_known_bug() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com"}
                """);

        assertThat(response.statusCode()).isEqualTo(500);
    }

    // -------------------------------------------------------------------------
    // GET /items
    // -------------------------------------------------------------------------

    @Test
    void get_returns_200_ok() throws Exception {
        assertThat(get("/items").statusCode()).isEqualTo(200);
    }

    @Test
    void get_returns_empty_array_when_no_items_stored() throws Exception {
        JsonNode body = objectMapper.readTree(get("/items").body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.isEmpty()).isTrue();
    }

    @Test
    void get_returns_all_stored_items() throws Exception {
        post("""
                {"url": "https://first.com", "create_time": "2026-01-01T00:00:00Z"}
                """);
        post("""
                {"url": "https://second.com", "create_time": "2026-02-01T00:00:00Z"}
                """);

        JsonNode body = objectMapper.readTree(get("/items").body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(2);
    }

    @Test
    void get_returns_items_in_insertion_order() throws Exception {
        post("""
                {"url": "https://first.com",  "create_time": "2026-01-01T00:00:00Z"}
                """);
        post("""
                {"url": "https://second.com", "create_time": "2026-02-01T00:00:00Z"}
                """);
        post("""
                {"url": "https://third.com",  "create_time": "2026-03-01T00:00:00Z"}
                """);

        JsonNode body = objectMapper.readTree(get("/items").body());

        assertThat(body.get(0).get("url").asText()).isEqualTo("https://first.com");
        assertThat(body.get(1).get("url").asText()).isEqualTo("https://second.com");
        assertThat(body.get(2).get("url").asText()).isEqualTo("https://third.com");
    }

    @Test
    void get_response_items_have_id_url_and_create_time_fields() throws Exception {
        post("""
                {"url": "https://example.com", "create_time": "2026-04-11T10:30:00Z"}
                """);

        JsonNode item = objectMapper.readTree(get("/items").body()).get(0);
        assertThat(item.has("id")).isTrue();
        assertThat(item.has("url")).isTrue();
        assertThat(item.has("create_time")).isTrue();
    }

    @Test
    void get_response_content_type_is_application_json() throws Exception {
        String contentType = get("/items").headers().firstValue("Content-Type").orElse("");
        assertThat(contentType).contains("application/json");
    }

    @Test
    void get_create_time_is_returned_as_iso8601_utc() throws Exception {
        // Epoch integer posted; GET must return ISO 8601 UTC string, not the raw integer
        post("""
                {"url": "https://example.com", "create_time": 0}
                """);

        JsonNode item = objectMapper.readTree(get("/items").body()).get(0);
        assertThat(item.get("create_time").asText()).isEqualTo("1970-01-01T00:00:00Z");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HttpResponse<String> post(String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url("/items")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
