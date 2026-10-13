package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyEvidenceRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.ReconciliationLineRepository;
import io.github.guillermodubon.invoward.reconciliation.application.exception.DiscrepancyConflictException;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyEvidence;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.EvidenceSide;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class ReconciliationPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    @Autowired
    private ReconciliationLineRepository reconciliationLineRepository;

    @Autowired
    private DiscrepancyRepository discrepancyRepository;

    @Autowired
    private DiscrepancyEvidenceRepository evidenceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void roundTripsEveryReconciliationLineStatusAndPreservesSignedSnapshots() {
        LineFixture fixture = createLineFixture();
        List<ReconciliationLine> expected = List.of(
                line(fixture.analysisId(), fixture.matchIds().get(0), ReconciliationLineStatus.MATCHED,
                        "REF-0", "INV-0", "Reference item", "Invoice item",
                        "2.0000", "3.0000", "12.5000", "10.0000", "25.0000", "30.0000",
                        "1.0000", "-2.5000", "5.0000", NOW),
                line(fixture.analysisId(), fixture.matchIds().get(1), ReconciliationLineStatus.DIFFERENT,
                        "REF-1", "INV-1", "Reference item 1", "Invoice item 1",
                        "4.0000", "2.0000", "8.0000", "9.0000", "32.0000", "18.0000",
                        "-2.0000", "1.0000", "-14.0000", NOW.plusSeconds(1)),
                line(fixture.analysisId(), fixture.matchIds().get(2), ReconciliationLineStatus.MISSING,
                        "REF-2", null, "Reference item 2", null,
                        "1.0000", null, "3.0000", null, "3.0000", null,
                        null, null, null, NOW.plusSeconds(2)),
                line(fixture.analysisId(), fixture.matchIds().get(3), ReconciliationLineStatus.UNEXPECTED,
                        null, "INV-2", null, "Invoice item 2",
                        null, "5.0000", null, "7.0000", null, "35.0000",
                        null, null, null, NOW.plusSeconds(3)));

        assertEquals(expected, reconciliationLineRepository.createAll(expected));
        assertEquals(expected, reconciliationLineRepository.findByAnalysisId(fixture.analysisId()));
        assertEquals(expected.size(), reconciliationLineRepository.countByAnalysisId(fixture.analysisId()));
        assertTrue(reconciliationLineRepository.findByAnalysisId(UUID.randomUUID()).isEmpty());
        assertEquals(0, reconciliationLineRepository.countByAnalysisId(UUID.randomUUID()));
        assertEquals(new BigDecimal("-2.5000"),
                reconciliationLineRepository.findByAnalysisId(fixture.analysisId()).getFirst().unitPriceDifference());
    }

    @Test
    void persistsEveryDiscrepancyTypeAndAppliesOptimisticResolutionVersioning() {
        LineFixture fixture = createLineFixture();
        ReconciliationLine line = line(fixture.analysisId(), fixture.matchIds().getFirst(),
                ReconciliationLineStatus.MATCHED, "REF", "INV", "Reference", "Invoice",
                "1.0000", "2.0000", "3.0000", "4.0000", "3.0000", "8.0000",
                "1.0000", "1.0000", "5.0000", NOW);
        ReconciliationLine persistedLine = reconciliationLineRepository.createAll(List.of(line)).getFirst();
        List<Discrepancy> expected = new ArrayList<>();
        for (DiscrepancyType type : DiscrepancyType.values()) {
            expected.add(discrepancy(fixture.analysisId(),
                    type == DiscrepancyType.QUANTITY_MISMATCH ? persistedLine.id() : null,
                    type, NOW.plusSeconds(expected.size())));
        }

        List<Discrepancy> saved = discrepancyRepository.createAll(expected);

        assertEquals(expected, saved);
        assertEquals(expected, discrepancyRepository.findByAnalysisId(fixture.analysisId()));
        assertEquals(expected.size(), discrepancyRepository.countByAnalysisId(fixture.analysisId()));
        assertTrue(discrepancyRepository.findByIdAndAnalysisId(
                saved.getFirst().id(), UUID.randomUUID()).isEmpty());
        assertEquals(saved.getFirst(), discrepancyRepository.findByIdAndAnalysisIdForUpdate(
                saved.getFirst().id(), fixture.analysisId()).orElseThrow());

        Discrepancy initial = saved.getFirst();
        Discrepancy accepted = initial.transitionResolution(
                DiscrepancyResolutionStatus.ACCEPTED,
                DiscrepancyResolutionReason.APPROVED_CHANGE,
                null,
                NOW.plusSeconds(30));
        Discrepancy updated = discrepancyRepository.update(accepted).orElseThrow();

        assertEquals(1, updated.version());
        assertEquals(DiscrepancyResolutionStatus.ACCEPTED, updated.resolutionStatus());
        assertEquals(accepted.resolvedAt(), updated.resolvedAt());
        assertThrows(DiscrepancyConflictException.class,
                () -> discrepancyRepository.update(accepted));
        assertTrue(discrepancyRepository.update(new Discrepancy(
                UUID.randomUUID(), fixture.analysisId(), null, DiscrepancyType.TOTAL_MISMATCH,
                "1", "2", BigDecimal.ONE, null, "A total differs.", DiscrepancyResolutionStatus.OPEN,
                null, null, 0, null, NOW, NOW)).isEmpty());
    }

    @Test
    void persistsBothEvidenceSidesAndJsonbBoundingBoxesAndCascadesWithAnalysis() {
        LineFixture fixture = createLineFixture();
        Discrepancy parent = discrepancy(fixture.analysisId(), null, DiscrepancyType.TOTAL_MISMATCH, NOW);
        Discrepancy savedParent = discrepancyRepository.createAll(List.of(parent)).getFirst();
        List<DiscrepancyEvidence> expected = List.of(
                new DiscrepancyEvidence(UUID.randomUUID(), savedParent.id(), fixture.referenceDocumentId(),
                        EvidenceSide.REFERENCE, 2, "Reference amount", new BoundingBox(0.1, 0.2, 0.4, 0.5), NOW),
                new DiscrepancyEvidence(UUID.randomUUID(), savedParent.id(), fixture.invoiceDocumentId(),
                        EvidenceSide.INVOICE, 3, "Invoice amount", new BoundingBox(0.2, 0.3, 0.6, 0.8),
                        NOW.plusSeconds(1)));

        assertEquals(expected, evidenceRepository.createAll(expected));
        assertEquals(expected, evidenceRepository.findByDiscrepancyId(savedParent.id()));
        assertEquals(expected.size(), evidenceRepository.countByDiscrepancyId(savedParent.id()));
        assertFalse(evidenceRepository.findByDiscrepancyId(UUID.randomUUID()).iterator().hasNext());

        assertEquals(1, jdbcTemplate.update("DELETE FROM invoward.analyses WHERE id = ?", fixture.analysisId()));
        assertEquals(0, reconciliationLineRepository.countByAnalysisId(fixture.analysisId()));
        assertEquals(0, discrepancyRepository.countByAnalysisId(fixture.analysisId()));
        assertEquals(0, evidenceRepository.countByDiscrepancyId(savedParent.id()));
    }

    @Test
    void databaseRejectsInvalidResolutionDetails() {
        LineFixture fixture = createLineFixture();
        Discrepancy saved = discrepancyRepository.createAll(List.of(
                discrepancy(fixture.analysisId(), null, DiscrepancyType.TOTAL_MISMATCH, NOW))).getFirst();

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "UPDATE invoward.discrepancies SET resolution_status = 'RESOLVED' WHERE id = ?", saved.id()));
        assertEquals(DiscrepancyResolutionStatus.OPEN,
                discrepancyRepository.findByIdAndAnalysisId(saved.id(), fixture.analysisId())
                        .orElseThrow().resolutionStatus());
    }

    private LineFixture createLineFixture() {
        return jdbcTemplate.execute((ConnectionCallback<LineFixture>) connection -> {
            UUID analysisId = DatabaseFixtures.insertUserAnalysis(connection);
            DatabaseFixtures.LinePair pair = DatabaseFixtures.insertReferenceAndInvoiceLines(connection, analysisId);
            List<UUID> referenceLineIds = new ArrayList<>(List.of(pair.referenceLineId()));
            List<UUID> invoiceLineIds = new ArrayList<>(List.of(pair.invoiceLineId()));
            for (int position = 1; position < 3; position++) {
                referenceLineIds.add(DatabaseFixtures.insertLineItem(connection, pair.referenceExtractionId(), position));
                invoiceLineIds.add(DatabaseFixtures.insertLineItem(connection, pair.invoiceExtractionId(), position));
            }

            List<UUID> matchIds = new ArrayList<>();
            matchIds.add(DatabaseFixtures.insertMatch(connection, analysisId,
                    referenceLineIds.get(0), invoiceLineIds.get(0), "MATCHED", "MANUAL", null));
            matchIds.add(DatabaseFixtures.insertMatch(connection, analysisId,
                    referenceLineIds.get(1), invoiceLineIds.get(1), "MATCHED", "MANUAL", null));
            matchIds.add(DatabaseFixtures.insertMatch(connection, analysisId,
                    referenceLineIds.get(2), null, "UNMATCHED_REFERENCE", "NONE", null));
            matchIds.add(DatabaseFixtures.insertMatch(connection, analysisId,
                    null, invoiceLineIds.get(2), "UNMATCHED_INVOICE", "NONE", null));
            return new LineFixture(analysisId, pair.referenceDocumentId(), pair.invoiceDocumentId(),
                    List.copyOf(matchIds));
        });
    }

    private static ReconciliationLine line(
            UUID analysisId,
            UUID lineMatchId,
            ReconciliationLineStatus status,
            String referenceCode,
            String invoiceCode,
            String referenceDescription,
            String invoiceDescription,
            String referenceQuantity,
            String invoiceQuantity,
            String referenceUnitPrice,
            String invoiceUnitPrice,
            String referenceLineTotal,
            String invoiceLineTotal,
            String quantityDifference,
            String unitPriceDifference,
            String lineTotalDifference,
            Instant createdAt) {
        return new ReconciliationLine(UUID.randomUUID(), analysisId, lineMatchId, status,
                referenceCode, invoiceCode, referenceDescription, invoiceDescription,
                decimal(referenceQuantity), decimal(invoiceQuantity), decimal(referenceUnitPrice),
                decimal(invoiceUnitPrice), decimal(referenceLineTotal), decimal(invoiceLineTotal),
                decimal(quantityDifference), decimal(unitPriceDifference), decimal(lineTotalDifference), createdAt);
    }

    private static Discrepancy discrepancy(
            UUID analysisId, UUID reconciliationLineId, DiscrepancyType type, Instant createdAt) {
        return new Discrepancy(UUID.randomUUID(), analysisId, reconciliationLineId, type,
                "10.0000", "12.0000", new BigDecimal("2.0000"), new BigDecimal("20.000000"),
                "The invoice value differs from the reference value.", DiscrepancyResolutionStatus.OPEN,
                null, null, 0, null, createdAt, createdAt);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private record LineFixture(
            UUID analysisId,
            UUID referenceDocumentId,
            UUID invoiceDocumentId,
            List<UUID> matchIds) {
    }
}
