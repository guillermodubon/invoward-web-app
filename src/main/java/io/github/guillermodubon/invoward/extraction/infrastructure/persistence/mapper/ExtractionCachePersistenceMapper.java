package io.github.guillermodubon.invoward.extraction.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.extraction.application.model.CachedExtractionPayload;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.entity.ExtractionCacheEntryJpaEntity;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;

/** Converts the provider-only cache contract to the JSONB payload representation. */
@Component
public class ExtractionCachePersistenceMapper {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public ExtractionCachePersistenceMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public String toJson(CachedExtractionPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Cached extraction payload could not be encoded", exception);
        }
    }

    public CachedExtractionPayload toPayload(Map<String, Object> jsonObject) {
        Objects.requireNonNull(jsonObject, "jsonObject must not be null");
        try {
            return objectMapper.convertValue(jsonObject, CachedExtractionPayload.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Cached extraction payload has an invalid shape", exception);
        }
    }

    public Map<String, Object> toJsonObject(CachedExtractionPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        try {
            return objectMapper.convertValue(payload, JSON_OBJECT);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Cached extraction payload could not be mapped", exception);
        }
    }

    public CachedExtractionPayload toPayload(ExtractionCacheEntryJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return toPayload(entity.getPayload());
    }
}
