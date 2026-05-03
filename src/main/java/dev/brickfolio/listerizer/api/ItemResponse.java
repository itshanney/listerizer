package dev.brickfolio.listerizer.api;

public record ItemResponse(
        long id,
        String url,
        long createTime,
        String title,
        boolean hasBeenRead
) {
}
