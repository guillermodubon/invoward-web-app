package io.github.guillermodubon.invoward.reporting.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.reporting.application.model.CsvExport;
import io.github.guillermodubon.invoward.reporting.application.service.ExportReconciliationCsvService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/** HTTP boundary for downloading an on-demand CSV of the current persisted reconciliation. */
@RestController
@RequestMapping("/api/analyses/{analysisId}")
public class CsvExportController {

    private static final MediaType CSV_MEDIA_TYPE = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private final ExportReconciliationCsvService exportService;
    private final AnalysisRequestOwnerResolver ownerResolver;

    public CsvExportController(
            ExportReconciliationCsvService exportService,
            AnalysisRequestOwnerResolver ownerResolver) {
        this.exportService = Objects.requireNonNull(exportService, "exportService must not be null");
        this.ownerResolver = Objects.requireNonNull(ownerResolver, "ownerResolver must not be null");
    }

    @GetMapping(path = "/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        CsvExport export = exportService.export(analysisId, owner);
        ownerResolver.recordSuccessfulActivity(owner);

        String filename = "invoward-reconciliation-" + analysisId + ".csv";
        return ResponseEntity.ok()
                .contentType(CSV_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(export.content());
    }
}
