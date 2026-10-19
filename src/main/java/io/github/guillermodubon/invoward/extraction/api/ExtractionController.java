package io.github.guillermodubon.invoward.extraction.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.api.model.AnalysisStatusResponse;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.extraction.api.model.ConfirmExtractionRequest;
import io.github.guillermodubon.invoward.extraction.api.model.ExtractionReviewResponse;
import io.github.guillermodubon.invoward.extraction.api.model.StartExtractionRequest;
import io.github.guillermodubon.invoward.extraction.api.model.UpdateExtractionRequest;
import io.github.guillermodubon.invoward.extraction.application.service.ConfirmExtractionService;
import io.github.guillermodubon.invoward.extraction.application.service.GetExtractionService;
import io.github.guillermodubon.invoward.extraction.application.service.RequestExtractionService;
import io.github.guillermodubon.invoward.extraction.application.service.UpdateExtractionService;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/** HTTP boundary for starting owner-scoped document extraction. */
@RestController
@RequestMapping("/api/analyses/{analysisId}")
public class ExtractionController {

    private final RequestExtractionService requestExtractionService;
    private final GetExtractionService getExtractionService;
    private final UpdateExtractionService updateExtractionService;
    private final ConfirmExtractionService confirmExtractionService;
    private final AnalysisRequestOwnerResolver ownerResolver;

    public ExtractionController(
            RequestExtractionService requestExtractionService,
            GetExtractionService getExtractionService,
            UpdateExtractionService updateExtractionService,
            ConfirmExtractionService confirmExtractionService,
            AnalysisRequestOwnerResolver ownerResolver) {
        this.requestExtractionService = Objects.requireNonNull(requestExtractionService);
        this.getExtractionService = Objects.requireNonNull(getExtractionService);
        this.updateExtractionService = Objects.requireNonNull(updateExtractionService);
        this.confirmExtractionService = Objects.requireNonNull(confirmExtractionService);
        this.ownerResolver = Objects.requireNonNull(ownerResolver);
    }

    @GetMapping(path = "/extraction", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExtractionReviewResponse> getExtraction(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest servletRequest) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, servletRequest)
                .orElseThrow(AnalysisNotFoundException::new);
        ExtractionReviewResponse response = ExtractionReviewResponse.from(
                getExtractionService.get(analysisId, owner));
        ownerResolver.recordSuccessfulActivity(owner);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping(path = "/extract", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisStatusResponse> startExtraction(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            @Valid @RequestBody StartExtractionRequest request,
            HttpServletRequest servletRequest) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, servletRequest)
                .orElseThrow(AnalysisNotFoundException::new);
        AnalysisStatusSnapshot snapshot = requestExtractionService.request(
                analysisId, owner, request.toRequestTypes());
        ownerResolver.recordSuccessfulActivity(owner);
        return ResponseEntity.accepted()
                .location(URI.create("/api/analyses/" + analysisId + "/status"))
                .cacheControl(CacheControl.noStore())
                .body(AnalysisStatusResponse.from(snapshot));
    }

    @PutMapping(path = "/extraction", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExtractionReviewResponse> updateExtraction(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            @Valid @RequestBody UpdateExtractionRequest request,
            HttpServletRequest servletRequest) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, servletRequest)
                .orElseThrow(AnalysisNotFoundException::new);
        ExtractionReviewResponse response = ExtractionReviewResponse.from(
                updateExtractionService.update(analysisId, owner, request.toApplicationUpdate()));
        ownerResolver.recordSuccessfulActivity(owner);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping(path = "/extraction/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisStatusResponse> confirmExtraction(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            @Valid @RequestBody ConfirmExtractionRequest request,
            HttpServletRequest servletRequest) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, servletRequest)
                .orElseThrow(AnalysisNotFoundException::new);
        AnalysisStatusSnapshot snapshot = confirmExtractionService.confirm(
                analysisId, owner, request.toApplicationConfirmation());
        ownerResolver.recordSuccessfulActivity(owner);
        return ResponseEntity.accepted()
                .location(URI.create("/api/analyses/" + analysisId + "/status"))
                .cacheControl(CacheControl.noStore())
                .body(AnalysisStatusResponse.from(snapshot));
    }
}
