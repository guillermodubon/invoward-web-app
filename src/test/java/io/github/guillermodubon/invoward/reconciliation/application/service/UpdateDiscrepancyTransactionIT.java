package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.reconciliation.application.exception.DiscrepancyConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.model.DiscrepancyResolutionUpdate;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class UpdateDiscrepancyTransactionIT {

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private DiscrepancyRepository discrepancyRepository;

    @Autowired
    private UpdateDiscrepancyService updateDiscrepancyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void persistsResolutionVersionAndLeavesOriginalAnalysisResultUntouched() {
        Instant createdAt = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MICROS);
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        AnalysisOwner owner = new RegisteredUserOwner(userId);
        Analysis analysis = createReconciledAnalysis(owner, createdAt);

        Instant discrepancyCreatedAt = createdAt.plusSeconds(7);
        Discrepancy initial = discrepancyRepository.createAll(List.of(new Discrepancy(
                UUID.randomUUID(), analysis.id(), null, DiscrepancyType.TOTAL_MISMATCH,
                "100.00", "110.00", new BigDecimal("10.0000"), null,
                "The invoice total differs.", DiscrepancyResolutionStatus.OPEN,
                null, null, 0, null, discrepancyCreatedAt, discrepancyCreatedAt))).getFirst();

        Discrepancy accepted = updateDiscrepancyService.update(
                analysis.id(), owner, initial.id(), new DiscrepancyResolutionUpdate(
                        0, DiscrepancyResolutionStatus.ACCEPTED,
                        DiscrepancyResolutionReason.APPROVED_CHANGE, null));

        assertEquals(1, accepted.version());
        assertEquals(DiscrepancyResolutionStatus.ACCEPTED, accepted.resolutionStatus());
        assertEquals(DiscrepancyResolutionReason.APPROVED_CHANGE, accepted.resolutionReason());
        assertTrue(accepted.resolvedAt().isAfter(discrepancyCreatedAt));
        assertEquals(initial.referenceValue(), accepted.referenceValue());
        assertEquals(initial.invoiceValue(), accepted.invoiceValue());
        assertEquals(initial.absoluteDifference(), accepted.absoluteDifference());
        assertEquals(initial.explanation(), accepted.explanation());

        assertThrows(DiscrepancyConflictException.class, () -> updateDiscrepancyService.update(
                analysis.id(), owner, initial.id(), new DiscrepancyResolutionUpdate(
                        0, DiscrepancyResolutionStatus.RESOLVED,
                        DiscrepancyResolutionReason.SUPPLIER_CORRECTION_EXPECTED, null)));
        assertEquals(1, discrepancyRepository.findByIdAndAnalysisId(initial.id(), analysis.id())
                .orElseThrow().version());

        Discrepancy reopened = updateDiscrepancyService.update(
                analysis.id(), owner, initial.id(), new DiscrepancyResolutionUpdate(
                        1, DiscrepancyResolutionStatus.OPEN,
                        DiscrepancyResolutionReason.OTHER, "cleared on reopen"));
        assertEquals(2, reopened.version());
        assertNull(reopened.resolutionReason());
        assertNull(reopened.resolutionNote());
        assertNull(reopened.resolvedAt());

        Analysis unchanged = analysisRepository.findOwnedById(analysis.id(), owner, Instant.now())
                .orElseThrow();
        assertEquals(AnalysisStatus.GENERATING_REPORT, unchanged.status());
        assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, unchanged.reconciliationStatus());
        assertEquals(analysis.version(), unchanged.version());
    }

    private Analysis createReconciledAnalysis(AnalysisOwner owner, Instant createdAt) {
        Analysis analysis = Analysis.create(UUID.randomUUID(), owner, PriceTolerance.exactMatch(), createdAt);
        analysis = analysisRepository.create(analysis);
        analysis = analysisRepository.update(analysis.transitionToUploading(createdAt.plusSeconds(1)));
        analysis = analysisRepository.update(analysis.markClassifying(createdAt.plusSeconds(2)));
        analysis = analysisRepository.update(analysis.awaitExtractionConfirmation(createdAt.plusSeconds(3)));
        analysis = analysisRepository.update(analysis.confirmExtraction(
                "QUOTE", "Q-1", "I-1", "Example Supplier", "USD",
                BigDecimal.TEN, BigDecimal.TEN, createdAt.plusSeconds(4)));
        analysis = analysisRepository.update(analysis.beginReconciliation(createdAt.plusSeconds(5)));
        return analysisRepository.update(analysis.completeReconciliation(
                AnalysisReconciliationStatus.REVIEW_REQUIRED, BigDecimal.ZERO, createdAt.plusSeconds(6)));
    }
}
