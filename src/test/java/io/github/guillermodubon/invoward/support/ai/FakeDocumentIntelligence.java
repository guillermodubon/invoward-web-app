package io.github.guillermodubon.invoward.support.ai;

import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException.Failure;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentClassification;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentIntelligenceInput;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.extraction.application.port.DocumentIntelligence;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic test-only provider. It never performs network I/O. */
public final class FakeDocumentIntelligence implements DocumentIntelligence {

    private final Map<UUID, DocumentType> classifications = new ConcurrentHashMap<>();
    private final Map<UUID, Failure> classificationFailures = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> classificationCalls = new ConcurrentHashMap<>();
    private final AtomicInteger extractionCalls = new AtomicInteger();
    private volatile ExtractionDraft extraction = new ExtractionDraft(
            null, null, null, null, null, null, null, null, List.of());
    private volatile Failure globalFailure;

    @Override
    public DocumentClassification classify(DocumentIntelligenceInput input) {
        classificationCalls.computeIfAbsent(input.documentId(), ignored -> new AtomicInteger()).incrementAndGet();
        Failure failure = classificationFailures.getOrDefault(input.documentId(), globalFailure);
        if (failure != null) {
            throw new DocumentIntelligenceException(failure);
        }
        return new DocumentClassification(classifications.getOrDefault(input.documentId(), DocumentType.UNKNOWN));
    }

    @Override
    public ExtractionDraft extract(DocumentIntelligenceInput input, DocumentType confirmedType) {
        extractionCalls.incrementAndGet();
        if (globalFailure != null) {
            throw new DocumentIntelligenceException(globalFailure);
        }
        return extraction;
    }

    public void setClassification(UUID documentId, DocumentType type) {
        classifications.put(documentId, type);
    }

    public void configureClassificationFailure(UUID documentId, Failure failure) {
        classificationFailures.put(documentId, failure);
    }

    public void clearClassificationFailure(UUID documentId) {
        classificationFailures.remove(documentId);
    }

    public void configureFailure(Failure failure) {
        globalFailure = failure;
    }

    public void clearFailure() {
        globalFailure = null;
    }

    public void setExtraction(ExtractionDraft extraction) {
        this.extraction = extraction;
    }

    public int classifyCalls(UUID documentId) {
        AtomicInteger calls = classificationCalls.get(documentId);
        return calls == null ? 0 : calls.get();
    }

    public int totalClassifyCalls() {
        return classificationCalls.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public int extractCalls() {
        return extractionCalls.get();
    }

    public void reset() {
        classifications.clear();
        classificationFailures.clear();
        classificationCalls.clear();
        extractionCalls.set(0);
        extraction = new ExtractionDraft(null, null, null, null, null, null, null, null, List.of());
        globalFailure = null;
    }
}
