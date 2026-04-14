package dev.brickfolio.listerizer.item;

/**
 * Carries the result of an INSERT OR IGNORE operation.
 * {@code isNew} is true when the row was inserted, false when the URL already existed
 * and the existing record was returned instead.
 */
public record InsertResult(Item item, boolean isNew) {
}
