package io.github.guillermodubon.invoward.reconciliation.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.api.model.AnalysisStatusResponse;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.service.RequestAnalysisProcessingService;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.reconciliation.api.model.DiscrepancyEvidenceResponse;
import io.github.guillermodubon.invoward.reconciliation.api.model.DiscrepancyResponse;
import io.github.guillermodubon.invoward.reconciliation.api.model.ReconciliationResponse;
import io.github.guillermodubon.invoward.reconciliation.api.model.UpdateDiscrepancyRequest;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetDiscrepancyEvidenceService;
import io.github.guillermodubon.invoward.reconciliation.application.service.UpdateDiscrepancyService;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.net.URI;

/** HTTP boundary for owner-scoped reconciliation commands and persisted results. */
@RestController
@RequestMapping("/api/analyses/{analysisId}")
public class ReconciliationController {

    private final GetReconciliationService getReconciliationService;
    private final GetDiscrepancyEvidenceService getDiscrepancyEvidenceService;
    private final RequestAnalysisProcessingService requestAnalysisProcessingService;
    private final UpdateDiscrepancyService updateDiscrepancyService;
    private final AnalysisRequestOwnerResolver ownerResolver;

    public ReconciliationController(
            GetReconciliationService getReconciliationService,
            GetDiscrepancyEvidenceService getDiscrepancyEvidenceService,
            RequestAnalysisProcessingService requestAnalysisProcessingService,
            UpdateDiscrepancyService updateDiscrepancyService,
            AnalysisRequestOwnerResolver ownerResolver) {
        this.getReconciliationService = Objects.requireNonNull(getReconciliationService);
        this.getDiscrepancyEvidenceService = Objects.requireNonNull(getDiscrepancyEvidenceService);
        this.requestAnalysisProcessingService = Objects.requireNonNull(requestAnalysisProcessingService);
        this.updateDiscrepancyService = Objects.requireNonNull(updateDiscrepancyService);
        this.ownerResolver = Objects.requireNonNull(ownerResolver);
    }

    @GetMapping(path = "/discrepancies/{discrepancyId}/evidence", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<DiscrepancyEvidenceResponse>> getDiscrepancyEvidence(
            @PathVariable UUID analysisId,
            @PathVariable UUID discrepancyId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        List<DiscrepancyEvidenceResponse> response = getDiscrepancyEvidenceService
                .get(analysisId, owner, discrepancyId).stream()
                .map(DiscrepancyEvidenceResponse::from)
                .toList();
        ownerResolver.recordSuccessfulActivity(owner);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping(path = "/reconciliation", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReconciliationResponse> getReconciliation(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        ReconciliationResponse response = ReconciliationResponse.from(
                getReconciliationService.get(analysisId, owner));
        ownerResolver.recordSuccessfulActivity(owner);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping(path = "/reconcile", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisStatusResponse> reconcile(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        AnalysisStatusSnapshot snapshot = requestAnalysisProcessingService.request(
                analysisId, owner, AnalysisStatus.RECONCILING);
        ownerResolver.recordSuccessfulActivity(owner);
        AnalysisStatusResponse response = AnalysisStatusResponse.from(snapshot);
        if (isPendingReconciliation(snapshot)) {
            return ResponseEntity.accepted()
                    .location(URI.create("/api/analyses/" + analysisId + "/status"))
                    .cacheControl(CacheControl.noStore())
                    .body(response);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    private static boolean isPendingReconciliation(AnalysisStatusSnapshot snapshot) {
        return snapshot.status() == AnalysisStatus.RECONCILING
                && snapshot.currentStage() == AnalysisStatus.RECONCILING
                && (snapshot.jobStatus() == AnalysisJobStatus.QUEUED
                        || snapshot.jobStatus() == AnalysisJobStatus.RUNNING);
    }

    @PatchMapping(path = "/discrepancies/{discrepancyId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DiscrepancyResponse> updateDiscrepancy(
            @PathVariable UUID analysisId,
            @PathVariable UUID discrepancyId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request,
            @Valid @RequestBody UpdateDiscrepancyRequest updateRequest) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        Discrepancy updated = updateDiscrepancyService.update(
                analysisId, owner, discrepancyId, updateRequest.toCommand());
        ownerResolver.recordSuccessfulActivity(owner);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(DiscrepancyResponse.from(updated));
    }
}
