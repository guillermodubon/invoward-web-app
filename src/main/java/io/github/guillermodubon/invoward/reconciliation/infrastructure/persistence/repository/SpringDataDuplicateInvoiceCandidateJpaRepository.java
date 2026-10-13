package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisJpaEntity;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.infrastructure.persistence.entity.DocumentJpaEntity;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.entity.ExtractedDocumentJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Owner-scoped JPA projections for exact-hash and normalized-invoice-number candidates. */
public interface SpringDataDuplicateInvoiceCandidateJpaRepository
        extends JpaRepository<AnalysisJpaEntity, UUID> {

    @Query("""
            select analysis.id as analysisId,
                   analysis.createdAt as analyzedAt,
                   analysis.invoiceNumber as invoiceNumber,
                   analysis.supplierKey as supplierKey,
                   invoiceDocument.sha256 as invoiceFileSha256,
                   analysis.invoicedTotal as invoiceTotal,
                   confirmedInvoice.documentDate as documentDate
            from AnalysisJpaEntity analysis
            join DocumentJpaEntity invoiceDocument
              on invoiceDocument.analysisId = analysis.id and invoiceDocument.role = :invoiceRole
            left join ExtractedDocumentJpaEntity confirmedInvoice
              on confirmedInvoice.documentId = invoiceDocument.id
             and confirmedInvoice.status = :confirmedStatus
            where analysis.userId = :userId
              and analysis.id <> :currentAnalysisId
              and (invoiceDocument.sha256 = :invoiceFileSha256
                   or (:normalizedInvoiceNumber is not null
                       and upper(trim(analysis.invoiceNumber)) = :normalizedInvoiceNumber))
            order by analysis.createdAt desc, analysis.id asc
            """)
    List<CandidateProjection> findRegisteredUserCandidates(
            @Param("userId") UUID userId,
            @Param("currentAnalysisId") UUID currentAnalysisId,
            @Param("invoiceFileSha256") String invoiceFileSha256,
            @Param("normalizedInvoiceNumber") String normalizedInvoiceNumber,
            @Param("invoiceRole") DocumentRole invoiceRole,
            @Param("confirmedStatus") ExtractionStatus confirmedStatus,
            Pageable pageable);

    @Query("""
            select analysis.id as analysisId,
                   analysis.createdAt as analyzedAt,
                   analysis.invoiceNumber as invoiceNumber,
                   analysis.supplierKey as supplierKey,
                   invoiceDocument.sha256 as invoiceFileSha256,
                   analysis.invoicedTotal as invoiceTotal,
                   confirmedInvoice.documentDate as documentDate
            from AnalysisJpaEntity analysis
            join DocumentJpaEntity invoiceDocument
              on invoiceDocument.analysisId = analysis.id and invoiceDocument.role = :invoiceRole
            left join ExtractedDocumentJpaEntity confirmedInvoice
              on confirmedInvoice.documentId = invoiceDocument.id
             and confirmedInvoice.status = :confirmedStatus
            where analysis.guestSessionId = :guestSessionId
              and analysis.expiresAt > :now
              and analysis.id <> :currentAnalysisId
              and (invoiceDocument.sha256 = :invoiceFileSha256
                   or (:normalizedInvoiceNumber is not null
                       and upper(trim(analysis.invoiceNumber)) = :normalizedInvoiceNumber))
            order by analysis.createdAt desc, analysis.id asc
            """)
    List<CandidateProjection> findGuestSessionCandidates(
            @Param("guestSessionId") UUID guestSessionId,
            @Param("now") Instant now,
            @Param("currentAnalysisId") UUID currentAnalysisId,
            @Param("invoiceFileSha256") String invoiceFileSha256,
            @Param("normalizedInvoiceNumber") String normalizedInvoiceNumber,
            @Param("invoiceRole") DocumentRole invoiceRole,
            @Param("confirmedStatus") ExtractionStatus confirmedStatus,
            Pageable pageable);

    interface CandidateProjection {
        UUID getAnalysisId();

        Instant getAnalyzedAt();

        String getInvoiceNumber();

        String getSupplierKey();

        String getInvoiceFileSha256();

        BigDecimal getInvoiceTotal();

        LocalDate getDocumentDate();
    }
}
