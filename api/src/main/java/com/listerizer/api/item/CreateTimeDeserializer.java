package com.listerizer.api.item;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Accepts create_time as either a JSON integer (Unix epoch seconds) or a JSON string (ISO 8601).
 * Normalizes both forms to a UTC ISO 8601 string (e.g. "2026-04-11T10:30:00Z") before binding.
 */
public class CreateTimeDeserializer extends StdDeserializer<String> {

    public CreateTimeDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return deserializeEpochSeconds(p);
        }
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            return deserializeIso8601(p);
        }
        throw InvalidFormatException.from(
                p,
                "create_time must be an ISO 8601 string or Unix epoch seconds integer",
                p.getCurrentToken(),
                String.class
        );
    }

    private String deserializeEpochSeconds(JsonParser p) throws IOException {
        long epochSeconds = p.getLongValue();
        if (epochSeconds < 0) {
            throw InvalidFormatException.from(
                    p,
                    "create_time epoch seconds must be >= 0",
                    epochSeconds,
                    String.class
            );
        }
        return Instant.ofEpochSecond(epochSeconds).toString();
    }

    private String deserializeIso8601(JsonParser p) throws IOException {
        String value = p.getText();
        try {
            // OffsetDateTime handles ISO 8601 with timezone offsets; Instant handles the Z suffix.
            // Normalize to UTC and format as a Z-suffix string for consistent storage.
            return OffsetDateTime.parse(value).toInstant().toString();
        } catch (DateTimeParseException e) {
            throw InvalidFormatException.from(
                    p,
                    "create_time must be an ISO 8601 string or Unix epoch seconds integer",
                    value,
                    String.class
            );
        }
    }
}
