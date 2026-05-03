package dev.brickfolio.listerizer.api;

public record ItemRequest(
        String url,
        Long createTime,
        String title,
        Boolean hasBeenRead
) {
}
