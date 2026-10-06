package io.github.guillermodubon.invoward.support.ai;

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

class FakeDocumentIntelligenceTest {

    @Test
    void supportsConfiguredClassificationsCallCountsFailuresAndReset() {
        FakeDocumentIntelligence fake = new FakeDocumentIntelligence();
        UUID documentId = UUID.randomUUID();
        DocumentIntelligenceInput input = new DocumentIntelligenceInput(
                documentId, DocumentRole.REFERENCE, "application/pdf", 1, Path.of("synthetic.pdf"));
        fake.setClassification(documentId, DocumentType.QUOTE);

        assertEquals(DocumentType.QUOTE, fake.classify(input).detectedType());
        assertEquals(1, fake.classifyCalls(documentId));

        for (Failure failure : Failure.values()) {
            fake.configureFailure(failure);
            DocumentIntelligenceException exception = assertThrows(
                    DocumentIntelligenceException.class, () -> fake.classify(input));
            assertEquals(failure, exception.failure());
        }

        fake.reset();
        assertEquals(0, fake.classifyCalls(documentId));
        assertEquals(0, fake.extractCalls());
    }
}
