package io.github.guillermodubon.invoward.identity.api.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record ValidationErrorResponse(String code, String message, Map<String, String> fields) {

    public ValidationErrorResponse {
        Objects.requireNonNull(fields, "fields must not be null");
        fields = Collections.unmodifiableMap(new TreeMap<>(fields));
    }
}
