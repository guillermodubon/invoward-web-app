package io.github.guillermodubon.invoward.extraction.infrastructure.ai.disabled;

import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException.Failure;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentIntelligenceInput;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisabledDocumentIntelligenceTest {

    private final DisabledDocumentIntelligence provider = new DisabledDocumentIntelligence();
    private final DocumentIntelligenceInput input = new DocumentIntelligenceInput(
            UUID.randomUUID(), DocumentRole.REFERENCE, "application/pdf", 1, Path.of("not-opened.pdf"));

    @Test
    void classificationFailsClosedWithProviderNeutralUnavailableFailure() {
        DocumentIntelligenceException exception = assertThrows(
                DocumentIntelligenceException.class, () -> provider.classify(input));
        assertEquals(Failure.UNAVAILABLE, exception.failure());
    }

    @Test
    void extractionFailsClosedWithProviderNeutralUnavailableFailure() {
        DocumentIntelligenceException exception = assertThrows(
                DocumentIntelligenceException.class, () -> provider.extract(input, DocumentType.INVOICE));
        assertEquals(Failure.UNAVAILABLE, exception.failure());
    }
}
