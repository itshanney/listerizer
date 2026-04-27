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
 *     - Happy path: 201 Created with id, url, create_time (epoch integer) in response body
 *     - Duplicate URL: 200 OK returning the original record (original create_time, same id)
 *     - create_time as string: 400 (Jackson cannot deserialize string as long)
 *     - Negative epoch: 400
 *     - Missing create_time field: 400 (null-checked in ItemService)
 *     - Missing url field: 400
 *     - Invalid url (no scheme): 400
 *     - Malformed JSON: 400
 *     - Empty body: 400
 *     - Empty JSON object: 400
 *
 *   GET /items
 *     - Empty store: 200 with []
 *     - Populated store: 200 with array in insertion order
 *     - Response schema: each item has id (long), url (string), create_time (epoch integer)
 *     - Content-Type of response is application/json
 *
 * Not covered:
 *   - Concurrent requests (would require a thread-pool test harness)
 *   - Very large payloads
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

    private static final long CREATE_TIME = 1744367400L; // 2026-04-11T10:30:00Z

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
                {"url": "https://example.com", "create_time": 1744367400}
                """);

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void post_new_item_response_body_contains_id_url_and_create_time() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "create_time": 1744367400}
                """);

        JsonNode body = objectMapper.readTree(response.body());
        Assertions.assertThat(body.get("id").asLong()).isPositive();
        Assertions.assertThat(body.get("url").asText()).isEqualTo("https://example.com");
        Assertions.assertThat(body.get("create_time").asLong()).isEqualTo(CREATE_TIME);
    }

    // -------------------------------------------------------------------------
    // POST /items — duplicate URL (idempotency)
    // -------------------------------------------------------------------------

    @Test
    void post_duplicate_url_returns_200_ok() throws Exception {
        post("""
                {"url": "https://example.com", "create_time": 1744367400}
                """);

        HttpResponse<String> duplicate = post("""
                {"url": "https://example.com", "create_time": 2000000000}
                """);

        assertThat(duplicate.statusCode()).isEqualTo(200);
    }

    @Test
    void post_duplicate_url_returns_original_record_not_the_new_values() throws Exception {
        HttpResponse<String> first = post("""
                {"url": "https://example.com", "create_time": 1744367400}
                """);
        HttpResponse<String> second = post("""
                {"url": "https://example.com", "create_time": 2000000000}
                """);

        JsonNode firstBody  = objectMapper.readTree(first.body());
        JsonNode secondBody = objectMapper.readTree(second.body());

        Assertions.assertThat(secondBody.get("id").asLong()).isEqualTo(firstBody.get("id").asLong());
        Assertions.assertThat(secondBody.get("create_time").asLong()).isEqualTo(CREATE_TIME);
    }

    // -------------------------------------------------------------------------
    // POST /items — validation errors → 400
    // -------------------------------------------------------------------------

    @Test
    void post_missing_url_field_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"create_time": 1744367400}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_null_url_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": null, "create_time": 1744367400}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_blank_url_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "", "create_time": 1744367400}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_url_without_scheme_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "example.com/article", "create_time": 1744367400}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_create_time_as_string_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com", "create_time": "not-a-number"}
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
    void post_missing_create_time_returns_400() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "https://example.com"}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void post_400_error_body_contains_error_and_message_fields() throws Exception {
        HttpResponse<String> response = post("""
                {"url": "not-a-url", "create_time": 1744367400}
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
                {"url": "https://first.com",  "create_time": 1735689600}
                """);
        post("""
                {"url": "https://second.com", "create_time": 1738368000}
                """);

        JsonNode body = objectMapper.readTree(get("/items").body());
        Assertions.assertThat(body.isArray()).isTrue();
        Assertions.assertThat(body.size()).isEqualTo(2);
    }

    @Test
    void get_returns_items_in_insertion_order() throws Exception {
        post("""
                {"url": "https://first.com",  "create_time": 1735689600}
                """);
        post("""
                {"url": "https://second.com", "create_time": 1738368000}
                """);
        post("""
                {"url": "https://third.com",  "create_time": 1740787200}
                """);

        JsonNode body = objectMapper.readTree(get("/items").body());

        Assertions.assertThat(body.get(0).get("url").asText()).isEqualTo("https://first.com");
        Assertions.assertThat(body.get(1).get("url").asText()).isEqualTo("https://second.com");
        Assertions.assertThat(body.get(2).get("url").asText()).isEqualTo("https://third.com");
    }

    @Test
    void get_response_items_have_id_url_and_create_time_fields() throws Exception {
        post("""
                {"url": "https://example.com", "create_time": 1744367400}
                """);

        JsonNode item = objectMapper.readTree(get("/items").body()).get(0);
        Assertions.assertThat(item.has("id")).isTrue();
        Assertions.assertThat(item.has("url")).isTrue();
        Assertions.assertThat(item.has("create_time")).isTrue();
    }

    @Test
    void get_create_time_is_returned_as_epoch_integer() throws Exception {
        post("""
                {"url": "https://example.com", "create_time": 1744367400}
                """);

        JsonNode item = objectMapper.readTree(get("/items").body()).get(0);
        Assertions.assertThat(item.get("create_time").isNumber()).isTrue();
        Assertions.assertThat(item.get("create_time").asLong()).isEqualTo(CREATE_TIME);
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
