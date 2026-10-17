package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reporting.application.export.CsvExportGenerator;
import io.github.guillermodubon.invoward.reporting.application.model.CsvExport;

import java.util.Objects;
import java.util.UUID;

/** Exports the current persisted reconciliation only after owner-scoped Analysis authorization. */
public class ExportReconciliationCsvService {

    private final GetAnalysisService analysisService;
    private final GetReconciliationService reconciliationService;
    private final CsvExportGenerator csvExportGenerator;

    public ExportReconciliationCsvService(
            GetAnalysisService analysisService,
            GetReconciliationService reconciliationService,
            CsvExportGenerator csvExportGenerator) {
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService must not be null");
        this.reconciliationService = Objects.requireNonNull(
                reconciliationService, "reconciliationService must not be null");
        this.csvExportGenerator = Objects.requireNonNull(csvExportGenerator, "csvExportGenerator must not be null");
    }

    /** Returns a complete bounded export; no report generation or workflow mutation is involved. */
    public CsvExport export(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        // Keep the owner check ahead of reconciliation reads and all export work.
        analysisService.get(analysisId, owner);
        ReconciliationResult reconciliation = reconciliationService.get(analysisId, owner);
        return csvExportGenerator.generate(reconciliation);
    }
}
