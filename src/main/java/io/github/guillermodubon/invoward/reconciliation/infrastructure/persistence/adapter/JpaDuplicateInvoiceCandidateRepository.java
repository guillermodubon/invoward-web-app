package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.reconciliation.application.port.DuplicateInvoiceCandidateRepository;
import io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.repository.SpringDataDuplicateInvoiceCandidateJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** JPA adapter that searches duplicate candidates strictly within the Analysis owner. */
@Repository
@Transactional(readOnly = true)
public class JpaDuplicateInvoiceCandidateRepository implements DuplicateInvoiceCandidateRepository {

    private static final int MAX_CANDIDATES = 5;

    private final SpringDataDuplicateInvoiceCandidateJpaRepository repository;

    public JpaDuplicateInvoiceCandidateRepository(
            SpringDataDuplicateInvoiceCandidateJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public List<CandidateRecord> findCandidates(
            AnalysisOwner owner,
            UUID currentAnalysisId,
            String invoiceFileSha256,
            String normalizedInvoiceNumber,
            Instant now,
            int limit) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(currentAnalysisId, "currentAnalysisId must not be null");
        Objects.requireNonNull(invoiceFileSha256, "invoiceFileSha256 must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (limit < 1 || limit > MAX_CANDIDATES) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_CANDIDATES);
        }

        PageRequest page = PageRequest.of(0, limit);
        if (owner instanceof RegisteredUserOwner registeredOwner) {
            return repository.findRegisteredUserCandidates(
                            registeredOwner.userId(), currentAnalysisId, invoiceFileSha256,
                            normalizedInvoiceNumber, DocumentRole.INVOICE, ExtractionStatus.CONFIRMED, page)
                    .stream().map(JpaDuplicateInvoiceCandidateRepository::toRecord).toList();
        }
        if (owner instanceof GuestSessionOwner guestOwner) {
            return repository.findGuestSessionCandidates(
                            guestOwner.guestSessionId(), now, currentAnalysisId, invoiceFileSha256,
                            normalizedInvoiceNumber, DocumentRole.INVOICE, ExtractionStatus.CONFIRMED, page)
                    .stream().map(JpaDuplicateInvoiceCandidateRepository::toRecord).toList();
        }
        throw new IllegalArgumentException("unsupported Analysis owner type");
    }

    private static CandidateRecord toRecord(
            SpringDataDuplicateInvoiceCandidateJpaRepository.CandidateProjection row) {
        return new CandidateRecord(row.getAnalysisId(), row.getAnalyzedAt(), row.getInvoiceNumber(),
                row.getSupplierKey(), row.getInvoiceFileSha256(), row.getInvoiceTotal(), row.getDocumentDate());
    }
}
