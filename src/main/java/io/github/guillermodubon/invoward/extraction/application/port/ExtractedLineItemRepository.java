package io.github.guillermodubon.invoward.extraction.application.port;

import io.github.guillermodubon.invoward.extraction.application.model.PersistedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence operations for ordered lines belonging to an extraction. */
public interface ExtractedLineItemRepository {

    List<PersistedLineItem> findByExtractedDocumentId(UUID extractedDocumentId);

    List<PersistedLineItem> replaceAll(
            UUID extractedDocumentId,
            List<ExtractedLineItem> lineItems,
            Instant now);
}
