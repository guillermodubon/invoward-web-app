package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.analysis.api.model.AnalysisNoteResponse;
import io.github.guillermodubon.invoward.analysis.api.model.PutAnalysisNoteRequest;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisNoteService;
import io.github.guillermodubon.invoward.analysis.application.service.PutAnalysisNoteService;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/** Registered-user-only HTTP boundary for private Analysis notes. */
@RestController
@RequestMapping("/api/analyses/{analysisId}/note")
public class AnalysisNoteController {

    private final GetAnalysisNoteService getAnalysisNoteService;
    private final PutAnalysisNoteService putAnalysisNoteService;

    public AnalysisNoteController(
            GetAnalysisNoteService getAnalysisNoteService,
            PutAnalysisNoteService putAnalysisNoteService) {
        this.getAnalysisNoteService = Objects.requireNonNull(getAnalysisNoteService);
        this.putAnalysisNoteService = Objects.requireNonNull(putAnalysisNoteService);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisNoteResponse> get(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        AnalysisNoteResponse response = AnalysisNoteResponse.from(
                getAnalysisNoteService.get(analysisId, Objects.requireNonNull(identity).userId()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisNoteResponse> put(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity identity,
            @RequestBody PutAnalysisNoteRequest request) {
        AnalysisNoteResponse response = AnalysisNoteResponse.from(putAnalysisNoteService.put(
                analysisId,
                Objects.requireNonNull(identity).userId(),
                request.text(),
                request.expectedVersion()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
