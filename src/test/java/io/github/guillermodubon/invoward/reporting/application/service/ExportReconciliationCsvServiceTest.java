package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reporting.application.exception.CsvExportTooLargeException;
import io.github.guillermodubon.invoward.reporting.application.export.CsvExportGenerator;
import io.github.guillermodubon.invoward.reporting.application.model.CsvExport;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExportReconciliationCsvServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.fromString("61000000-0000-0000-0000-000000000001");

    private final GetAnalysisService analysisService = mock(GetAnalysisService.class);
    private final GetReconciliationService reconciliationService = mock(GetReconciliationService.class);
    private final CsvExportGenerator csvExportGenerator = mock(CsvExportGenerator.class);
    private final AnalysisOwner registeredOwner = new RegisteredUserOwner(
            UUID.fromString("61000000-0000-0000-0000-000000000002"));
    private final ExportReconciliationCsvService service = new ExportReconciliationCsvService(
            analysisService, reconciliationService, csvExportGenerator);

    @Test
    void authorizesAnalysisBeforeReadingCurrentReconciliationAndGeneratingExport() {
        ReconciliationResult currentResult = result(ANALYSIS_ID, acceptedDiscrepancy());
        CsvExport expected = new CsvExport("current csv".getBytes(StandardCharsets.UTF_8));
        when(reconciliationService.get(ANALYSIS_ID, registeredOwner)).thenReturn(currentResult);
        when(csvExportGenerator.generate(currentResult)).thenReturn(expected);

        CsvExport actual = service.export(ANALYSIS_ID, registeredOwner);

        assertEquals("current csv", new String(actual.content(), StandardCharsets.UTF_8));
        InOrder order = inOrder(analysisService, reconciliationService, csvExportGenerator);
        order.verify(analysisService).get(ANALYSIS_ID, registeredOwner);
        order.verify(reconciliationService).get(ANALYSIS_ID, registeredOwner);
        order.verify(csvExportGenerator).generate(currentResult);
    }

    @Test
    void unauthorizedRegisteredOwnerCannotReadReconciliationOrGenerateCsv() {
        when(analysisService.get(ANALYSIS_ID, registeredOwner)).thenThrow(new AnalysisNotFoundException());

        assertThrows(AnalysisNotFoundException.class, () -> service.export(ANALYSIS_ID, registeredOwner));

        verifyNoInteractions(reconciliationService, csvExportGenerator);
    }

    @Test
    void unauthorizedOrExpiredGuestCannotReadReconciliationOrGenerateCsv() {
        AnalysisOwner guestOwner = new GuestSessionOwner(
                UUID.fromString("61000000-0000-0000-0000-000000000003"), NOW.minusSeconds(1));
        when(analysisService.get(ANALYSIS_ID, guestOwner)).thenThrow(new AnalysisNotFoundException());

        assertThrows(AnalysisNotFoundException.class, () -> service.export(ANALYSIS_ID, guestOwner));

        verifyNoInteractions(reconciliationService, csvExportGenerator);
    }

    @Test
    void missingReconciliationIsPropagatedWithoutGeneratingAnExport() {
        when(reconciliationService.get(ANALYSIS_ID, registeredOwner))
                .thenThrow(new ReconciliationNotFoundException());

        assertThrows(ReconciliationNotFoundException.class, () -> service.export(ANALYSIS_ID, registeredOwner));

        verify(analysisService).get(ANALYSIS_ID, registeredOwner);
        verify(csvExportGenerator, never()).generate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void exportsTheCurrentResolutionStateFromThePersistedReconciliationProjection() {
        Discrepancy accepted = acceptedDiscrepancy();
        ReconciliationResult currentResult = result(ANALYSIS_ID, accepted);
        when(reconciliationService.get(ANALYSIS_ID, registeredOwner)).thenReturn(currentResult);
        ExportReconciliationCsvService actualGeneratorService = new ExportReconciliationCsvService(
                analysisService, reconciliationService, new CsvExportGenerator(20_000));

        String csv = new String(actualGeneratorService.export(ANALYSIS_ID, registeredOwner).content(),
                StandardCharsets.UTF_8);

        assertTrue(csv.contains(",ACCEPTED,APPROVED_CHANGE,"));
        assertTrue(csv.contains("\"reviewed, confirmed after reconciliation\""));
        assertEquals(DiscrepancyResolutionStatus.ACCEPTED, accepted.resolutionStatus());
    }

    @Test
    void boundedGeneratorRejectsOversizedExportWithoutReturningPartialContent() {
        when(reconciliationService.get(ANALYSIS_ID, registeredOwner))
                .thenReturn(result(ANALYSIS_ID, acceptedDiscrepancy()));
        ExportReconciliationCsvService boundedService = new ExportReconciliationCsvService(
                analysisService, reconciliationService, new CsvExportGenerator(1));

        assertThrows(CsvExportTooLargeException.class, () -> boundedService.export(ANALYSIS_ID, registeredOwner));
    }

    private static ReconciliationResult result(UUID analysisId, Discrepancy discrepancy) {
        return new ReconciliationResult(
                analysisId, AnalysisStatus.COMPLETED, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                PriceTolerance.exactMatch(), new BigDecimal("100.0000"), new BigDecimal("110.0000"),
                new BigDecimal("10.0000"), DuplicateInvoiceWarning.none(),
                new ReconciliationSummary(0, 0, 0, 0, 0,
                        discrepancy != null && discrepancy.resolutionStatus() == DiscrepancyResolutionStatus.OPEN
                                ? 1 : 0,
                        discrepancy != null && discrepancy.resolutionStatus() == DiscrepancyResolutionStatus.ACCEPTED
                                ? 1 : 0,
                        discrepancy != null && discrepancy.resolutionStatus() == DiscrepancyResolutionStatus.RESOLVED
                                ? 1 : 0),
                List.of(), discrepancy == null ? List.of() : List.of(discrepancy));
    }

    private static Discrepancy acceptedDiscrepancy() {
        return new Discrepancy(
                UUID.fromString("61000000-0000-0000-0000-000000000004"), ANALYSIS_ID, null,
                DiscrepancyType.TOTAL_MISMATCH, "100.0000", "110.0000", new BigDecimal("10.0000"),
                new BigDecimal("10.000000"), "The invoiced total differs from the reference total.",
                DiscrepancyResolutionStatus.ACCEPTED, DiscrepancyResolutionReason.APPROVED_CHANGE,
                "reviewed, confirmed after reconciliation", 1, NOW, NOW.minusSeconds(60), NOW);
    }

}
