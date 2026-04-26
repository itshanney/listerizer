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
 *   - create_time validation: null (missing field), negative epoch
 *   - Delegation: create() passes url and createTime to the repository unchanged
 *   - list() delegates to repository and returns results as-is
 *
 * Not covered:
 *   - Idempotency logic — owned entirely by ItemRepository, not by ItemService
 */
@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    private static final String VALID_URL   = "https://example.com/article";
    private static final long   CREATE_TIME = 1744367400L; // 2026-04-11T10:30:00Z

    @Mock
    private ItemRepository repository;

    @InjectMocks
    private ItemService itemService;

    // --- URL validation ---

    @Test
    void create_throws_when_url_is_null() {
        ItemRequest request = new ItemRequest(null, CREATE_TIME);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("url is required");
    }

    @Test
    void create_throws_when_url_is_empty_string() {
        ItemRequest request = new ItemRequest("", CREATE_TIME);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("url is required");
    }

    @Test
    void create_throws_when_url_is_whitespace_only() {
        ItemRequest request = new ItemRequest("   ", CREATE_TIME);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void create_throws_when_url_has_no_scheme() {
        ItemRequest request = new ItemRequest("example.com/article", CREATE_TIME);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void create_throws_when_url_has_scheme_but_no_host() {
        ItemRequest request = new ItemRequest("nohost:", CREATE_TIME);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void create_throws_on_syntactically_invalid_url() {
        ItemRequest request = new ItemRequest("not a url at all", CREATE_TIME);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    // --- create_time validation ---

    @Test
    void create_throws_when_create_time_is_null() {
        ItemRequest request = new ItemRequest(VALID_URL, null);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("create_time is required");
    }

    @Test
    void create_throws_when_create_time_is_negative() {
        ItemRequest request = new ItemRequest(VALID_URL, -1L);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    // --- happy path ---

    @Test
    void create_accepts_http_url() {
        ItemRequest request = new ItemRequest("http://example.com", CREATE_TIME);
        InsertResult expected = new InsertResult(new Item(1, "http://example.com", CREATE_TIME), true);
        when(repository.insertOrFetch("http://example.com", CREATE_TIME)).thenReturn(expected);

        InsertResult result = itemService.create(request);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void create_accepts_https_url_with_path_and_query() {
        String url = "https://example.com/path?q=1&page=2";
        ItemRequest request = new ItemRequest(url, CREATE_TIME);
        InsertResult expected = new InsertResult(new Item(1, url, CREATE_TIME), true);
        when(repository.insertOrFetch(url, CREATE_TIME)).thenReturn(expected);

        InsertResult result = itemService.create(request);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void create_accepts_epoch_zero() {
        ItemRequest request = new ItemRequest(VALID_URL, 0L);
        InsertResult expected = new InsertResult(new Item(1, VALID_URL, 0L), true);
        when(repository.insertOrFetch(VALID_URL, 0L)).thenReturn(expected);

        InsertResult result = itemService.create(request);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void create_delegates_url_and_create_time_to_repository_unchanged() {
        ItemRequest request = new ItemRequest(VALID_URL, CREATE_TIME);
        InsertResult expected = new InsertResult(new Item(42, VALID_URL, CREATE_TIME), true);
        when(repository.insertOrFetch(VALID_URL, CREATE_TIME)).thenReturn(expected);

        itemService.create(request);

        verify(repository).insertOrFetch(VALID_URL, CREATE_TIME);
    }

    @Test
    void create_returns_repository_insert_result_unchanged() {
        ItemRequest request = new ItemRequest(VALID_URL, CREATE_TIME);
        InsertResult expected = new InsertResult(new Item(7, VALID_URL, CREATE_TIME), false);
        when(repository.insertOrFetch(VALID_URL, CREATE_TIME)).thenReturn(expected);

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
                new Item(1, "https://first.com",  1735689600L),
                new Item(2, "https://second.com", 1738368000L)
        );
        when(repository.findAll()).thenReturn(items);

        assertThat(itemService.list()).isEqualTo(items);
    }
}
