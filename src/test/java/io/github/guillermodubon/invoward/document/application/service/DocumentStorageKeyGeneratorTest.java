package io.github.guillermodubon.invoward.document.application.service;

import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.model.DocumentFileFormat;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentStorageKeyGeneratorTest {

    private final DocumentStorageKeyGenerator generator = new DocumentStorageKeyGenerator();

    @Test
    void registeredKeysAreScopedAndUseAnOpaqueRandomFilename() {
        UUID userId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);

        String first = generator.generate(owner, analysisId, DocumentFileFormat.JPEG);
        String second = generator.generate(owner, analysisId, DocumentFileFormat.JPEG);

        String prefix = "users/" + userId + "/analyses/" + analysisId + "/documents/";
        assertTrue(first.matches(prefix + "[0-9a-f-]{36}\\.jpg"));
        assertTrue(second.matches(prefix + "[0-9a-f-]{36}\\.jpg"));
        assertNotEquals(first, second);
    }

    @Test
    void guestKeysAreScopedWithoutExposingGuestSessionIdentifier() {
        UUID guestSessionId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        GuestSessionOwner owner = new GuestSessionOwner(guestSessionId, Instant.parse("2026-10-02T00:00:00Z"));

        String key = generator.generate(owner, analysisId, DocumentFileFormat.PNG);

        assertTrue(key.matches("guest/" + analysisId + "/documents/[0-9a-f-]{36}\\.png"));
        assertFalse(key.contains(guestSessionId.toString()));
    }
}
