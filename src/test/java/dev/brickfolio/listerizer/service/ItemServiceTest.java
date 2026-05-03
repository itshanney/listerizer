package dev.brickfolio.listerizer.service;

import dev.brickfolio.listerizer.api.ItemRequest;
import dev.brickfolio.listerizer.domain.Item;
import dev.brickfolio.listerizer.repository.ItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test Plan Summary
 * -----------------
 * Covered:
 *   - URL validation: null, blank, whitespace-only, missing scheme, missing host, valid HTTP/HTTPS
 *   - createTime validation: null (now valid — defaults to server time), negative epoch (still invalid)
 *   - Delegation: create() passes url and createTime to repository.save() unchanged
 *   - create() returns isNew=true on successful save, isNew=false on duplicate URL
 *   - list() delegates to repository.findAllByOrderByIdAsc() and returns results as-is
 *
 * Not covered:
 *   - hasBeenRead ratchet logic, title fill-blank upsert — covered in integration tests
 *   - createTime server-default value — covered in integration tests
 *   - IllegalStateException path (duplicate URL but row missing after conflict) — race condition
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
        ItemRequest request = new ItemRequest(null, CREATE_TIME, null, null);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("url is required");
    }

    @Test
    void create_throws_when_url_is_empty_string() {
        ItemRequest request = new ItemRequest("", CREATE_TIME, null, null);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("url is required");
    }

    @Test
    void create_throws_when_url_is_whitespace_only() {
        ItemRequest request = new ItemRequest("   ", CREATE_TIME, null, null);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void create_throws_when_url_has_no_scheme() {
        ItemRequest request = new ItemRequest("example.com/article", CREATE_TIME, null, null);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void create_throws_when_url_has_scheme_but_no_host() {
        ItemRequest request = new ItemRequest("nohost:", CREATE_TIME, null, null);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void create_throws_on_syntactically_invalid_url() {
        ItemRequest request = new ItemRequest("not a url at all", CREATE_TIME, null, null);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    // --- createTime validation ---

    @Test
    void create_uses_server_time_when_create_time_is_null() {
        ItemRequest request = new ItemRequest(VALID_URL, null, null, null);
        long before = System.currentTimeMillis() / 1000;
        when(repository.save(any(Item.class))).thenAnswer(inv -> inv.getArgument(0));

        InsertResult result = itemService.create(request);

        long after = System.currentTimeMillis() / 1000;
        assertThat(result.item().createTime()).isBetween(before, after);
    }

    @Test
    void create_throws_when_create_time_is_negative() {
        ItemRequest request = new ItemRequest(VALID_URL, -1L, null, null);
        assertThatThrownBy(() -> itemService.create(request))
                .isInstanceOf(ValidationException.class);
    }

    // --- happy path ---

    @Test
    void create_accepts_http_url() {
        ItemRequest request = new ItemRequest("http://example.com", CREATE_TIME, null, null);
        when(repository.save(any(Item.class))).thenReturn(new Item("http://example.com", CREATE_TIME, null, false));

        InsertResult result = itemService.create(request);

        assertThat(result.isNew()).isTrue();
        assertThat(result.item().url()).isEqualTo("http://example.com");
        assertThat(result.item().createTime()).isEqualTo(CREATE_TIME);
    }

    @Test
    void create_accepts_https_url_with_path_and_query() {
        String url = "https://example.com/path?q=1&page=2";
        ItemRequest request = new ItemRequest(url, CREATE_TIME, null, null);
        when(repository.save(any(Item.class))).thenReturn(new Item(url, CREATE_TIME, null, false));

        InsertResult result = itemService.create(request);

        assertThat(result.isNew()).isTrue();
        assertThat(result.item().url()).isEqualTo(url);
    }

    @Test
    void create_accepts_epoch_zero() {
        ItemRequest request = new ItemRequest(VALID_URL, 0L, null, null);
        when(repository.save(any(Item.class))).thenReturn(new Item(VALID_URL, 0L, null, false));

        InsertResult result = itemService.create(request);

        assertThat(result.isNew()).isTrue();
        assertThat(result.item().createTime()).isEqualTo(0L);
    }

    @Test
    void create_delegates_url_and_create_time_to_repository_unchanged() {
        ItemRequest request = new ItemRequest(VALID_URL, CREATE_TIME, null, null);
        when(repository.save(any(Item.class))).thenReturn(new Item(VALID_URL, CREATE_TIME, null, false));

        itemService.create(request);

        verify(repository).save(argThat(item ->
                item.url().equals(VALID_URL) && item.createTime() == CREATE_TIME));
    }

    @Test
    void create_returns_is_new_false_when_url_already_exists() {
        ItemRequest request = new ItemRequest(VALID_URL, CREATE_TIME, null, null);
        Item existing = new Item(VALID_URL, CREATE_TIME, null, false);
        when(repository.save(any(Item.class))).thenThrow(DataIntegrityViolationException.class);
        when(repository.findByUrl(VALID_URL)).thenReturn(Optional.of(existing));

        InsertResult result = itemService.create(request);

        assertThat(result.isNew()).isFalse();
        assertThat(result.item().url()).isEqualTo(VALID_URL);
    }

    // --- list ---

    @Test
    void list_returns_empty_list_when_repository_is_empty() {
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of());

        assertThat(itemService.list()).isEmpty();
    }

    @Test
    void list_returns_all_items_from_repository() {
        List<Item> items = List.of(
                new Item("https://first.com",  1735689600L, null, false),
                new Item("https://second.com", 1738368000L, null, false)
        );
        when(repository.findAllByOrderByIdAsc()).thenReturn(items);

        assertThat(itemService.list()).isEqualTo(items);
    }
}
