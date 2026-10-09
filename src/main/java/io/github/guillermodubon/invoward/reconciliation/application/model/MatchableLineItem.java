package io.github.guillermodubon.invoward.reconciliation.application.model;

import java.util.Objects;
import java.util.UUID;

/** Matching-only projection of a confirmed line, intentionally excluding financial data and evidence. */
public record MatchableLineItem(
        UUID id,
        int position,
        String itemCode,
        String description,
        String normalizedDescription,
        String unit) {

    public MatchableLineItem {
        Objects.requireNonNull(id, "id must not be null");
        if (position < 0) {
            throw new IllegalArgumentException("position must not be negative");
        }
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(normalizedDescription, "normalizedDescription must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
    }
}
