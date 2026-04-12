package com.listerizer.api.item;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ItemRepository {

    private static final RowMapper<Item> ITEM_ROW_MAPPER =
            (rs, rowNum) -> new Item(
                    rs.getLong("id"),
                    rs.getString("url"),
                    rs.getString("create_time")
            );

    private final JdbcTemplate jdbc;

    public ItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new item, ignoring the insert if the URL already exists (SQLite INSERT OR IGNORE).
     * Returns the inserted row on success, or the pre-existing row on duplicate.
     */
    public InsertResult insertOrFetch(String url, String createTime) {
        int affected = jdbc.update(
                "INSERT OR IGNORE INTO items (url, create_time) VALUES (?, ?)",
                url, createTime
        );

        if (affected == 1) {
            Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            return new InsertResult(new Item(id, url, createTime), true);
        }

        // URL already existed — return the stored record unchanged.
        Item existing = jdbc.queryForObject(
                "SELECT id, url, create_time FROM items WHERE url = ?",
                ITEM_ROW_MAPPER,
                url
        );
        return new InsertResult(existing, false);
    }

    public List<Item> findAll() {
        return jdbc.query(
                "SELECT id, url, create_time FROM items ORDER BY id ASC",
                ITEM_ROW_MAPPER
        );
    }
}
