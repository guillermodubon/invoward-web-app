package io.github.guillermodubon.invoward.extraction.infrastructure.ai.disabled;

import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException.Failure;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentClassification;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentIntelligenceInput;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.extraction.application.port.DocumentIntelligence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fail-closed provider used when AI is disabled; it never fabricates successful output. */
@Component
@ConditionalOnProperty(prefix = "spring.ai.model", name = "chat", havingValue = "none", matchIfMissing = true)
public final class DisabledDocumentIntelligence implements DocumentIntelligence {

    @Override
    public DocumentClassification classify(DocumentIntelligenceInput input) {
        throw new DocumentIntelligenceException(Failure.UNAVAILABLE);
    }

    @Override
    public ExtractionDraft extract(DocumentIntelligenceInput input, DocumentType confirmedType) {
        throw new DocumentIntelligenceException(Failure.UNAVAILABLE);
    }
}
