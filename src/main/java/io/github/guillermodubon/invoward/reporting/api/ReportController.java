package io.github.guillermodubon.invoward.reporting.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.api.model.AnalysisStatusResponse;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.service.RequestAnalysisProcessingService;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.reporting.api.model.GeneratedReportResponse;
import io.github.guillermodubon.invoward.reporting.application.model.ReportDownload;
import io.github.guillermodubon.invoward.reporting.application.service.GetGeneratedReportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/** HTTP boundary for requesting report generation and retrieving the persisted report. */
@RestController
@RequestMapping("/api/analyses/{analysisId}/report")
public class ReportController {

    private final RequestAnalysisProcessingService requestAnalysisProcessingService;
    private final GetGeneratedReportService getGeneratedReportService;
    private final AnalysisRequestOwnerResolver ownerResolver;

    public ReportController(
            RequestAnalysisProcessingService requestAnalysisProcessingService,
            GetGeneratedReportService getGeneratedReportService,
            AnalysisRequestOwnerResolver ownerResolver) {
        this.requestAnalysisProcessingService = Objects.requireNonNull(requestAnalysisProcessingService);
        this.getGeneratedReportService = Objects.requireNonNull(getGeneratedReportService);
        this.ownerResolver = Objects.requireNonNull(ownerResolver);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GeneratedReportResponse> get(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        ReportDownload report = getGeneratedReportService.get(analysisId, owner);
        ownerResolver.recordSuccessfulActivity(owner);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(GeneratedReportResponse.from(report));
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisStatusResponse> requestGeneration(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        AnalysisStatusSnapshot snapshot = requestAnalysisProcessingService.request(
                analysisId, owner, AnalysisStatus.GENERATING_REPORT);
        ownerResolver.recordSuccessfulActivity(owner);

        AnalysisStatusResponse response = AnalysisStatusResponse.from(snapshot);
        if (isPendingReportGeneration(snapshot)) {
            return ResponseEntity.accepted()
                    .location(URI.create("/api/analyses/" + analysisId + "/status"))
                    .cacheControl(CacheControl.noStore())
                    .body(response);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    private static boolean isPendingReportGeneration(AnalysisStatusSnapshot snapshot) {
        return snapshot.status() == AnalysisStatus.GENERATING_REPORT
                && snapshot.currentStage() == AnalysisStatus.GENERATING_REPORT
                && (snapshot.jobStatus() == AnalysisJobStatus.QUEUED
                        || snapshot.jobStatus() == AnalysisJobStatus.RUNNING);
    }
}
