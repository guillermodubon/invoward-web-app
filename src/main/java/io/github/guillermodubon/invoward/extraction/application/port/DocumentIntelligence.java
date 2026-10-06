package io.github.guillermodubon.invoward.extraction.application.port;

import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentClassification;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentIntelligenceInput;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;

/** Provider-neutral port for classifying and extracting a privately materialized document. */
public interface DocumentIntelligence {

    DocumentClassification classify(DocumentIntelligenceInput input);

    ExtractionDraft extract(DocumentIntelligenceInput input, DocumentType confirmedType);
}
