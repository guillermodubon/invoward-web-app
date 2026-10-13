package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import io.github.guillermodubon.invoward.reconciliation.application.exception.DiscrepancyNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyEvidenceRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyEvidence;
import io.github.guillermodubon.invoward.reconciliation.domain.EvidenceSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GetDiscrepancyEvidenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final UUID ANALYSIS_ID = uuid(1);
    private static final UUID DISCREPANCY_ID = uuid(2);
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(uuid(3));

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final DiscrepancyRepository discrepancyRepository = mock(DiscrepancyRepository.class);
    private final DiscrepancyEvidenceRepository evidenceRepository = mock(DiscrepancyEvidenceRepository.class);
    private GetDiscrepancyEvidenceService service;

    @BeforeEach
    void setUp() {
        service = new GetDiscrepancyEvidenceService(
                analysisRepository, discrepancyRepository, evidenceRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void rejectsWrongRegisteredOwnerBeforeLookingUpDiscrepancyOrEvidence() {
        when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class,
                () -> service.get(ANALYSIS_ID, OWNER, DISCREPANCY_ID));

        verifyNoInteractions(discrepancyRepository, evidenceRepository);
    }

    @Test
    void rejectsWrongOrExpiredGuestBeforeLookingUpDiscrepancyOrEvidence() {
        AnalysisOwner expiredGuest = new GuestSessionOwner(uuid(4), NOW.minusSeconds(1));
        when(analysisRepository.findOwnedById(ANALYSIS_ID, expiredGuest, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class,
                () -> service.get(ANALYSIS_ID, expiredGuest, DISCREPANCY_ID));

        verifyNoInteractions(discrepancyRepository, evidenceRepository);
    }

    @Test
    void rejectsDiscrepancyOutsideTheAuthorizedAnalysisBeforeReadingEvidence() {
        Analysis analysis = analysis();
        when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(analysis));
        when(discrepancyRepository.findByIdAndAnalysisId(DISCREPANCY_ID, ANALYSIS_ID))
                .thenReturn(Optional.empty());

        assertThrows(DiscrepancyNotFoundException.class,
                () -> service.get(ANALYSIS_ID, OWNER, DISCREPANCY_ID));

        verifyNoInteractions(evidenceRepository);
        var order = inOrder(analysisRepository, discrepancyRepository);
        order.verify(analysisRepository).findOwnedById(ANALYSIS_ID, OWNER, NOW);
        order.verify(discrepancyRepository).findByIdAndAnalysisId(DISCREPANCY_ID, ANALYSIS_ID);
    }

    @Test
    void returnsEmptyEvidenceAsAnEmptyListAfterOwnerAndDiscrepancyValidation() {
        Analysis analysis = analysis();
        when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(analysis));
        when(discrepancyRepository.findByIdAndAnalysisId(DISCREPANCY_ID, ANALYSIS_ID))
                .thenReturn(Optional.of(discrepancy()));
        when(evidenceRepository.findByDiscrepancyId(DISCREPANCY_ID)).thenReturn(List.of());

        assertEquals(List.of(), service.get(ANALYSIS_ID, OWNER, DISCREPANCY_ID));
        verify(evidenceRepository, never()).createAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void returnsEvidenceInReferenceThenInvoicePageAndIdOrder() {
        Analysis analysis = analysis();
        DiscrepancyEvidence invoicePageOne = evidence(uuid(10), EvidenceSide.INVOICE, 1);
        DiscrepancyEvidence referencePageTwo = evidence(uuid(11), EvidenceSide.REFERENCE, 2);
        DiscrepancyEvidence referencePageOneHighId = evidence(uuid(13), EvidenceSide.REFERENCE, 1);
        DiscrepancyEvidence referencePageOneLowId = evidence(uuid(12), EvidenceSide.REFERENCE, 1);
        when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(analysis));
        when(discrepancyRepository.findByIdAndAnalysisId(DISCREPANCY_ID, ANALYSIS_ID))
                .thenReturn(Optional.of(discrepancy()));
        when(evidenceRepository.findByDiscrepancyId(DISCREPANCY_ID))
                .thenReturn(List.of(invoicePageOne, referencePageTwo, referencePageOneHighId, referencePageOneLowId));

        assertEquals(List.of(referencePageOneLowId, referencePageOneHighId, referencePageTwo, invoicePageOne),
                service.get(ANALYSIS_ID, OWNER, DISCREPANCY_ID));

        var order = inOrder(analysisRepository, discrepancyRepository, evidenceRepository);
        order.verify(analysisRepository).findOwnedById(ANALYSIS_ID, OWNER, NOW);
        order.verify(discrepancyRepository).findByIdAndAnalysisId(DISCREPANCY_ID, ANALYSIS_ID);
        order.verify(evidenceRepository).findByDiscrepancyId(DISCREPANCY_ID);
        verify(evidenceRepository, never()).createAll(org.mockito.ArgumentMatchers.anyList());
    }

    private static Analysis analysis() {
        return Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), NOW.minusSeconds(5));
    }

    private static Discrepancy discrepancy() {
        return new Discrepancy(
                DISCREPANCY_ID, ANALYSIS_ID, null, DiscrepancyType.TOTAL_MISMATCH,
                "10.0000", "11.0000", null, null, "The document totals differ.",
                DiscrepancyResolutionStatus.OPEN, null, null, 0, null, NOW, NOW);
    }

    private static DiscrepancyEvidence evidence(UUID id, EvidenceSide side, int page) {
        return new DiscrepancyEvidence(id, DISCREPANCY_ID, uuid(30), side, page,
                "source text", new BoundingBox(0.1, 0.1, 0.9, 0.9), NOW);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
