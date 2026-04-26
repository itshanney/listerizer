package dev.brickfolio.listerizer.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ItemRequest(
        String url,
        @JsonProperty("create_time") Long createTime
) {
}
