package io.github.guillermodubon.invoward.extraction.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.api.model.AnalysisStatusResponse;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.extraction.application.service.RequestDocumentTypeDetectionService;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/** HTTP boundary for requesting asynchronous type detection of an Analysis's uploaded documents. */
@RestController
@RequestMapping("/api/analyses/{analysisId}/documents")
public class DocumentTypeDetectionController {

    private final RequestDocumentTypeDetectionService requestService;
    private final AnalysisRequestOwnerResolver ownerResolver;

    public DocumentTypeDetectionController(
            RequestDocumentTypeDetectionService requestService,
            AnalysisRequestOwnerResolver ownerResolver) {
        this.requestService = Objects.requireNonNull(requestService);
        this.ownerResolver = Objects.requireNonNull(ownerResolver);
    }

    @PostMapping(path = "/detect-types", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisStatusResponse> detectTypes(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        AnalysisStatusSnapshot snapshot = requestService.request(analysisId, owner);
        ownerResolver.recordSuccessfulActivity(owner);

        AnalysisStatusResponse response = AnalysisStatusResponse.from(snapshot);
        if (isQueuedOrRunningClassification(snapshot)) {
            return ResponseEntity.accepted()
                    .location(URI.create("/api/analyses/" + analysisId + "/status"))
                    .cacheControl(CacheControl.noStore())
                    .body(response);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    private static boolean isQueuedOrRunningClassification(AnalysisStatusSnapshot snapshot) {
        return snapshot.status() == AnalysisStatus.CLASSIFYING
                && snapshot.currentStage() == AnalysisStatus.CLASSIFYING
                && (snapshot.jobStatus() == AnalysisJobStatus.QUEUED
                        || snapshot.jobStatus() == AnalysisJobStatus.RUNNING);
    }
}
