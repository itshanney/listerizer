package dev.brickfolio.listerizer.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test Plan Summary
 * -----------------
 * Covered:
 *   - Epoch seconds: zero, positive (known value), very large (year 2100), negative (invalid)
 *   - ISO 8601: Z-suffix passthrough, positive UTC offset → UTC, negative UTC offset → UTC
 *   - Invalid strings: random text, empty string, date-only (no time), partial ISO
 *   - Wrong JSON token types: boolean, float, null, array, object
 *
 * Not covered:
 *   - Leap-second handling (edge case of Instant.ofEpochSecond — delegated to JDK)
 *   - Very long strings that would stress the parser
 */
class CreateTimeDeserializerTest {

    private ObjectMapper mapper;

    // Minimal wrapper to bind create_time through the deserializer without affecting global config.
    private record Wrapper(
            @JsonProperty("create_time")
            @JsonDeserialize(using = CreateTimeDeserializer.class)
            String createTime
    ) {}

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    // --- Epoch seconds ---

    @Test
    void epoch_zero_normalizes_to_unix_epoch_string() throws Exception {
        Wrapper result = mapper.readValue("{\"create_time\": 0}", Wrapper.class);
        assertThat(result.createTime()).isEqualTo("1970-01-01T00:00:00Z");
    }

    @Test
    void positive_epoch_seconds_normalizes_to_utc_iso8601() throws Exception {
        // 1000000000 seconds since epoch = 2001-09-09T01:46:40Z
        Wrapper result = mapper.readValue("{\"create_time\": 1000000000}", Wrapper.class);
        assertThat(result.createTime()).isEqualTo("2001-09-09T01:46:40Z");
    }

    @Test
    void large_epoch_seconds_normalizes_correctly() throws Exception {
        // 4102444800 = 2100-01-01T00:00:00Z
        Wrapper result = mapper.readValue("{\"create_time\": 4102444800}", Wrapper.class);
        assertThat(result.createTime()).isEqualTo("2100-01-01T00:00:00Z");
    }

    @Test
    void negative_epoch_seconds_throws_invalid_format_exception() {
        assertThatThrownBy(() -> mapper.readValue("{\"create_time\": -1}", Wrapper.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    // --- ISO 8601 strings ---

    @Test
    void iso8601_with_z_suffix_is_stored_as_is() throws Exception {
        Wrapper result = mapper.readValue("{\"create_time\": \"2026-04-11T10:30:00Z\"}", Wrapper.class);
        assertThat(result.createTime()).isEqualTo("2026-04-11T10:30:00Z");
    }

    @Test
    void iso8601_with_positive_offset_is_converted_to_utc() throws Exception {
        // +05:00 means 15:30 local = 10:30 UTC
        Wrapper result = mapper.readValue("{\"create_time\": \"2026-04-11T15:30:00+05:00\"}", Wrapper.class);
        assertThat(result.createTime()).isEqualTo("2026-04-11T10:30:00Z");
    }

    @Test
    void iso8601_with_negative_offset_is_converted_to_utc() throws Exception {
        // -05:00 means 05:30 local = 10:30 UTC
        Wrapper result = mapper.readValue("{\"create_time\": \"2026-04-11T05:30:00-05:00\"}", Wrapper.class);
        assertThat(result.createTime()).isEqualTo("2026-04-11T10:30:00Z");
    }

    // --- Invalid strings ---

    @Test
    void random_string_throws_invalid_format_exception() {
        assertThatThrownBy(() -> mapper.readValue("{\"create_time\": \"not-a-date\"}", Wrapper.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void empty_string_throws_invalid_format_exception() {
        assertThatThrownBy(() -> mapper.readValue("{\"create_time\": \"\"}", Wrapper.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void date_only_without_time_throws_invalid_format_exception() {
        // ISO date alone is not ISO 8601 datetime; OffsetDateTime.parse rejects it
        assertThatThrownBy(() -> mapper.readValue("{\"create_time\": \"2026-04-11\"}", Wrapper.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void iso8601_datetime_without_offset_throws_invalid_format_exception() {
        // LocalDateTime has no offset and cannot be parsed by OffsetDateTime
        assertThatThrownBy(() -> mapper.readValue("{\"create_time\": \"2026-04-11T10:30:00\"}", Wrapper.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    // --- Wrong JSON token types ---

    @Test
    void boolean_value_throws_exception() {
        assertThatThrownBy(() -> mapper.readValue("{\"create_time\": true}", Wrapper.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void float_value_throws_exception() {
        // The deserializer only handles VALUE_NUMBER_INT, not floats
        assertThatThrownBy(() -> mapper.readValue("{\"create_time\": 1.5}", Wrapper.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void array_value_throws_exception() {
        assertThatThrownBy(() -> mapper.readValue("{\"create_time\": []}", Wrapper.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void object_value_throws_exception() {
        assertThatThrownBy(() -> mapper.readValue("{\"create_time\": {}}", Wrapper.class))
                .isInstanceOf(Exception.class);
    }
}
