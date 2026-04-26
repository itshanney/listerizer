package dev.brickfolio.listerizer.service;

import dev.brickfolio.listerizer.api.ItemRequest;
import dev.brickfolio.listerizer.domain.Item;
import dev.brickfolio.listerizer.repository.ItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test Plan Summary
 * -----------------
 * Covered:
 *   - URL validation: null, blank, whitespace-only, missing scheme, missing host, valid HTTP/HTTPS
 *   - Delegation: create() passes the normalized request to the repository unchanged
 *   - list() delegates to repository and returns results as-is
 *
 * Not covered:
 *   - create_time null — service does not validate it; null propagates to the repository
 *     and causes a 500 at the DB layer rather than a 400. This is a bug that the
 *     integration tests expose.
 *   - Idempotency logic — owned entirely by ItemRepository, not by ItemService
 */
@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    private static final String VALID_URL = "https://example.com/article";
    private static final String NORMALIZED_CREATE_TIME = "2026-04-11T10:30:00Z";

    @Mock
    private ItemRepository repository;

    @InjectMocks
    private ItemService itemService;

    // --- URL validation ---

    @Test
    void create_throws_when_url_is_null() {
        ItemRequest request = new ItemRequest(null, NORMALIZED_CREATE_TIME);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("url is required");
    }

    @Test
    void create_throws_when_url_is_empty_string() {
        ItemRequest request = new ItemRequest("", NORMALIZED_CREATE_TIME);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("url is required");
    }

    @Test
    void create_throws_when_url_is_whitespace_only() {
        ItemRequest request = new ItemRequest("   ", NORMALIZED_CREATE_TIME);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void create_throws_when_url_has_no_scheme() {
        // A bare hostname with no scheme fails URI parsing
        ItemRequest request = new ItemRequest("example.com/article", NORMALIZED_CREATE_TIME);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void create_throws_when_url_has_scheme_but_no_host() {
        // "//example.com" has a host but no scheme; "scheme:" alone has no host
        ItemRequest request = new ItemRequest("nohost:", NORMALIZED_CREATE_TIME);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void create_throws_on_syntactically_invalid_url() {
        ItemRequest request = new ItemRequest("not a url at all", NORMALIZED_CREATE_TIME);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void create_accepts_http_url() {
        ItemRequest request = new ItemRequest("http://example.com", NORMALIZED_CREATE_TIME);
        InsertResult expected = new InsertResult(new Item(1, "http://example.com", NORMALIZED_CREATE_TIME), true);
        when(repository.insertOrFetch("http://example.com", NORMALIZED_CREATE_TIME)).thenReturn(expected);

        InsertResult result = itemService.create(request);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void create_accepts_https_url_with_path_and_query() {
        String url = "https://example.com/path?q=1&page=2";
        ItemRequest request = new ItemRequest(url, NORMALIZED_CREATE_TIME);
        InsertResult expected = new InsertResult(new Item(1, url, NORMALIZED_CREATE_TIME), true);
        when(repository.insertOrFetch(url, NORMALIZED_CREATE_TIME)).thenReturn(expected);

        InsertResult result = itemService.create(request);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void create_delegates_url_and_create_time_to_repository_unchanged() {
        ItemRequest request = new ItemRequest(VALID_URL, NORMALIZED_CREATE_TIME);
        InsertResult expected = new InsertResult(new Item(42, VALID_URL, NORMALIZED_CREATE_TIME), true);
        when(repository.insertOrFetch(VALID_URL, NORMALIZED_CREATE_TIME)).thenReturn(expected);

        itemService.create(request);

        verify(repository).insertOrFetch(VALID_URL, NORMALIZED_CREATE_TIME);
    }

    @Test
    void create_returns_repository_insert_result_unchanged() {
        ItemRequest request = new ItemRequest(VALID_URL, NORMALIZED_CREATE_TIME);
        InsertResult expected = new InsertResult(new Item(7, VALID_URL, NORMALIZED_CREATE_TIME), false);
        when(repository.insertOrFetch(VALID_URL, NORMALIZED_CREATE_TIME)).thenReturn(expected);

        InsertResult result = itemService.create(request);

        assertThat(result.isNew()).isFalse();
        assertThat(result.item().id()).isEqualTo(7);
    }

    // --- list ---

    @Test
    void list_returns_empty_list_when_repository_is_empty() {
        when(repository.findAll()).thenReturn(List.of());

        assertThat(itemService.list()).isEmpty();
    }

    @Test
    void list_returns_all_items_from_repository() {
        List<Item> items = List.of(
                new Item(1, "https://first.com", "2026-01-01T00:00:00Z"),
                new Item(2, "https://second.com", "2026-02-01T00:00:00Z")
        );
        when(repository.findAll()).thenReturn(items);

        assertThat(itemService.list()).isEqualTo(items);
    }
}
