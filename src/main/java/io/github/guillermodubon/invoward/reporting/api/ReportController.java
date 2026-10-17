package io.github.guillermodubon.invoward.reporting.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.reporting.api.model.GeneratedReportResponse;
import io.github.guillermodubon.invoward.reporting.application.model.GenerateReportResult;
import io.github.guillermodubon.invoward.reporting.application.model.ReportDownload;
import io.github.guillermodubon.invoward.reporting.application.service.GenerateReportService;
import io.github.guillermodubon.invoward.reporting.application.service.GetGeneratedReportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/** HTTP boundary for creating or retrieving the immutable reconciliation report snapshot. */
@RestController
@RequestMapping("/api/analyses/{analysisId}/report")
public class ReportController {

    private final GenerateReportService generateReportService;
    private final GetGeneratedReportService getGeneratedReportService;
    private final AnalysisRequestOwnerResolver ownerResolver;

    public ReportController(
            GenerateReportService generateReportService,
            GetGeneratedReportService getGeneratedReportService,
            AnalysisRequestOwnerResolver ownerResolver) {
        this.generateReportService = Objects.requireNonNull(generateReportService);
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
    public ResponseEntity<GeneratedReportResponse> generate(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        GenerateReportResult result = generateReportService.generate(analysisId, owner);
        ownerResolver.recordSuccessfulActivity(owner);

        ResponseEntity.BodyBuilder response = result.createdByThisCall()
                ? ResponseEntity.created(URI.create("/api/analyses/" + analysisId + "/report"))
                : ResponseEntity.ok();
        return response
                .cacheControl(CacheControl.noStore())
                .body(GeneratedReportResponse.from(result.report()));
    }
}
