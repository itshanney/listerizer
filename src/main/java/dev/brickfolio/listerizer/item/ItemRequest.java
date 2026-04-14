package dev.brickfolio.listerizer.item;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;

public record ItemRequest(
        String url,
        @JsonProperty("create_time")
        @JsonDeserialize(using = CreateTimeDeserializer.class)
        String createTime
) {
}
