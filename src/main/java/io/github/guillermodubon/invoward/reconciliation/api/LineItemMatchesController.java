package io.github.guillermodubon.invoward.reconciliation.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.reconciliation.api.model.LineItemMatchesResponse;
import io.github.guillermodubon.invoward.reconciliation.api.model.UpdateLineItemMatchRequest;
import io.github.guillermodubon.invoward.reconciliation.application.service.ConfirmLineItemMatchesService;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetLineItemMatchesService;
import io.github.guillermodubon.invoward.reconciliation.application.service.UpdateLineItemMatchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/** HTTP boundary for owner-scoped reads of persisted line-item matches. */
@RestController
@RequestMapping("/api/analyses/{analysisId}/matches")
public class LineItemMatchesController {

    private final GetLineItemMatchesService getLineItemMatchesService;
    private final UpdateLineItemMatchService updateLineItemMatchService;
    private final ConfirmLineItemMatchesService confirmLineItemMatchesService;
    private final AnalysisRequestOwnerResolver ownerResolver;

    public LineItemMatchesController(
            GetLineItemMatchesService getLineItemMatchesService,
            UpdateLineItemMatchService updateLineItemMatchService,
            ConfirmLineItemMatchesService confirmLineItemMatchesService,
            AnalysisRequestOwnerResolver ownerResolver) {
        this.getLineItemMatchesService = Objects.requireNonNull(getLineItemMatchesService);
        this.updateLineItemMatchService = Objects.requireNonNull(updateLineItemMatchService);
        this.confirmLineItemMatchesService = Objects.requireNonNull(confirmLineItemMatchesService);
        this.ownerResolver = Objects.requireNonNull(ownerResolver);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LineItemMatchesResponse> getMatches(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        LineItemMatchesResponse response = LineItemMatchesResponse.from(
                getLineItemMatchesService.get(analysisId, owner));
        ownerResolver.recordSuccessfulActivity(owner);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PatchMapping(path = "/{matchId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LineItemMatchesResponse> updateMatch(
            @PathVariable UUID analysisId,
            @PathVariable UUID matchId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request,
            @Valid @RequestBody UpdateLineItemMatchRequest updateRequest) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        LineItemMatchesResponse response = LineItemMatchesResponse.from(
                updateLineItemMatchService.update(analysisId, matchId, owner, updateRequest.toCommand()));
        ownerResolver.recordSuccessfulActivity(owner);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping(path = "/confirm")
    public ResponseEntity<Void> confirmMatches(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        confirmLineItemMatchesService.confirm(analysisId, owner);
        ownerResolver.recordSuccessfulActivity(owner);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
