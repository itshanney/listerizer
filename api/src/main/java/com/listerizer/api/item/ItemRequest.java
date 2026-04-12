package com.listerizer.api.item;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record ItemRequest(
        String url,
        @JsonProperty("create_time")
        @JsonDeserialize(using = CreateTimeDeserializer.class)
        String createTime
) {
}
