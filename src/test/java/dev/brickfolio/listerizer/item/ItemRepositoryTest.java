package dev.brickfolio.listerizer.item;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test Plan Summary
 * -----------------
 * Covered:
 *   - insertOrFetch: new URL → isNew=true, correct id/url/createTime returned
 *   - insertOrFetch: duplicate URL → isNew=false, original record returned (not the new create_time)
 *   - insertOrFetch: multiple distinct URLs can coexist
 *   - findAll: empty table → empty list
 *   - findAll: items returned in insertion order (id ASC)
 *   - findAll: all fields (id, url, create_time) are correctly mapped
 *
 * Not covered:
 *   - Null create_time (violates NOT NULL; test belongs in integration tests as a 500 case)
 *   - Very long URLs (SQLite TEXT has no practical length limit)
 *   - Concurrent writes (single-connection pool makes this a no-op in tests)
 *
 * Test isolation: each test starts with a cleared items table.
 * The auto-increment sequence is NOT reset between tests — tests must not assert on specific id values.
 */
@SpringBootTest
@ActiveProfiles("test")
class ItemRepositoryTest {

    @Autowired
    private ItemRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearItems() {
        jdbc.update("DELETE FROM items");
    }

    // --- insertOrFetch: new item ---

    @Test
    void insert_new_url_returns_is_new_true() {
        InsertResult result = repository.insertOrFetch("https://example.com", "2026-04-11T10:30:00Z");
        assertThat(result.isNew()).isTrue();
    }

    @Test
    void insert_new_url_returns_the_stored_item() {
        InsertResult result = repository.insertOrFetch("https://example.com", "2026-04-11T10:30:00Z");
        assertThat(result.item().url()).isEqualTo("https://example.com");
        assertThat(result.item().createTime()).isEqualTo("2026-04-11T10:30:00Z");
        assertThat(result.item().id()).isPositive();
    }

    @Test
    void insert_new_url_is_retrievable_via_findAll() {
        repository.insertOrFetch("https://example.com", "2026-04-11T10:30:00Z");

        List<Item> items = repository.findAll();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).url()).isEqualTo("https://example.com");
    }

    // --- insertOrFetch: duplicate URL ---

    @Test
    void insert_duplicate_url_returns_is_new_false() {
        repository.insertOrFetch("https://example.com", "2026-04-11T10:30:00Z");
        InsertResult duplicate = repository.insertOrFetch("https://example.com", "2099-01-01T00:00:00Z");

        assertThat(duplicate.isNew()).isFalse();
    }

    @Test
    void insert_duplicate_url_returns_original_record_not_new_values() {
        InsertResult first = repository.insertOrFetch("https://example.com", "2026-04-11T10:30:00Z");
        InsertResult second = repository.insertOrFetch("https://example.com", "2099-01-01T00:00:00Z");

        assertThat(second.item().id()).isEqualTo(first.item().id());
        assertThat(second.item().createTime()).isEqualTo("2026-04-11T10:30:00Z");
    }

    @Test
    void duplicate_url_does_not_create_an_extra_row() {
        repository.insertOrFetch("https://example.com", "2026-04-11T10:30:00Z");
        repository.insertOrFetch("https://example.com", "2099-01-01T00:00:00Z");

        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void two_distinct_urls_are_stored_independently() {
        repository.insertOrFetch("https://first.com", "2026-01-01T00:00:00Z");
        repository.insertOrFetch("https://second.com", "2026-02-01T00:00:00Z");

        assertThat(repository.findAll()).hasSize(2);
    }

    // --- findAll ---

    @Test
    void findAll_returns_empty_list_when_table_is_empty() {
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void findAll_returns_items_in_insertion_order() {
        repository.insertOrFetch("https://first.com",  "2026-01-01T00:00:00Z");
        repository.insertOrFetch("https://second.com", "2026-02-01T00:00:00Z");
        repository.insertOrFetch("https://third.com",  "2026-03-01T00:00:00Z");

        List<Item> items = repository.findAll();

        assertThat(items).hasSize(3);
        assertThat(items.get(0).url()).isEqualTo("https://first.com");
        assertThat(items.get(1).url()).isEqualTo("https://second.com");
        assertThat(items.get(2).url()).isEqualTo("https://third.com");
    }

    @Test
    void findAll_maps_all_fields_correctly() {
        repository.insertOrFetch("https://example.com", "2026-04-11T10:30:00Z");

        Item item = repository.findAll().get(0);

        assertThat(item.url()).isEqualTo("https://example.com");
        assertThat(item.createTime()).isEqualTo("2026-04-11T10:30:00Z");
        assertThat(item.id()).isPositive();
    }
}
