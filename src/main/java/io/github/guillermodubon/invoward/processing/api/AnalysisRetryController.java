package io.github.guillermodubon.invoward.processing.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.api.model.AnalysisStatusResponse;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.processing.application.service.RetryAnalysisProcessingService;
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

/** HTTP boundary for retrying an owned failed Analysis stage. */
@RestController
@RequestMapping("/api/analyses/{id}")
public class AnalysisRetryController {

    private final RetryAnalysisProcessingService retryService;
    private final AnalysisRequestOwnerResolver ownerResolver;

    public AnalysisRetryController(
            RetryAnalysisProcessingService retryService,
            AnalysisRequestOwnerResolver ownerResolver) {
        this.retryService = Objects.requireNonNull(retryService);
        this.ownerResolver = Objects.requireNonNull(ownerResolver);
    }

    @PostMapping(path = "/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisStatusResponse> retry(
            @PathVariable("id") UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        AnalysisStatusSnapshot snapshot = retryService.retry(analysisId, owner);
        ownerResolver.recordSuccessfulActivity(owner);

        return ResponseEntity.accepted()
                .location(URI.create("/api/analyses/" + analysisId + "/status"))
                .cacheControl(CacheControl.noStore())
                .body(AnalysisStatusResponse.from(snapshot));
    }
}
