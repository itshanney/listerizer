package dev.brickfolio.listerizer;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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
 *     - Happy path: 201 with id, url, createTime, title, hasBeenRead in response
 *     - title and hasBeenRead stored and returned correctly when provided
 *     - Missing createTime: server assigns current epoch, returns 201
 *     - Missing title: stored and returned as null
 *     - Missing hasBeenRead: defaults to false
 *     - createTime as string: 400
 *     - Negative createTime: 400
 *     - Missing url: 400
 *     - Null url: 400
 *     - Blank url: 400
 *     - url without scheme: 400
 *     - Malformed JSON: 400
 *     - Empty body: 400
 *     - Empty JSON object: 400
 *     - 400 error body contains error and message fields
 *     - Duplicate URL, no upsert fields: 200, original record unchanged
 *     - Duplicate URL, hasBeenRead false→true: 200, hasBeenRead updated
 *     - Duplicate URL, hasBeenRead not downgraded true→false: 200, hasBeenRead unchanged
 *     - Duplicate URL, title fill-blank: 200, title updated when stored title is null
 *     - Duplicate URL, title not overwritten when already set: 200, title unchanged
 *     - Duplicate URL, createTime and id not overwritten
 *
 *   GET /items
 *     - Empty store: 200 with []
 *     - Populated store: 200 with array in insertion order
 *     - Response schema: each item has id, url, createTime (epoch integer), title, hasBeenRead
 *     - title is null for items stored without a title
 *     - hasBeenRead is false for items stored without hasBeenRead
 *     - Content-Type is application/json
 *
 * Not covered:
 *   - Concurrent upsert (race condition is benign by design; requires thread pool harness)
 *   - Payloads at column size limits (VARCHAR 2048 / 1024)
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ItemApiIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:9.2")
            .withDatabaseName("listerizer")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final long   CREATE_TIME = 1744367400L; // 2026-04-11T10:30:00Z
    private static final String EXAMPLE_URL = "https://example.com";

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
    // POST /items — happy path: new item
    // -------------------------------------------------------------------------

    @Test
    void post_new_item_returns_201_created() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "createTime": 1744367400}
                """);

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void post_new_item_response_body_contains_id_url_and_create_time() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "createTime": 1744367400}
                """);

        JsonNode body = objectMapper.readTree(response.body());
        Assertions.assertThat(body.get("id").asLong()).isPositive();
        Assertions.assertThat(body.get("url").asText()).isEqualTo("https://example.com");
        Assertions.assertThat(body.get("createTime").asLong()).isEqualTo(CREATE_TIME);
    }

    @Test
    void post_new_item_with_title_and_has_been_read_stores_and_returns_both() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "createTime": 1744367400, "title": "Test Article", "hasBeenRead": true}
                """);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        Assertions.assertThat(body.get("title").asText()).isEqualTo("Test Article");
        Assertions.assertThat(body.get("hasBeenRead").asBoolean()).isTrue();
    }

    @Test
    void post_new_item_without_title_returns_null_title() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "createTime": 1744367400}
                """);

        JsonNode body = objectMapper.readTree(response.body());
        Assertions.assertThat(body.get("title").isNull()).isTrue();
    }

    @Test
    void post_new_item_without_has_been_read_defaults_to_false() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "createTime": 1744367400}
                """);

        JsonNode body = objectMapper.readTree(response.body());
        Assertions.assertThat(body.get("hasBeenRead").asBoolean()).isFalse();
    }

    // -------------------------------------------------------------------------
    // POST /items — createTime server default
    // -------------------------------------------------------------------------

    @Test
    void post_without_create_time_returns_201_with_server_assigned_epoch() throws Exception {
        long before = System.currentTimeMillis() / 1000;
        HttpResponse<String> response = post("""
                {"url": "https://example.com"}
                """);
        long after = System.currentTimeMillis() / 1000;

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        long storedCreateTime = body.get("createTime").asLong();
        Assertions.assertThat(storedCreateTime).isBetween(before, after);
    }

    // -------------------------------------------------------------------------
    // POST /items — duplicate URL upsert
    // -------------------------------------------------------------------------

    @Test
    void post_duplicate_url_returns_200_ok() throws Exception {
        post("""
                {"url": "https://example.com", "createTime": 1744367400}
                """);

        HttpResponse<String> duplicate = post("""
                {"url": "https://example.com", "createTime": 2000000000}
                """);

        assertThat(duplicate.statusCode()).isEqualTo(200);
    }

    @Test
    void post_duplicate_url_does_not_overwrite_create_time_or_id() throws Exception {
        HttpResponse<String> first = post("""
                {"url": "https://example.com", "createTime": 1744367400}
                """);
        HttpResponse<String> second = post("""
                {"url": "https://example.com", "createTime": 2000000000}
                """);

        JsonNode firstBody  = objectMapper.readTree(first.body());
        JsonNode secondBody = objectMapper.readTree(second.body());

        Assertions.assertThat(secondBody.get("id").asLong()).isEqualTo(firstBody.get("id").asLong());
        Assertions.assertThat(secondBody.get("createTime").asLong()).isEqualTo(CREATE_TIME);
    }

    @Test
    void post_duplicate_url_upgrades_has_been_read_from_false_to_true() throws Exception {
        post("""
                {"url": "https://example.com", "createTime": 1744367400, "hasBeenRead": false}
                """);

        HttpResponse<String> second = post("""
                {"url": "https://example.com", "createTime": 1744367400, "hasBeenRead": true}
                """);

        JsonNode body = objectMapper.readTree(second.body());
        Assertions.assertThat(body.get("hasBeenRead").asBoolean()).isTrue();
    }

    @Test
    void post_duplicate_url_does_not_downgrade_has_been_read_from_true_to_false() throws Exception {
        post("""
                {"url": "https://example.com", "createTime": 1744367400, "hasBeenRead": true}
                """);

        HttpResponse<String> second = post("""
                {"url": "https://example.com", "createTime": 1744367400, "hasBeenRead": false}
                """);

        JsonNode body = objectMapper.readTree(second.body());
        Assertions.assertThat(body.get("hasBeenRead").asBoolean()).isTrue();
    }

    @Test
    void post_duplicate_url_fills_blank_title_when_stored_title_is_null() throws Exception {
        post("""
                {"url": "https://example.com", "createTime": 1744367400}
                """);

        HttpResponse<String> second = post("""
                {"url": "https://example.com", "createTime": 1744367400, "title": "New Title"}
                """);

        JsonNode body = objectMapper.readTree(second.body());
        Assertions.assertThat(body.get("title").asText()).isEqualTo("New Title");
    }

    @Test
    void post_duplicate_url_does_not_overwrite_existing_title() throws Exception {
        post("""
                {"url": "https://example.com", "createTime": 1744367400, "title": "Original Title"}
                """);

        HttpResponse<String> second = post("""
                {"url": "https://example.com", "createTime": 1744367400, "title": "New Title"}
                """);

        JsonNode body = objectMapper.readTree(second.body());
        Assertions.assertThat(body.get("title").asText()).isEqualTo("Original Title");
    }

    @Test
    void post_duplicate_url_response_reflects_post_upsert_state() throws Exception {
        post("""
                {"url": "https://example.com", "createTime": 1744367400, "hasBeenRead": false}
                """);

        HttpResponse<String> second = post("""
                {"url": "https://example.com", "createTime": 1744367400, "hasBeenRead": true, "title": "Added Title"}
                """);

        JsonNode body = objectMapper.readTree(second.body());
        Assertions.assertThat(body.get("hasBeenRead").asBoolean()).isTrue();
        Assertions.assertThat(body.get("title").asText()).isEqualTo("Added Title");
    }

    @Test
    void post_duplicate_url_is_idempotent_when_nothing_changes() throws Exception {
        post("""
                {"url": "https://example.com", "createTime": 1744367400, "hasBeenRead": true, "title": "Title"}
                """);

        HttpResponse<String> second = post("""
                {"url": "https://example.com", "createTime": 1744367400, "hasBeenRead": false}
                """);
        HttpResponse<String> third = post("""
                {"url": "https://example.com", "createTime": 1744367400, "hasBeenRead": false}
                """);

        JsonNode secondBody = objectMapper.readTree(second.body());
        JsonNode thirdBody  = objectMapper.readTree(third.body());
        Assertions.assertThat(thirdBody.get("hasBeenRead").asBoolean()).isEqualTo(secondBody.get("hasBeenRead").asBoolean());
        Assertions.assertThat(thirdBody.get("title").asText()).isEqualTo(secondBody.get("title").asText());
    }

    // -------------------------------------------------------------------------
    // POST /items — validation errors → 400
    // -------------------------------------------------------------------------

    @Test
    void post_missing_url_field_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"createTime": 1744367400}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_null_url_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": null, "createTime": 1744367400}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_blank_url_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "", "createTime": 1744367400}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_url_without_scheme_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "example.com/article", "createTime": 1744367400}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_create_time_as_string_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "createTime": "not-a-number"}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_negative_epoch_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "createTime": -1}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_400_error_body_contains_error_and_message_fields() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "not-a-url", "createTime": 1744367400}
                """);

        JsonNode body = objectMapper.readTree(response.body());
        Assertions.assertThat(body.get("error").asText()).isEqualTo("invalid_request");
        Assertions.assertThat(body.has("message")).isTrue();
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
        Assertions.assertThat(body.isArray()).isTrue();
        Assertions.assertThat(body.isEmpty()).isTrue();
    }

    @Test
    void get_returns_all_stored_items() throws Exception {
        post("""
                {"url": "https://first.com",  "createTime": 1735689600}
                """);
        post("""
                {"url": "https://second.com", "createTime": 1738368000}
                """);

        JsonNode body = objectMapper.readTree(get("/items").body());
        Assertions.assertThat(body.isArray()).isTrue();
        Assertions.assertThat(body.size()).isEqualTo(2);
    }

    @Test
    void get_returns_items_in_insertion_order() throws Exception {
        post("""
                {"url": "https://first.com",  "createTime": 1735689600}
                """);
        post("""
                {"url": "https://second.com", "createTime": 1738368000}
                """);
        post("""
                {"url": "https://third.com",  "createTime": 1740787200}
                """);

        JsonNode body = objectMapper.readTree(get("/items").body());

        Assertions.assertThat(body.get(0).get("url").asText()).isEqualTo("https://first.com");
        Assertions.assertThat(body.get(1).get("url").asText()).isEqualTo("https://second.com");
        Assertions.assertThat(body.get(2).get("url").asText()).isEqualTo("https://third.com");
    }

    @Test
    void get_response_items_have_expected_fields() throws Exception {
        post("""
                {"url": "https://example.com", "createTime": 1744367400, "title": "An Article", "hasBeenRead": true}
                """);

        JsonNode item = objectMapper.readTree(get("/items").body()).get(0);
        Assertions.assertThat(item.has("id")).isTrue();
        Assertions.assertThat(item.has("url")).isTrue();
        Assertions.assertThat(item.has("createTime")).isTrue();
        Assertions.assertThat(item.has("title")).isTrue();
        Assertions.assertThat(item.has("hasBeenRead")).isTrue();
    }

    @Test
    void get_create_time_is_returned_as_epoch_integer() throws Exception {
        post("""
                {"url": "https://example.com", "createTime": 1744367400}
                """);

        JsonNode item = objectMapper.readTree(get("/items").body()).get(0);
        Assertions.assertThat(item.get("createTime").isNumber()).isTrue();
        Assertions.assertThat(item.get("createTime").asLong()).isEqualTo(CREATE_TIME);
    }

    @Test
    void get_returns_null_title_for_items_stored_without_title() throws Exception {
        post("""
                {"url": "https://example.com", "createTime": 1744367400}
                """);

        JsonNode item = objectMapper.readTree(get("/items").body()).get(0);
        Assertions.assertThat(item.get("title").isNull()).isTrue();
    }

    @Test
    void get_returns_false_has_been_read_for_items_stored_without_has_been_read() throws Exception {
        post("""
                {"url": "https://example.com", "createTime": 1744367400}
                """);

        JsonNode item = objectMapper.readTree(get("/items").body()).get(0);
        Assertions.assertThat(item.get("hasBeenRead").asBoolean()).isFalse();
    }

    @Test
    void get_response_content_type_is_application_json() throws Exception {
        String contentType = get("/items").headers().firstValue("Content-Type").orElse("");
        assertThat(contentType).contains("application/json");
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
