package dev.brickfolio.listerizer.item;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ItemResponse(
        long id,
        String url,
        @JsonProperty("create_time") String createTime
) {
}
